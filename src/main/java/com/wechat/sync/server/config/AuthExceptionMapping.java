package com.wechat.sync.server.config;

import fun.commons.framework4j.accesstoken.exception.AuthException;
import fun.commons.framework4j.api.ApiCode;
import fun.commons.framework4j.web.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AuthException → mc-api-spec 102xx 认证信封的翻译接线（P1 修复，见 PROGRESS T7 P1 条目）。
 *
 * <p><b>根因</b>：framework4j-accesstoken 的 {@code TokenInterceptor.preHandle} 在无令牌 / 令牌失效 /
 * 令牌格式错 / 被踢下线 / 角色不符时抛 {@link AuthException}（自带 int code，缺省 10200，
 * 见其 {@code DEFAULT_CODE} 与 TokenUtils / AccessTokenValidationStrategy / RoleAuthorizer 的全部抛点），
 * 而框架 {@code GlobalExceptionHandler}（framework4j-web）只注册了
 * ApiException / 参数校验 / 路由层 / {@code Exception.class} 兜底等映射，<b>没有 AuthException 条目</b>
 * （grep 全 framework4j 源码仅 3 个 @ControllerAdvice：WebAutoConfiguration 注册的
 * GlobalExceptionHandler、DataAccessExceptionAdvice，均不含 AuthException）→ 落进
 * {@code handleException(Exception)} 兜底 → HTTP 500 + 信封 10001「系统繁忙」。
 * 客户端从此无法区分「没带令牌」与「服务端故障」。
 *
 * <p><b>为什么直接返回 {@link ApiResponse} 而不是抛 {@code ApiException(e.getCode(), message)}</b>：
 * 异常处理器内抛出的<b>新</b>异常不会被再次路由 —— Spring 6.2.19
 * {@code ExceptionHandlerExceptionResolver.doResolveHandlerMethodException} 的
 * {@code catch (Throwable invocationEx)} 分支记一条 warn 后 {@code return null}
 * （L473-484，源码已核），原异常继续外抛，结局仍是 500。框架自家两个 advice
 * （GlobalExceptionHandler / DataAccessExceptionAdvice）也都直接返回 ApiResponse，惯例一致。
 *
 * <p><b>为什么必须显式 {@code @Order}</b>：Spring 跨 advice 的选择规则是「advice 按
 * AnnotationAwareOrderComparator 排序后，<b>第一个含匹配方法的 advice 胜出</b>」（不做全局最具体匹配，
 * framework4j-web v1.7.1 Issue #24 在 DataAccessExceptionAdvice 上已为此踩坑并注释）。
 * {@code GlobalExceptionHandler} 自带 {@code @ExceptionHandler(Exception.class)}，对本异常必然"匹配"，
 * 其 order 是 {@code LOWEST_PRECEDENCE}；本类取 {@code HIGHEST_PRECEDENCE + 100}
 * （与 DataAccessExceptionAdvice 同款），保证先于兜底被选中。
 *
 * <p><b>码透传表（mc-api-spec v1.6 §7.6/§7.7 = ApiCode 枚举）</b>——AuthException 自带码，原样透传：
 * 10200 未登录（未提供令牌）/ 10201 令牌已过期或会话超时 / 10202 令牌签名验证失败 /
 * 10205 账号在别处登录被踢 / 10207 令牌格式或元数据解析失败 / 10208 令牌已注销 /
 * 10210 refresh 已过期 / 10211 refresh 无效或已使用（重用检测）/ 10212 轮转次数超限 /
 * 10300 令牌类型与端点要求不匹配（角色不符也归此）。仅防御性兜底：code ≤ 0（理论上框架不会产生）
 * 折到 10202 TOKEN_INVALID，绝不落到 10xxx 系统段。
 *
 * <p><b>HTTP 状态保持 200</b>（mc-api-spec 铁律 1：业务/认证错误不走 4xx/5xx，错误信息在信封 code）。
 *
 * @since 0.1.0
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RestControllerAdvice
public class AuthExceptionMapping {

    /** code ≤ 0 时的防御性兜底码（框架抛点恒为正，此分支理论上不可达）。 */
    private static final int FALLBACK_CODE = ApiCode.TOKEN_INVALID.getCode();

    /**
     * 把拦截器抛出的 {@link AuthException} 翻译成 102xx 认证信封（HTTP 200）。
     */
    @ExceptionHandler(AuthException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleAuthException(AuthException e) {
        int code = e.getCode() > 0 ? e.getCode() : FALLBACK_CODE;
        String message = (e.getMessage() == null || e.getMessage().isBlank())
                ? defaultMessage(code)
                : e.getMessage();
        log.warn("[认证拦截] code={}, message={}", code, message);
        return ApiResponse.fail(code, message);
    }

    /** message 空白时回退 ApiCode 标准文案；未知码（106xx 等业务自定义段不会出现，防漂移兜底）给通用文案。 */
    private static String defaultMessage(int code) {
        ApiCode apiCode = ApiCode.fromCode(code);
        return apiCode != null ? apiCode.getMessage() : "认证失败，请重新登录";
    }
}
