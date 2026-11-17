package com.wechat.sync.server.account.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.account.dto.AccountRegisterRequest;
import com.wechat.sync.server.account.dto.RegCodeIssueVO;
import com.wechat.sync.server.account.dto.RegCodeVO;
import com.wechat.sync.server.account.entity.WxAccountEntity;
import com.wechat.sync.server.account.entity.WxRegCodeEntity;
import com.wechat.sync.server.account.mapper.WxAccountMapper;
import com.wechat.sync.server.account.mapper.WxRegCodeMapper;
import com.wechat.sync.server.auth.dto.RegisterRequest;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 注册码服务单测（TASK_DISPATCH §2.1 v1.1）：签发明文格式与哈希落库、撞哈希重生成与上限、
 * 分页审计视图（不回明文）、作废三分支、自助注册校验次序（10102→10400→10402×3→10401→打点）。
 * SQL 文本与并发双消费不在此测（真库 IT 职责），LambdaWrapper 只验证「被调」不拆内容。
 */
@ExtendWith(MockitoExtension.class)
class RegCodeServiceTest {

    private static final String ACCOUNT = "wxid_t9test_a";
    private static final String WXID = "wxid_t9";
    private static final String HOST = "host-t9";

    @Mock private WxRegCodeMapper regCodeMapper;
    @Mock private WxAccountMapper accountMapper;
    @Mock private AccountService accountService;

    private RegCodeService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxRegCodeEntity.class, WxAccountEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RegCodeService(regCodeMapper, accountMapper, accountService);
    }

    // ---------------------------------------------------------------- 签发

    @Test
    void issueDetailedReturnsPlaintextOnceAndStoresOnlyHash() {
        ArgumentCaptor<WxRegCodeEntity> insert = ArgumentCaptor.forClass(WxRegCodeEntity.class);
        given(regCodeMapper.insert(insert.capture())).willReturn(1);

        OffsetDateTime before = OffsetDateTime.now();
        RegCodeIssueVO vo = service.issueDetailed(null, "  ", null);
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(vo.code()).startsWith("wxreg_").as("明文码前缀");
        assertThat(vo.code()).hasSize(49).as("wxreg_ + 32 字节 base64url(43 位)");
        assertThat(insert.getValue().getCodeHash()).isEqualTo(sha256(vo.code())).as("库只存 sha256");
        assertThat(insert.getValue().getCodeHash()).isNotEqualTo(vo.code()).as("明文绝不落库");
        assertThat(insert.getValue().getAccount()).as("空白预绑定归一为 null").isNull();
        assertThat(insert.getValue().getLabel()).isNull();
        assertThat(insert.getValue().getIsDeleted()).isZero();
        assertThat(insert.getValue().getExpiresAt())
                .isAfter(before.plusHours(23)).isBefore(after.plusHours(25)).as("expires 缺省 24h");
    }

    @Test
    void issueDetailedHonorsCustomExpiryAndPreBinding() {
        ArgumentCaptor<WxRegCodeEntity> insert = ArgumentCaptor.forClass(WxRegCodeEntity.class);
        given(regCodeMapper.insert(insert.capture())).willReturn(1);

        OffsetDateTime before = OffsetDateTime.now();
        service.issueDetailed("发给客户端A", ACCOUNT, 48);
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(insert.getValue().getLabel()).isEqualTo("发给客户端A");
        assertThat(insert.getValue().getAccount()).isEqualTo(ACCOUNT);
        assertThat(insert.getValue().getExpiresAt())
                .isAfter(before.plusHours(47)).isBefore(after.plusHours(49)).as("自传 48h 生效");
    }

    @Test
    void issueKeepsLegacyPlaintextContract() {
        given(regCodeMapper.insert(any(WxRegCodeEntity.class))).willReturn(1);
        String code = service.issue("l", null, null);
        assertThat(code).startsWith("wxreg_").hasSize(49).as("issue = issueDetailed 的明文口径");
    }

    @Test
    void hashCollisionRegeneratesDifferentCode() {
        ArgumentCaptor<WxRegCodeEntity> insert = ArgumentCaptor.forClass(WxRegCodeEntity.class);
        // mock 回填 id（模拟真库 ASSIGN_ID）：不回填则 vo.id() 恒 null；77 首枚撞 uk，78 重生成成功
        long[] seq = {77};
        given(regCodeMapper.insert(insert.capture())).willAnswer(inv -> {
            ((WxRegCodeEntity) inv.getArgument(0)).setId(seq[0]++);
            if (seq[0] == 78) {
                throw new DuplicateKeyException("uk_wx_reg_code_hash");
            }
            return 1;
        });

        RegCodeIssueVO vo = service.issueDetailed(null, null, null);

        assertThat(insert.getAllValues()).hasSize(2).as("撞哈希后重生成一次");
        assertThat(insert.getAllValues().get(0).getCodeHash())
                .isNotEqualTo(insert.getAllValues().get(1).getCodeHash()).as("两枚哈希不同");
        assertThat(vo.id()).as("返回成功那枚的行 id（重生成的那枚）").isEqualTo(78L);
    }

    @Test
    void hashCollisionExhaustsRetryBudget() {
        given(regCodeMapper.insert(any(WxRegCodeEntity.class)))
                .willThrow(new DuplicateKeyException("uk_wx_reg_code_hash"));

        assertThatThrownBy(() -> service.issueDetailed(null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("连续冲突");
        verify(regCodeMapper, times(5)).insert(any(WxRegCodeEntity.class)); // 防御性上限 5 次
    }

    // ---------------------------------------------------------------- 列表 / 作废

    @Test
    void listMapsAuditViewWithoutPlaintext() {
        WxRegCodeEntity row = used(codeRow(301L, null), "host-a");
        Page<WxRegCodeEntity> page = new Page<>(2, 20);
        page.setRecords(List.of(row, codeRow(302L, ACCOUNT)));
        page.setTotal(41);
        willReturn(page).given(regCodeMapper).selectPage(any(), any());

        PageVO<RegCodeVO> vo = service.list(2, 20);

        assertThat(vo.total()).isEqualTo(41);
        assertThat(vo.page()).isEqualTo(2);
        assertThat(vo.pageSize()).isEqualTo(20);
        assertThat(vo.hasMore()).as("2×20=40 < 41").isTrue();
        assertThat(vo.list()).hasSize(2);
        assertThat(vo.list().get(0).id()).isEqualTo(301L);
        assertThat(vo.list().get(0).usedBy()).isEqualTo("host-a");
        assertThat(vo.list().get(1).preBoundAccount()).isEqualTo(ACCOUNT);
        assertThat(Arrays.stream(RegCodeVO.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("code").as("审计视图不含明文字段");
    }

    @Test
    void revokeThrowsNotFoundWhenRowMissing() {
        given(regCodeMapper.selectById(99L)).willReturn(null);

        assertThatThrownBy(() -> service.revoke(99L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(regCodeMapper, never()).update(any(), any());
    }

    @Test
    void revokeIsIdempotentForAlreadyDeletedRow() {
        WxRegCodeEntity deleted = codeRow(301L, null);
        deleted.setIsDeleted(1);
        given(regCodeMapper.selectById(301L)).willReturn(deleted);

        service.revoke(301L);

        // 已是软删态直接返回：不再发 update
        verify(regCodeMapper, never()).update(any(), any());
    }

    @Test
    void revokeSoftDeletesActiveRow() {
        given(regCodeMapper.selectById(301L)).willReturn(codeRow(301L, null));
        given(regCodeMapper.update(any(), any())).willReturn(1);

        service.revoke(301L);

        ArgumentCaptor<LambdaUpdateWrapper<WxRegCodeEntity>> wrapper =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(regCodeMapper).update(isNull(), wrapper.capture());
        assertThat(wrapper.getValue().getEntity()).as("set 型 wrapper，不带实体").isNull();
    }

    // ---------------------------------------------------------------- 自助注册

    @Test
    void registerRejectsInvalidAccountFormatBeforeAnyLookup() {
        RegisterRequest bad = request("bad account");

        assertThatThrownBy(() -> service.registerWithCode(bad))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10102));
        verify(regCodeMapper, never()).selectOne(any()); // 格式闸门在码查询之前
    }

    @Test
    void registerRejectsUnknownCode() {
        given(regCodeMapper.selectOne(any())).willReturn(null);

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10400));
        verify(accountService, never()).register(any()); // 码不过则不建号
    }

    @Test
    void registerRejectsUsedCode() {
        given(regCodeMapper.selectOne(any())).willReturn(used(codeRow(301L, null), HOST));

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    @Test
    void registerRejectsExpiredCode() {
        WxRegCodeEntity expired = codeRow(301L, null);
        expired.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        given(regCodeMapper.selectOne(any())).willReturn(expired);

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    @Test
    void registerRejectsPreBindMismatch() {
        given(regCodeMapper.selectOne(any())).willReturn(codeRow(301L, "wxid_t9test_other"));

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
    }

    @Test
    void registerPropagatesUniqueConflictFromAccountService() {
        given(regCodeMapper.selectOne(any())).willReturn(codeRow(301L, null));
        given(accountService.register(any()))
                .willThrow(new ApiException(10401, "账号已注册: " + ACCOUNT));

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10401));
        verify(accountMapper, never()).update(any(), any()); // 建号失败即止，不打点不消费
        verify(regCodeMapper, never()).update(any(), any());
    }

    @Test
    void registerSuccessCreatesAccountAndConsumesCode() {
        given(regCodeMapper.selectOne(any())).willReturn(codeRow(301L, null));
        given(accountMapper.update(any(), any())).willReturn(1);
        given(regCodeMapper.update(any(), any())).willReturn(1);

        String registered = service.registerWithCode(request(ACCOUNT));

        assertThat(registered).isEqualTo(ACCOUNT);
        verify(accountService).register(new AccountRegisterRequest(ACCOUNT, WXID)); // 建号走统一口径
        verify(accountMapper).update(isNull(), any()); // hostname/client_version 打点
        verify(regCodeMapper).update(isNull(), any()); // used_at/used_by 条件消费
    }

    @Test
    void registerFailsWhenAccountRowNotStamped() {
        given(regCodeMapper.selectOne(any())).willReturn(codeRow(301L, null));
        given(accountMapper.update(any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402));
        verify(regCodeMapper, never()).update(any(), any()); // 账号行变更异常则不消费码
    }

    @Test
    void registerFailsWhenConsumeLosesRace() {
        given(regCodeMapper.selectOne(any())).willReturn(codeRow(301L, null));
        given(accountMapper.update(any(), any())).willReturn(1);
        given(regCodeMapper.update(any(), any())).willReturn(0);

        assertThatThrownBy(() -> service.registerWithCode(request(ACCOUNT)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10402))
                .as("并发双消费防线：条件 UPDATE 0 行 → 整体回滚");
    }

    // ---------------------------------------------------------------- 夹具

    private RegisterRequest request(String account) {
        return new RegisterRequest("wxreg_t9", account, WXID, HOST, "4.2.0.11");
    }

    private WxRegCodeEntity codeRow(Long id, String preBound) {
        WxRegCodeEntity row = new WxRegCodeEntity();
        row.setId(id);
        row.setCodeHash(sha256("wxreg_" + id));
        row.setAccount(preBound);
        row.setExpiresAt(OffsetDateTime.now().plusHours(24));
        row.setIsDeleted(0);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }

    private WxRegCodeEntity used(WxRegCodeEntity row, String host) {
        row.setUsedAt(OffsetDateTime.now());
        row.setUsedBy(host);
        return row;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
