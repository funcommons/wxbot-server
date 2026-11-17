package com.wechat.sync.server.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wechat.sync.server.account.service.TokenService;
import com.wechat.sync.server.common.PageVO;
import com.wechat.sync.server.task.dto.TaskAdminRequests.ScheduleCreateRequest;
import com.wechat.sync.server.task.dto.TaskAdminRequests.ScheduleUpdateRequest;
import com.wechat.sync.server.task.dto.TaskAdminRequests.TaskCreateRequest;
import com.wechat.sync.server.task.dto.TaskAdminVOs.ScheduleVO;
import com.wechat.sync.server.task.dto.TaskAdminVOs.TaskRunVO;
import com.wechat.sync.server.task.dto.TaskAdminVOs.TaskVO;
import com.wechat.sync.server.task.entity.WxTaskEntity;
import com.wechat.sync.server.task.entity.WxTaskRunEntity;
import com.wechat.sync.server.task.mapper.WxTaskMapper;
import com.wechat.sync.server.task.mapper.WxTaskRunMapper;
import com.wechat.sync.server.task.service.TaskService;
import fun.commons.framework4j.accesstoken.annotation.RequiresToken;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiException;
import fun.commons.framework4j.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 任务/计划/历史管理接口（TASK_DISPATCH §6，角色 admin）：手工建任务、取消/死信重置、
 * 计划管理、任务与派发历史查询。
 *
 * <p>写操作一律走 {@link TaskService}（状态机与事务在 service）；只读列表直注 mapper 做
 * selectPage（BlobController 先例：controller→mapper 只读查询可接受，不为此扩 service 面）。
 * 不做 admin 之外的账号归属校验（admin 令牌 claims 无 account，角色校验由注解承担）。
 *
 * <p>无类级 @RequestMapping：本控制器同时服务 /v1/tasks、/v1/task-runs、/v1/task-schedules
 * 三个前缀（与 client 控制器的 /v1/tasks/** 方法级互斥，无映射冲突）。返回 id 的端点直接回
 * Long（框架全局 Long→String 序列化，mc-api-spec ID String 口径）。
 */
@RestController
@RequiredArgsConstructor
@RequiresToken(value = TokenService.ADMIN_TYPE, roles = TokenService.ROLE_ADMIN)
public class TaskAdminController {

    /**
     * status 过滤值全域。FAILED 当前无写入方（fail 非重试直落 DEAD，§3 v1.1 口径），
     * 保留为过滤值兼容 DDL 注释域与历史数据。
     */
    private static final Set<String> STATUS_FILTERS = Set.of(
            TaskService.ST_PENDING, TaskService.ST_DISPATCHED, TaskService.ST_RUNNING,
            TaskService.ST_SUCCEEDED, "FAILED", TaskService.ST_DEAD, TaskService.ST_CANCELLED);

    private final TaskService taskService;
    private final WxTaskMapper taskMapper;
    private final WxTaskRunMapper taskRunMapper;

    // ------------------------------------------------------------ /v1/tasks

    /** 手工建临时任务（schedule_id=NULL）；同键活动任务已存在 → 10402；max_attempts 越界 → 10102；返回新任务 id。 */
    @PostMapping("/v1/tasks")
    public ApiResponse<Long> create(@Valid @RequestBody TaskCreateRequest request) {
        return ApiResponse.success(taskService.createTask(request.account(), request.taskType(),
                request.target(), request.priority(), request.payload(), request.maxAttempts()));
    }

    /** 取消：PENDING/DISPATCHED → CANCELLED（保留 lease_id，迟到回报按 10603）；其它状态 → 10402。 */
    @PostMapping("/v1/tasks/{task_id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable("task_id") String taskId) {
        taskService.cancelTask(TaskIdParser.parse(taskId, "task_id"));
        return ApiResponse.success();
    }

    /** 死信重置：DEAD → PENDING（attempts 归零清租约，§10）；其它状态 → 10402。 */
    @PostMapping("/v1/tasks/{task_id}/reset")
    public ApiResponse<Void> reset(@PathVariable("task_id") String taskId) {
        taskService.resetDeadTask(TaskIdParser.parse(taskId, "task_id"));
        return ApiResponse.success();
    }

    /**
     * 任务列表（Offset 分页，§6）：可按 status / account 过滤；id 降序（新的在前）。
     * status 非法值 → 10102（防拼错静默空列表）。
     */
    @GetMapping("/v1/tasks")
    public ApiResponse<PageVO<TaskVO>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String account) {
        PageVO.PageParams params = PageVO.clamp(page, pageSize);
        boolean filterStatus = status != null && !status.isBlank();
        if (filterStatus && !STATUS_FILTERS.contains(status)) {
            throw new ApiException(ApiCode.PARAM_FORMAT_ERROR, "未知 status: " + status);
        }
        Page<WxTaskEntity> result = taskMapper.selectPage(Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxTaskEntity>()
                        .eq(filterStatus, WxTaskEntity::getStatus, status)
                        .eq(account != null && !account.isBlank(), WxTaskEntity::getAccount, account)
                        .orderByDesc(WxTaskEntity::getId));
        return ApiResponse.success(PageVO.map(result.getRecords(), result.getTotal(),
                params.page(), params.pageSize(), TaskVO::from));
    }

    // ------------------------------------------------------------ /v1/task-runs

    /** 派发执行历史（§2.3 append-only 日志）：可按 task_id / account 过滤，id 降序。 */
    @GetMapping("/v1/task-runs")
    public ApiResponse<PageVO<TaskRunVO>> listRuns(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize,
                                                   @RequestParam(name = "task_id", required = false) String taskId,
                                                   @RequestParam(required = false) String account) {
        PageVO.PageParams params = PageVO.clamp(page, pageSize);
        Long filterTaskId = TaskIdParser.parseOptional(taskId, "task_id");
        Page<WxTaskRunEntity> result = taskRunMapper.selectPage(Page.of(params.page(), params.pageSize()),
                new LambdaQueryWrapper<WxTaskRunEntity>()
                        .eq(filterTaskId != null, WxTaskRunEntity::getTaskId, filterTaskId)
                        .eq(account != null && !account.isBlank(), WxTaskRunEntity::getAccount, account)
                        .orderByDesc(WxTaskRunEntity::getId));
        return ApiResponse.success(PageVO.map(result.getRecords(), result.getTotal(),
                params.page(), params.pageSize(), TaskRunVO::from));
    }

    // ------------------------------------------------------------ /v1/task-schedules

    /** 建计划（§2.4）：interval/daily 二选一；可调度计划强制 next_materialize_at = now()。返回计划 id。 */
    @PostMapping("/v1/task-schedules")
    public ApiResponse<Long> createSchedule(@Valid @RequestBody ScheduleCreateRequest request) {
        return ApiResponse.success(taskService.createSchedule(request.account(), request.taskType(),
                request.target(), request.intervalSeconds(), request.dailyAt(), request.priority(),
                request.payload(), request.activityWindowDays(), request.enabled()));
    }

    /**
     * 计划列表：配置表量级小，service 已 LIMIT 200 全量返回（不分页——§6 的 page 参数在此
     * 无意义，javadoc 明示；数据量涨到需要分页时再扩 service）。
     */
    @GetMapping("/v1/task-schedules")
    public ApiResponse<List<ScheduleVO>> listSchedules() {
        return ApiResponse.success(taskService.listSchedules().stream().map(ScheduleVO::from).toList());
    }

    /** 计划详情（§6 GET /v1/task-schedules/{id}）：不存在/软删 → 10400。 */
    @GetMapping("/v1/task-schedules/{task_schedule_id}")
    public ApiResponse<ScheduleVO> scheduleDetail(@PathVariable("task_schedule_id") String id) {
        return ApiResponse.success(ScheduleVO.from(taskService.scheduleDetail(TaskIdParser.parse(id, "task_schedule_id"))));
    }

    /**
     * 改计划：可改字段 enabled/interval_seconds/daily_at/priority/payload/activity_window_days
     * （target/account/task_type 不可改——改语义等价删了重建，避免物化中的计划漂移）。
     * 校验次序 path-first（评审定夺）：先解析路径 id（非法 → 10102），再判 body
     * （缺失或全部可更新字段皆 null → 10101 拒绝：空体 PUT 会把计划字段整体抹空/
     * 退化为无意义调用，统一挡掉，service 不被调——资源先于载荷）。
     */
    @PutMapping("/v1/task-schedules/{id}")
    public ApiResponse<Void> updateSchedule(@PathVariable("id") String id,
                                            @Valid @RequestBody(required = false) ScheduleUpdateRequest request) {
        long scheduleId = TaskIdParser.parse(id, "id");   // path-first：非法 id 先于空体判定（10102 → 10101）
        if (request == null || (request.enabled() == null && request.intervalSeconds() == null
                && request.dailyAt() == null && request.priority() == null
                && request.payload() == null && request.activityWindowDays() == null)) {
            throw new ApiException(ApiCode.PARAM_MISSING, "至少提供一个可更新字段");
        }
        taskService.updateSchedule(scheduleId, request.enabled(), request.intervalSeconds(),
                request.dailyAt(), request.priority(), request.payload(), request.activityWindowDays());
        return ApiResponse.success();
    }
}
