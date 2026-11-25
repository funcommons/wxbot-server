package com.wechat.sync.server.task.scheduler;

import com.wechat.sync.server.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;

/**
 * 任务分发调度接线（TASK_DISPATCH §4.3）：全工程首个 @Scheduled。
 *
 * <p>{@code @EnableScheduling} 挂在本配置类（task 模块自包含，不污染启动类）。节拍用
 * fixedDelay 而非 fixedRate：上一轮还没结束时绝不并发下一轮（Spring 默认调度线程池 1 线程，
 * 两个任务串行共享，30s/60s 节拍远低于执行耗时，无饥饿风险）；initialDelay 错开启动高峰，
 * 也给框架/连接池预热留时间。
 *
 * <p>catch-all 的原因：回收/物化失败是可恢复常态（PG 抖动、锁等待、网络分区），按 warn 记录
 * 让「本轮失败下轮重试」——这与任务重派的幂等语义（§8：ingest 天然幂等，重跑无害）一致，
 * 不升级为告警；若不 catch，异常虽也被 Spring 吞掉继续下一轮，但每次都走框架 error 级日志。
 *
 * <p>多实例并发（§8）：materialize 的 next_materialize_at 推进带「旧值一致」乐观锁
 * （TaskService#pushNextMaterializeAt），sweep 逐行 FOR UPDATE（selectExpiredForUpdate）行级互斥
 * ——多实例部署正确性不依赖单实例假设（最多浪费一轮重复扫描），不引入 ShedLock。
 *
 * <p>测试上下文关闭后台调度（{@code wx-sync.scheduling.enabled=false}）：@SpringBootTest 会拉起
 * 完整应用，调度线程随之存活——200ms/30s 的拨轮会与集成测试抢同一批「PENDING + 到期」行
 * （dispatcher 抢认领使 attempts 多加、物化抢跑），表现为只在全量套件里出现的随机失败。
 * 生产不设该属性即为启用。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "wx-sync.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@RequiredArgsConstructor
public class TaskSchedulerConfig {

    private final TaskService taskService;

    /** 回收巡检：过期租约打回 PENDING/DEAD + run 日志 LOST（§4.3，30s 一轮，启动 15s 后首跑）。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void sweepExpiredLeases() {
        try {
            int recycled = taskService.sweepExpiredLeases();
            if (recycled > 0) {
                log.info("[TaskScheduler] 回收过期租约 count={}", recycled);
            }
        } catch (Exception e) {
            log.warn("[TaskScheduler] 回收巡检失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /** 计划物化：due 计划 → wx_task（撞活动唯一键 DO NOTHING 防堆积，§8，60s 一轮，启动 30s 后首跑）。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    public void materializeDueSchedules() {
        try {
            int produced = taskService.materializeDueSchedules(OffsetDateTime.now());
            if (produced > 0) {
                log.info("[TaskScheduler] 物化到期计划 produced={}", produced);
            }
        } catch (Exception e) {
            log.warn("[TaskScheduler] 物化失败（下一轮重试）: {}", e.getMessage(), e);
        }
    }
}
