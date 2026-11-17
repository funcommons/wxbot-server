package com.wechat.sync.server.subscription.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.sync.server.message.entity.WxMessageEntity;
import com.wechat.sync.server.message.mapper.WxMessageAttachmentMapper;
import com.wechat.sync.server.message.mapper.WxMessageMapper;
import com.wechat.sync.server.subscription.entity.WxConsumerEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionDeliveryEntity;
import com.wechat.sync.server.subscription.entity.WxSubscriptionEntity;
import com.wechat.sync.server.subscription.mapper.WxConsumerMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.mapper.WxSubscriptionMapper;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * DeliveryService 单测（mock mapper，只测编排契约；SQL 语义归 T10-8 真库 IT）。
 * 矩阵（SUBSCRIPTION_DESIGN §4/§5.2/§5.3/§6/§9）：
 * <p>① 退避数学（纯函数 base×2^(n-1) 封顶 3600）；② WEBHOOK 终态重放判定（requireDelivery 先行——
 * 行缺失 10605；条件 UPDATE 0 行 → 复查询定性：同 lease 幂等 / 异 lease 10606，口径同
 * TaskService.replayOrLeaseLost）；③ 失败分支（attempts&lt;max → requeueForRetry 按退避、退避基数回
 * 订阅表宽版 selectById；≥max → failDead）；④ PULL（归属走 selectOne：缺失 10605 / 不符 10300 /
 * 非 POLL 10402 / 组装按认领序 + 摘要不含 content_raw 族 + has_more=countDuePending）；
 * ⑤ ack/nack 逐行 (deliveryId, leaseId) 命令对（认领 SQL 逐行 gen_random_uuid，单 lease 批量
 * UPDATE 数学不可能命中多行——业务裁决）+ 逐条 AckItemResult(success/replayed/error) 10700 语义；
 * ⑥ DLQ 救济（requeueDeadRows / redeliver mapper 原语，SQL 语义锚在 SubscriptionEntitiesMappingTest）；
 * ⑦ authenticateConsumer（sha256 十六进制 + 10300 field=consumer_token 口径）。
 *
 * <p><b>对齐记录</b>（测试叉发明面 → 落盘现实，断言意图全保留）：ctor 6 参（+attachmentMapper/objectMapper）；
 * complete/fail 返回 WebhookReportResult(replayed,status)（非 void）；pull 返回 PullResultVO（组件名
 * messages 非 items）；ack/nack 逐行命令对（非单 lease 批量）；行缺失统一 10605（requireDelivery 先行，
 * 原「行缺失 10606」翻转）；nack scope 不符 10605（原假设 10300）；requeueDead/redeliver 走 mapper
 * 方法（wrapper captor 锚移交 SQL 片段契约测试）；failWebhook attempts=3 base=30 → 120（与退避矩阵一致）；
 * nack error 落库文案 "nack"（非 null）；订阅读取 selectOne（归属）+ selectById 宽版（failWebhook 退避基数）；
 * completeDelivered 增第三参 responseCode（WEBHOOK 落 HTTP 状态码 200；POLL ack 无 HTTP 响应 → 传 null）。
 */
@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    private static final long SUB_ID = 901L;
    private static final long CONSUMER_ID = 701L;
    private static final long OTHER_CONSUMER_ID = 702L;
    private static final String LEASE_A = "lease-a";
    private static final String LEASE_B = "lease-b";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** RFC 公开向量：sha256("abc")——钉死 authenticateConsumer 的哈希算法与十六进制口径。 */
    private static final String SHA256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Mock private WxSubscriptionDeliveryMapper deliveryMapper;
    @Mock private WxSubscriptionMapper subscriptionMapper;
    @Mock private WxConsumerMapper consumerMapper;
    @Mock private WxMessageMapper messageMapper;
    @Mock private WxMessageAttachmentMapper attachmentMapper;

    private DeliveryService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxSubscriptionDeliveryEntity.class, WxSubscriptionEntity.class,
                WxConsumerEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeliveryService(deliveryMapper, subscriptionMapper, consumerMapper,
                messageMapper, attachmentMapper, JSON);
    }

    // ------------------------------------------------------------ 夹具

    private WxSubscriptionEntity sub(String deliveryMode) {
        WxSubscriptionEntity sub = new WxSubscriptionEntity();
        sub.setId(SUB_ID);
        sub.setConsumerId(CONSUMER_ID);
        sub.setDeliveryMode(deliveryMode);
        sub.setBackoffBaseSeconds(30);
        sub.setVisibleSeconds(60);
        sub.setMaxAttempts(8);
        sub.setEnabled(1);
        sub.setIsDeleted(0);
        return sub;
    }

    private WxSubscriptionDeliveryEntity row(long id, long msgId, String status,
                                             int attempts, int maxAttempts, String leaseId) {
        WxSubscriptionDeliveryEntity row = new WxSubscriptionDeliveryEntity();
        row.setId(id);
        row.setSubscriptionId(SUB_ID);
        row.setConsumerId(CONSUMER_ID);
        row.setMessageId(msgId);
        row.setStatus(status);
        row.setAttempts(attempts);
        row.setMaxAttempts(maxAttempts);
        row.setLeaseSeconds(60);
        row.setLeaseId(leaseId);
        return row;
    }

    private WxMessageEntity messageEntity(long id, String body) {
        WxMessageEntity msg = new WxMessageEntity();
        msg.setId(id);
        msg.setDedupKey("srv:" + id);
        msg.setChatUsername("room@chatroom");
        msg.setIsGroup(true);
        msg.setSenderUsername("wxid_sender");
        msg.setTypeCode("1");
        msg.setTypeName("文本");
        msg.setBody(body);
        msg.setCreateTimeRaw(1731216000L);
        msg.setIsRevoked(false);
        msg.setContentRaw("<xml>大字段不投递</xml>");
        msg.setContentSha256("deadbeef");
        return msg;
    }

    private WxConsumerEntity consumer(String tokenHash) {
        WxConsumerEntity consumer = new WxConsumerEntity();
        consumer.setId(CONSUMER_ID);
        consumer.setTokenHash(tokenHash);
        consumer.setIsDeleted(0);
        return consumer;
    }

    /** ack/nack 逐行命令对（业务裁决：认领 SQL 逐行 gen_random_uuid，汇报必须逐行携带 lease）。 */
    private static DeliveryVOs.DeliveryAckCommand cmd(long deliveryId, String leaseId) {
        return new DeliveryVOs.DeliveryAckCommand(deliveryId, leaseId);
    }

    /** 提取 ApiException 业务码（TaskServiceTest 的 satisfies 形态在此收敛成单一助手）。 */
    private static int bizCode(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e.getCode();
        } catch (RuntimeException e) {
            throw new AssertionError("未抛 ApiException：" + e, e);
        }
        throw new AssertionError("未抛任何异常");
    }

    // ================================================================ A. 退避数学（纯函数）

    @ParameterizedTest(name = "base={0} attempts={1} → {2}s（§5.2 base×2^(n-1) 封顶 3600）")
    @CsvSource({
            "30, 1, 30",
            "30, 2, 60",
            "30, 3, 120",
            "30, 4, 240",
            "30, 5, 480",
            "30, 6, 960",
            "30, 7, 1920",
            "30, 8, 3600",   // 3840 封顶
            "30, 9, 3600",   // 超限继续封顶
            "5, 1, 5",
            "5, 3, 20",
            "30, 0, 30",     // 边界：attempts<1 视同首次
            "30, -3, 30",
    })
    void nextBackoffSecondsFollowsExponentialWithOneHourCap(int base, int attempts, int expected) {
        assertThat(DeliveryService.nextBackoffSeconds(base, attempts)).isEqualTo(expected);
    }

    // ================================================================ H. WEBHOOK 认领与组装（dispatchDue）

    @Test
    void dispatchDueAssemblesTasksWithResolvedUrlAndFinalPayload() {
        // 认领行 → 组装 WebhookDeliveryTask：url 取订阅覆写、payloadJson 含投递身份与消息摘要
        WxSubscriptionEntity withUrl = sub("WEBHOOK");
        withUrl.setWebhookUrl("https://cb.example/hook");
        given(deliveryMapper.claimDueWebhook(5, 4)).willReturn(
                List.of(row(1L, 11L, "DISPATCHED", 2, 8, LEASE_A)));
        given(messageMapper.selectBatchIds(List.of(11L)))
                .willReturn(List.of(messageEntity(11L, "正文-11")));
        given(subscriptionMapper.selectBatchIds(List.of(SUB_ID))).willReturn(List.of(withUrl));

        List<DeliveryVOs.WebhookDeliveryTask> tasks = service.dispatchDue(5, 4);

        assertThat(tasks).hasSize(1);
        DeliveryVOs.WebhookDeliveryTask task = tasks.get(0);
        assertThat(task.deliveryId()).isEqualTo(1L);
        assertThat(task.leaseId()).isEqualTo(LEASE_A);
        assertThat(task.attempts()).isEqualTo(2);
        assertThat(task.consumerId()).isEqualTo(CONSUMER_ID);
        assertThat(task.url()).isEqualTo("https://cb.example/hook");
        // §5.1：载荷是最终序列化文本（T10-5 HMAC 按原始字节算，不得重序列化）；身份字段齐备
        assertThat(task.payloadJson()).contains("\"delivery_id\":1", "\"subscription_id\":901",
                "\"consumer_id\":701", "\"attempt\":2", "正文-11");
    }

    @Test
    void dispatchDueEmptyClaimReturnsEmptyWithoutTouchingLoaders() {
        given(deliveryMapper.claimDueWebhook(5, 4)).willReturn(List.of());

        assertThat(service.dispatchDue(5, 4)).isEmpty();
        verifyNoInteractions(messageMapper, attachmentMapper, subscriptionMapper, consumerMapper);
    }

    @Test
    void dispatchDueWithoutAnyUrlRequeuesQuietlyInsteadOfDead() {
        // URL 双空（订阅与消费者均未配置）→ 60s 退避重排不投（配置错不打死信，等运营补 URL）
        given(deliveryMapper.claimDueWebhook(5, 4)).willReturn(
                List.of(row(1L, 11L, "DISPATCHED", 1, 8, LEASE_A)));
        given(subscriptionMapper.selectBatchIds(List.of(SUB_ID))).willReturn(List.of(sub("WEBHOOK")));

        assertThat(service.dispatchDue(5, 4)).isEmpty();

        verify(deliveryMapper).requeueForRetry(eq(1L), eq(LEASE_A), eq(60), isNull(),
                eq("webhook_url 未配置（订阅与消费者均空）"));
    }

    // ================================================================ 透传：sweep

    @Test
    void sweepExpiredPassesThroughMapperCount() {
        given(deliveryMapper.sweepExpiredLeases()).willReturn(7);

        assertThat(service.sweepExpired()).isEqualTo(7);
    }

    // ================================================================ B. WEBHOOK 终态重放判定（10606 口径）

    @Test
    void completeWebhookConditionalHitMarksDelivered() {
        // requireDelivery 先行（DISPATCHED 活动态放行）→ 条件 UPDATE 命中即终态，无复查询
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DISPATCHED", 1, 8, LEASE_A));
        given(deliveryMapper.completeDelivered(501L, LEASE_A, 200)).willReturn(1);

        DeliveryService.WebhookReportResult result = service.completeWebhook(501L, LEASE_A, 200);

        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo("DELIVERED");
        verify(deliveryMapper, times(1)).selectById(501L);   // 仅 requireDelivery 一次，0 行才复查询
    }

    @Test
    void completeWebhookPersistsHttpResponseCodeToMapper() {
        // P1 回归锚点：WEBHOOK 成功汇报必须把 HTTP 状态码原样透传到 mapper 落库
        // （此前 completeDelivered 的 SET 漏写 response_code → 投递成功行状态码恒 NULL，
        //  消费方排障/DLQ 分析断链；失败行有 response_code 而成功行没有，语义不对称）
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DISPATCHED", 1, 8, LEASE_A));
        given(deliveryMapper.completeDelivered(501L, LEASE_A, 200)).willReturn(1);

        DeliveryService.WebhookReportResult result = service.completeWebhook(501L, LEASE_A, 200);

        assertThat(result.status()).isEqualTo("DELIVERED");
        verify(deliveryMapper).completeDelivered(eq(501L), eq(LEASE_A), eq(200));
    }

    @Test
    void completeWebhookZeroRowReplayedSameLeaseIsIdempotent() {
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DISPATCHED", 1, 8, LEASE_A),
                        row(501L, 11L, "DELIVERED", 1, 8, LEASE_A));
        given(deliveryMapper.completeDelivered(501L, LEASE_A, 200)).willReturn(0);

        DeliveryService.WebhookReportResult result = service.completeWebhook(501L, LEASE_A, 200);

        assertThat(result.replayed()).isTrue();              // 同 lease 重放幂等成功
        assertThat(result.status()).isEqualTo("DELIVERED");
    }

    @Test
    void completeWebhookTerminalRowDifferentLeaseThrows10606() {
        // 汇报所持 lease 与终态行不一致 → 租约已易主（首查即定性，条件 UPDATE 不再发起）
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DELIVERED", 1, 8, LEASE_B));

        assertThat(bizCode(() -> service.completeWebhook(501L, LEASE_A, 200))).isEqualTo(10606);
        verify(deliveryMapper, never()).completeDelivered(anyLong(), anyString(), any());
    }

    @Test
    void completeWebhookRowMissingThrows10605() {
        // 行缺失 10605（requireDelivery 先行的落盘语义；10606 留给「行在但租约不符」）
        given(deliveryMapper.selectById(501L)).willReturn(null);

        assertThat(bizCode(() -> service.completeWebhook(501L, LEASE_A, 200))).isEqualTo(10605);
        verify(deliveryMapper, never()).completeDelivered(anyLong(), anyString(), any());
    }

    @Test
    void completeWebhookZeroRowReclaimedBySweepThrows10606() {
        // 条件 UPDATE 0 行 → 复查询仍是 DISPATCHED（租约已被 sweep 清掉重派）→ 10606
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DISPATCHED", 1, 8, LEASE_B));
        given(deliveryMapper.completeDelivered(501L, LEASE_A, 200)).willReturn(0);

        assertThat(bizCode(() -> service.completeWebhook(501L, LEASE_A, 200))).isEqualTo(10606);
    }

    // ================================================================ C. 失败路径分支（退避 / DEAD / 透传）

    @Test
    void failWebhookUnderMaxRetriesRequeuesWithComputedBackoff() {
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DISPATCHED", 3, 8, LEASE_A));
        given(subscriptionMapper.selectById(SUB_ID)).willReturn(sub("WEBHOOK"));
        given(deliveryMapper.requeueForRetry(eq(501L), eq(LEASE_A), eq(120), eq(503), eq("upstream 5xx")))
                .willReturn(1);

        DeliveryService.WebhookReportResult result = service.failWebhook(501L, LEASE_A, 503, "upstream 5xx");

        // attempts=3、base=30 → 120（与退避矩阵一致）；responseCode/error 透传落库
        assertThat(result.status()).isEqualTo("PENDING");
        verify(deliveryMapper).requeueForRetry(eq(501L), eq(LEASE_A), eq(120), eq(503), eq("upstream 5xx"));
        verify(deliveryMapper, never()).failDead(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void failWebhookAtMaxRetriesGoesDead() {
        given(deliveryMapper.selectById(501L))
                .willReturn(row(501L, 11L, "DISPATCHED", 8, 8, LEASE_A));
        given(deliveryMapper.failDead(501L, LEASE_A, 503, "upstream 5xx")).willReturn(1);

        DeliveryService.WebhookReportResult result = service.failWebhook(501L, LEASE_A, 503, "upstream 5xx");

        assertThat(result.status()).isEqualTo("DEAD");
        verify(deliveryMapper).failDead(501L, LEASE_A, 503, "upstream 5xx");
        verify(deliveryMapper, never()).requeueForRetry(anyLong(), anyString(), anyInt(), any(), any());
        verifyNoInteractions(subscriptionMapper);            // DEAD 分支不需要退避基数
    }

    @Test
    void failWebhookRowMissingThrows10605() {
        given(deliveryMapper.selectById(501L)).willReturn(null);

        assertThat(bizCode(() -> service.failWebhook(501L, LEASE_A, 500, "boom"))).isEqualTo(10605);
        verify(deliveryMapper, never()).requeueForRetry(anyLong(), anyString(), anyInt(), any(), any());
    }

    @Test
    void failWebhookZeroRowRaceDeliveredSameLeaseIsIdempotent() {
        // select 说 DISPATCHED → 条件更新 0 行（竞态已被人先标 DELIVERED）→ 复查询同 lease 幂等
        given(deliveryMapper.selectById(501L)).willReturn(row(501L, 11L, "DISPATCHED", 2, 8, LEASE_A),
                row(501L, 11L, "DELIVERED", 2, 8, LEASE_A));
        given(subscriptionMapper.selectById(SUB_ID)).willReturn(sub("WEBHOOK"));
        given(deliveryMapper.requeueForRetry(eq(501L), eq(LEASE_A), anyInt(), any(), any())).willReturn(0);

        DeliveryService.WebhookReportResult result = service.failWebhook(501L, LEASE_A, 500, "boom");

        assertThat(result.replayed()).isTrue();
        assertThat(result.status()).isEqualTo("DELIVERED");
    }

    @Test
    void failWebhookZeroRowRaceOtherLeaseThrows10606() {
        given(deliveryMapper.selectById(501L)).willReturn(row(501L, 11L, "DISPATCHED", 2, 8, LEASE_A),
                row(501L, 11L, "DISPATCHED", 2, 8, LEASE_B));
        given(subscriptionMapper.selectById(SUB_ID)).willReturn(sub("WEBHOOK"));
        given(deliveryMapper.requeueForRetry(eq(501L), eq(LEASE_A), anyInt(), any(), any())).willReturn(0);

        assertThat(bizCode(() -> service.failWebhook(501L, LEASE_A, 500, "boom"))).isEqualTo(10606);
    }

    // ================================================================ D. PULL 语义（§6）

    @Test
    void pullSubscriptionMissingThrows10605() {
        given(subscriptionMapper.selectOne(any())).willReturn(null);

        assertThat(bizCode(() -> service.pull(SUB_ID, CONSUMER_ID, 50))).isEqualTo(10605);
    }

    @Test
    void pullOwnershipMismatchThrows10300() {
        WxSubscriptionEntity foreign = sub("POLL");
        foreign.setConsumerId(OTHER_CONSUMER_ID);
        given(subscriptionMapper.selectOne(any())).willReturn(foreign);

        assertThat(bizCode(() -> service.pull(SUB_ID, CONSUMER_ID, 50))).isEqualTo(10300);
        verify(deliveryMapper, never()).claimForPull(anyLong(), anyInt());
    }

    @Test
    void pullWebhookSubscriptionRejectedThrows10402() {
        // 非 POLL 订阅拒绝拉取（10402 状态冲突定夺）：两形互斥在 SQL 层兜底，此处显式拒绝给出可诊断码
        given(subscriptionMapper.selectOne(any())).willReturn(sub("WEBHOOK"));

        assertThat(bizCode(() -> service.pull(SUB_ID, CONSUMER_ID, 50))).isEqualTo(10402);
        verify(deliveryMapper, never()).claimForPull(anyLong(), anyInt());
    }

    @Test
    void pullAssemblesClaimOrderSummaryWithoutLargeFields() throws Exception {
        // 组装按认领序返回（批内守序的契约在 claim SQL ORDER BY d.id——SQL 片段锚在
        // SubscriptionEntitiesMappingTest；此处断言装配保序 + 摘要口径）
        WxSubscriptionDeliveryEntity first = row(1001L, 44L, "DISPATCHED", 1, 8, LEASE_A);
        WxSubscriptionDeliveryEntity second = row(1002L, 55L, "DISPATCHED", 1, 8, LEASE_B);
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.claimForPull(SUB_ID, 50)).willReturn(List.of(first, second));
        given(messageMapper.selectBatchIds(List.of(44L, 55L)))
                .willReturn(List.of(messageEntity(44L, "正文-44"), messageEntity(55L, "正文-55")));
        given(deliveryMapper.countDuePending(SUB_ID)).willReturn(0L);

        DeliveryVOs.PullResultVO result = service.pull(SUB_ID, CONSUMER_ID, 50);

        assertThat(result.subscriptionId()).isEqualTo(SUB_ID);
        assertThat(result.visibilitySeconds()).isEqualTo(60);            // 订阅快照 visible_seconds
        assertThat(result.messages()).extracting(DeliveryVOs.PullItemVO::deliveryId)
                .containsExactly(1001L, 1002L);
        assertThat(result.messages()).extracting(DeliveryVOs.PullItemVO::leaseId)
                .containsExactly(LEASE_A, LEASE_B);
        assertThat(result.hasMore()).isFalse();
        // 摘要口径锚（§5.1）：大字段不投递——两种命名法都不允许出现
        String json = JSON.writeValueAsString(result.messages());
        assertThat(json).doesNotContain("contentRaw", "content_raw", "contentSha256", "content_sha256");
        assertThat(json).contains("正文-44");
        verify(deliveryMapper).countDuePending(SUB_ID);                  // has_more 到期计数
    }

    @Test
    void pullHasMoreFollowsDuePendingCount() {
        // has_more 机制=认领后再数一次到期 PENDING：0 → false；>0 → true
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.claimForPull(SUB_ID, 5)).willReturn(List.of(
                row(1L, 11L, "DISPATCHED", 1, 8, LEASE_A),
                row(2L, 12L, "DISPATCHED", 1, 8, LEASE_A)));
        given(deliveryMapper.countDuePending(SUB_ID)).willReturn(0L, 9L);
        given(messageMapper.selectBatchIds(anyList())).willReturn(List.of());

        assertThat(service.pull(SUB_ID, CONSUMER_ID, 5).hasMore()).isFalse();
        assertThat(service.pull(SUB_ID, CONSUMER_ID, 50).hasMore()).isTrue();
    }

    @Test
    void pullWithNoDueRowsReturnsEmptyAndSkipsMessageFetch() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.claimForPull(SUB_ID, 50)).willReturn(List.of());
        given(deliveryMapper.countDuePending(SUB_ID)).willReturn(0L);

        DeliveryVOs.PullResultVO result = service.pull(SUB_ID, CONSUMER_ID, 50);

        assertThat(result.messages()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        verifyNoInteractions(messageMapper, attachmentMapper);           // 空认领不取消息/附件
    }

    @Test
    void pullItemsCarryAttemptNumberForConsumerRetryObservability() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.claimForPull(SUB_ID, 50))
                .willReturn(List.of(row(1001L, 44L, "DISPATCHED", 3, 8, LEASE_A)));
        given(messageMapper.selectBatchIds(List.of(44L)))
                .willReturn(List.of(messageEntity(44L, "正文-44")));
        given(deliveryMapper.countDuePending(SUB_ID)).willReturn(0L);

        DeliveryVOs.PullResultVO result = service.pull(SUB_ID, CONSUMER_ID, 50);

        assertThat(result.messages()).extracting(DeliveryVOs.PullItemVO::attempt).containsExactly(3);
    }

    // ================================================================ E. ack / nack（逐行命令对）

    @Test
    void ackBatchReportsPerItemResultsIncludingIdempotentReplay() {
        // 三行三态逐行命令：命中 ok(replayed=false) / 同 lease 重放幂等(replayed=true) / 失效逐条 10606
        // （10700 部分成功语义在响应层——批量 data 非 null，失败以逐条 error 表达）
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_A));
        given(deliveryMapper.selectById(1002L))
                .willReturn(row(1002L, 12L, "DISPATCHED", 1, 8, LEASE_A),
                        row(1002L, 12L, "DELIVERED", 1, 8, LEASE_A));
        given(deliveryMapper.selectById(1003L))
                .willReturn(row(1003L, 13L, "DISPATCHED", 1, 8, LEASE_B));
        given(deliveryMapper.completeDelivered(1001L, LEASE_A, null)).willReturn(1);
        given(deliveryMapper.completeDelivered(1002L, LEASE_A, null)).willReturn(0);
        given(deliveryMapper.completeDelivered(1003L, LEASE_B, null)).willReturn(0);
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));

        List<DeliveryVOs.AckItemResult> results = service.ack(SUB_ID, CONSUMER_ID,
                List.of(cmd(1001L, LEASE_A), cmd(1002L, LEASE_A), cmd(1003L, LEASE_B)));

        assertThat(results).extracting(DeliveryVOs.AckItemResult::deliveryId)
                .containsExactly(1001L, 1002L, 1003L);
        assertThat(results).extracting(DeliveryVOs.AckItemResult::success)
                .containsExactly(true, true, false);
        assertThat(results).extracting(DeliveryVOs.AckItemResult::replayed)
                .containsExactly(false, true, false);
        assertThat(results.get(2).error()).contains("10606");
    }

    @Test
    void ackPersistsNullResponseCodeBecausePollHasNoHttpResponse() {
        // P1 回归锚点（对称面）：POLL ack 无 HTTP 响应 → response_code 必须传 null 落库
        // （与 WEBHOOK 成功行的 200 可区分——「成功」不靠猜状态码）
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_A));
        given(deliveryMapper.completeDelivered(1001L, LEASE_A, null)).willReturn(1);

        List<DeliveryVOs.AckItemResult> results =
                service.ack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)));

        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).replayed()).isFalse();
        verify(deliveryMapper).completeDelivered(eq(1001L), eq(LEASE_A), isNull());
    }

    @Test
    void ackOwnershipMismatchThrows10300() {
        WxSubscriptionEntity foreign = sub("POLL");
        foreign.setConsumerId(OTHER_CONSUMER_ID);
        given(subscriptionMapper.selectOne(any())).willReturn(foreign);

        assertThat(bizCode(() -> service.ack(SUB_ID, CONSUMER_ID, List.of(cmd(1L, LEASE_A)))))
                .isEqualTo(10300);
        verifyNoInteractions(deliveryMapper);
    }

    @Test
    void ackMissingSubscriptionThrows10605() {
        given(subscriptionMapper.selectOne(any())).willReturn(null);

        assertThat(bizCode(() -> service.ack(SUB_ID, CONSUMER_ID, List.of(cmd(1L, LEASE_A)))))
                .isEqualTo(10605);
    }

    @Test
    void nackFirstAttemptRequeuesImmediatelyWithZeroBackoff() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_A));
        // 回队 UPDATE 命中 1 行（Mockito 未桩 int 方法返回 0 → 业务误走 0 行竞态路径 → 10606）
        given(deliveryMapper.requeueForRetry(eq(1001L), eq(LEASE_A), eq(0), isNull(), eq("nack")))
                .willReturn(1);

        List<DeliveryVOs.AckItemResult> results =
                service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)));

        assertThat(results.get(0).success()).isTrue();
        // 首条 nack 立即重试（§4「nack → 回 PENDING（立即重试）」）：backoffSeconds=0、error 文案 "nack"
        verify(deliveryMapper).requeueForRetry(eq(1001L), eq(LEASE_A), eq(0), isNull(), eq("nack"));
    }

    @Test
    void nackRepeatAttemptAppliesExponentialBackoff() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 3, 8, LEASE_A));

        service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)));

        // 非首 nack 按退避排（§4「非首次回队按退避排 next_attempt_at」）：base=30（订阅快照）, attempts=3 → 120
        verify(deliveryMapper).requeueForRetry(eq(1001L), eq(LEASE_A), eq(120), isNull(), eq("nack"));
    }

    @Test
    void nackExhaustedAttemptsGoesDeadWithSuccessResult() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 8, 8, LEASE_A));
        // DEAD 终局 UPDATE 命中 1 行（同上：未桩 int 方法返 0 → 误走 10606）
        given(deliveryMapper.failDead(eq(1001L), eq(LEASE_A), isNull(), eq("nack: 重试额度用尽")))
                .willReturn(1);

        List<DeliveryVOs.AckItemResult> results =
                service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)));

        // DEAD 是 nack 的合法终局（额度用尽）：逐条结果仍 success（DLQ 由 requeue-dead 救济）
        assertThat(results.get(0).success()).isTrue();
        verify(deliveryMapper).failDead(eq(1001L), eq(LEASE_A), isNull(), eq("nack: 重试额度用尽"));
        verify(deliveryMapper, never()).requeueForRetry(anyLong(), anyString(), anyInt(), any(), any());
    }

    @Test
    void nackLostLeaseOnDeliveredRowIsIdempotentOk() {
        // nack 撞上已被 ack 的行：DELIVERED 优于 nack——同 lease 幂等 ok（replayed）
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_A),
                        row(1001L, 11L, "DELIVERED", 1, 8, LEASE_A));
        given(deliveryMapper.requeueForRetry(eq(1001L), eq(LEASE_A), anyInt(), any(), any())).willReturn(0);

        List<DeliveryVOs.AckItemResult> results =
                service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)));

        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).replayed()).isTrue();
    }

    @Test
    void nackOtherLeaseReports10606PerItem() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_A),
                        row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_B));
        given(deliveryMapper.requeueForRetry(eq(1001L), eq(LEASE_A), anyInt(), any(), any())).willReturn(0);

        List<DeliveryVOs.AckItemResult> results =
                service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)));

        // 异 lease 不炸整批：逐条 success=false + error 10606（10700 部分成功语义）
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).error()).contains("10606");
    }

    @Test
    void nackRowMissingThrows10605() {
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.selectById(1001L)).willReturn(null);

        assertThat(bizCode(() -> service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)))))
                .isEqualTo(10605);
    }

    @Test
    void nackRowOutOfScopeThrows10605() {
        // 投递行不属于该订阅（scope=subscriptionId 校验）：10605 资源缺失——订阅级归属 10300 已在入口挡
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        WxSubscriptionDeliveryEntity foreignScope = row(1001L, 11L, "DISPATCHED", 1, 8, LEASE_A);
        foreignScope.setSubscriptionId(999L);
        given(deliveryMapper.selectById(1001L)).willReturn(foreignScope);

        assertThat(bizCode(() -> service.nack(SUB_ID, CONSUMER_ID, List.of(cmd(1001L, LEASE_A)))))
                .isEqualTo(10605);
        verify(deliveryMapper, never()).requeueForRetry(anyLong(), anyString(), anyInt(), any(), any());
    }

    // ================================================================ F. DLQ 救济（requeue-dead / redeliver）

    @Test
    void requeueDeadResetsAttemptsAndClearsLeaseViaMapperPrimitive() {
        // DEAD→PENDING（attempts=0、清 lease、立即到期）——SQL 语义锚在 SubscriptionEntitiesMappingTest
        // （requeueDeadRows 的 SET 文本）；此处断编排：归属校验后调原语并透传行数
        given(subscriptionMapper.selectOne(any())).willReturn(sub("POLL"));
        given(deliveryMapper.requeueDeadRows(SUB_ID)).willReturn(4);

        assertThat(service.requeueDead(SUB_ID, CONSUMER_ID)).isEqualTo(4);
        verify(deliveryMapper).requeueDeadRows(SUB_ID);
    }

    @Test
    void requeueDeadSubscriptionMissingThrows10605() {
        given(subscriptionMapper.selectOne(any())).willReturn(null);

        assertThat(bizCode(() -> service.requeueDead(SUB_ID, CONSUMER_ID))).isEqualTo(10605);
        verify(deliveryMapper, never()).requeueDeadRows(anyLong());
    }

    @Test
    void redeliverDeliveredRowRequeuesWithoutAttemptsReset() {
        // DELIVERED 误投救济（§4 v1.1）：DELIVERED→PENDING 立即到期，attempts **续算不归零**
        // （redeliver SQL 的 SET 不含 attempts——SQL 锚在 SubscriptionEntitiesMappingTest）
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "DELIVERED", 5, 8, LEASE_A));
        given(deliveryMapper.redeliver(1001L)).willReturn(1);

        assertThat(service.redeliver(1001L, CONSUMER_ID)).isEqualTo(1001L);
        verify(deliveryMapper).redeliver(1001L);
    }

    @Test
    void redeliverNonDeliveredRowThrows10402() {
        given(deliveryMapper.selectById(1001L))
                .willReturn(row(1001L, 11L, "PENDING", 1, 8, null));

        assertThat(bizCode(() -> service.redeliver(1001L, CONSUMER_ID))).isEqualTo(10402);
        verify(deliveryMapper, never()).redeliver(anyLong());
    }

    @Test
    void redeliverRowMissingThrows10605() {
        given(deliveryMapper.selectById(1001L)).willReturn(null);

        assertThat(bizCode(() -> service.redeliver(1001L, CONSUMER_ID))).isEqualTo(10605);
    }

    @Test
    void redeliverOwnershipMismatchThrows10300() {
        WxSubscriptionDeliveryEntity foreign = row(1001L, 11L, "DELIVERED", 1, 8, LEASE_A);
        foreign.setConsumerId(OTHER_CONSUMER_ID);
        given(deliveryMapper.selectById(1001L)).willReturn(foreign);

        assertThat(bizCode(() -> service.redeliver(1001L, CONSUMER_ID))).isEqualTo(10300);
        verify(deliveryMapper, never()).redeliver(anyLong());
    }

    // ================================================================ G. POLL 消费者凭据

    @Test
    void authenticateConsumerResolvesBySha256Hex() throws Exception {
        given(consumerMapper.selectOne(any())).willReturn(consumer(SHA256_ABC));

        WxConsumerEntity resolved = service.authenticateConsumer("abc");

        assertThat(resolved.getId()).isEqualTo(CONSUMER_ID);
        // 算法钉死：入参 sha256 十六进制小写（RFC 向量），wrapper 参数值即该哈希
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<WxConsumerEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(consumerMapper).selectOne(captor.capture());
        // MP 3.5.15 懒注册：paramNameValuePairs 在 getSqlSegment() 物化（未触发前恒 {}）——先求段再断参数
        captor.getValue().getSqlSegment();
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(SHA256_ABC);
        // 双保险：自算哈希与公开向量一致（防测试自身向量写错）
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(SHA256_ABC);
    }

    @Test
    void authenticateConsumerUnknownTokenThrows10300() {
        given(consumerMapper.selectOne(any())).willReturn(null);

        assertThat(bizCode(() -> service.authenticateConsumer("ghost-token"))).isEqualTo(10300);
    }

    @Test
    void authenticateConsumerBlankTokenThrows10300WithoutDbHit() {
        assertThat(bizCode(() -> service.authenticateConsumer("  "))).isEqualTo(10300);
        assertThat(bizCode(() -> service.authenticateConsumer(null))).isEqualTo(10300);
        verifyNoInteractions(consumerMapper);
    }

    // ================================================================ 兜底：异常语义健全性

    @Test
    void completeWebhookMissingRowErrorMessageNamesDelivery() {
        given(deliveryMapper.selectById(501L)).willReturn(null);

        assertThatThrownBy(() -> service.completeWebhook(501L, LEASE_A, 200))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("501");
    }
}
