package com.wechat.sync.server.subscription.scheduler;

import com.wechat.sync.server.subscription.mapper.WxSubscriptionDeliveryMapper;
import com.wechat.sync.server.subscription.service.DeliveryService;
import com.wechat.sync.server.subscription.service.SubscriptionBackstopService;
import com.wechat.sync.server.subscription.service.SubscriptionMatcher;
import com.wechat.sync.server.subscription.webhook.WebhookDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.BDDMockito.given;

/**
 * SubscriptionSchedulerConfig 单测（SUBSCRIPTION_DESIGN §8 拨轮参数 + catch-all 纪律，先例
 * TaskSchedulerConfig）。纯反射 + mock，零 Spring 上下文、零 DB。
 *
 * <p>落盘 API 对齐记录：包 {@code subscription.scheduler}；ctor 注 <b>五依赖</b>
 * {@code (WebhookDispatcher, DeliveryService, SubscriptionBackstopService, SubscriptionMatcher,
 * WxSubscriptionDeliveryMapper)}（dispatch 拨轮 → {@code dispatcher.dispatchOnce()}，不经
 * DeliveryService.dispatchDue）；{@code cleanupDelivered} 直接挂 mapper；{@code retainedDays}/{@code
 * cleanupBatch} 是 {@code @Value} 字段——纯 {@code new} 时为 0，测试用 ReflectionTestUtils 置
 * 契约值 30/5000。五个 @Scheduled 拨轮：dispatch 200ms / sweep 30000 / 补扫 60000 / 快照刷新 30000 /
 * 清理 60000（initialDelay 不在 §8 契约内，不强断避免假锚）。
 *
 * <p>委托断言用 {@code mockingDetails} 按方法名扫描（对重载鲁棒）；fail 路径统一吞异常
 * （拨轮抛异常会杀 @Scheduled 调度线程——catch-all 是任务域 T9 已验证的纪律）。
 */
class SubscriptionSchedulerConfigTest {

    private final WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
    private final DeliveryService deliveryService = mock(DeliveryService.class);
    private final SubscriptionBackstopService backstopService = mock(SubscriptionBackstopService.class);
    private final SubscriptionMatcher matcher = mock(SubscriptionMatcher.class);
    private final WxSubscriptionDeliveryMapper deliveryMapper = mock(WxSubscriptionDeliveryMapper.class);

    private final SubscriptionSchedulerConfig config = new SubscriptionSchedulerConfig(
            dispatcher, deliveryService, backstopService, matcher, deliveryMapper);

    @BeforeEach
    void seedContractCleanupValues() {
        // @Value 字段纯构造时为 0——测试置契约缺省（yml 同值），否则清理窗口断言拿到 0 天
        ReflectionTestUtils.setField(config, "retainedDays", 30);
        ReflectionTestUtils.setField(config, "cleanupBatch", 5000);
    }

    private List<Method> scheduledMethods() {
        return Arrays.stream(SubscriptionSchedulerConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();
    }

    /** 反射调一个拨轮方法（private 也放行），RuntimeException 透传（供 catch-all 断言）。 */
    private void invoke(Method method) throws Exception {
        method.setAccessible(true);
        try {
            method.invoke(config);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ D. 五个拨轮 + §8 参数

    @Test
    void fiveScheduledWheelsWithContractDelays() {
        List<Method> methods = scheduledMethods();
        assertThat(methods).as("五个 @Scheduled 拨轮（dispatch/sweep/backstop/snapshot/cleanup）").hasSize(5);

        List<Long> delays = methods.stream()
                .map(m -> m.getAnnotation(Scheduled.class).fixedDelay())
                .sorted()
                .toList();
        assertThat(delays).as("§8 拨轮参数：dispatch 200ms，sweep/清理 30s×2 与 补扫/快照 60s/30s 档")
                .containsExactly(200L, 30000L, 30000L, 60000L, 60000L);
    }

    // ------------------------------------------------------------------ D. 委托与清理参数

    @Test
    void everyWheelDelegatesToItsDependency() throws Exception {
        given(deliveryService.sweepExpired()).willReturn(0);
        given(backstopService.runBackstop()).willReturn(0);
        given(backstopService.runBackstop(anyInt())).willReturn(0);

        for (Method method : scheduledMethods()) {
            invoke(method);
        }

        assertThat(invoked("dispatchOnce", dispatcher)).as("dispatch 拨轮 → WebhookDispatcher.dispatchOnce").isTrue();
        assertThat(invoked("sweepExpired", deliveryService)).as("回收拨轮 → sweepExpired").isTrue();
        assertThat(invoked("runBackstop", backstopService)).as("补扫拨轮 → runBackstop").isTrue();
        assertThat(invoked("refresh", matcher)).as("快照拨轮 → SubscriptionMatcher.refresh").isTrue();
        assertThat(invoked("cleanupDelivered", deliveryMapper)).as("清理拨轮 → mapper.cleanupDelivered").isTrue();
    }

    @Test
    void cleanupUsesThirtyDayWindowAndContractBatch() throws Exception {
        given(deliveryService.sweepExpired()).willReturn(0);
        given(backstopService.runBackstop()).willReturn(0);
        given(backstopService.runBackstop(anyInt())).willReturn(0);

        for (Method method : scheduledMethods()) {
            invoke(method);
        }

        OffsetDateTime before = null;
        int cap = -1;
        for (var invocation : mockingDetails(deliveryMapper).getInvocations()) {
            if (invocation.getMethod().getName().equals("cleanupDelivered")) {
                before = invocation.getArgument(0);
                cap = invocation.getArgument(1);
            }
        }
        assertThat(before).as("清理窗口起点（captured）").isNotNull();
        OffsetDateTime expected = OffsetDateTime.now().minusDays(30);
        assertThat(Math.abs(before.toEpochSecond() - expected.toEpochSecond()))
                .as("窗口 = now-30d（±120s 容差，§8 保留 30 天；@Value 字段已置 30）").isLessThanOrEqualTo(120);
        assertThat(cap).as("每轮批删上限 5000 行（§8；@Value 字段已置 5000）").isEqualTo(5000);
    }

    // ------------------------------------------------------------------ D. catch-all（拨轮抛异常不杀调度线程）

    @Test
    void wheelsSwallowDependencyFailures() throws Exception {
        doThrow(new RuntimeException("dispatch broke")).when(dispatcher).dispatchOnce();
        given(deliveryService.sweepExpired()).willThrow(new RuntimeException("db down"));
        given(backstopService.runBackstop()).willThrow(new RuntimeException("db down"));
        given(backstopService.runBackstop(anyInt())).willThrow(new RuntimeException("db down"));
        doThrow(new IllegalStateException("matcher broken")).when(matcher).refresh();
        given(deliveryMapper.cleanupDelivered(any(), anyInt())).willThrow(new RuntimeException("db down"));

        for (Method method : scheduledMethods()) {
            assertThatCode(() -> invoke(method))
                    .as("拨轮 %s 必须 catch-all（吞异常保调度线程，T9 纪律）", method.getName())
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ 助手

    private boolean invoked(String methodName, Object mock) {
        return mockingDetails(mock).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals(methodName));
    }
}
