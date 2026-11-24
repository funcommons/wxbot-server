package com.wechat.sync.server.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 静态 admin token 过滤器：SPA 登录后每个请求带 {@code Authorization: Bearer <静态串>}，
 * 本过滤器精确命中静态串时把请求头透明改写为真实 admin JWT（下游框架 accesstoken
 * 拦截器照常校验，无感知）。脚本/浏览器持真实 JWT 的请求不受影响（字符串不相等）。
 *
 * <p>仅改写 {@code Authorization}；其余请求头原样透传。登录校验端点（无鉴权）走
 * 静态串也安全——命中后改写为 JWT，路径未注册则照常 404。
 */
@Configuration
@RequiredArgsConstructor
public class AdminUiStaticTokenFilter {

    private final AdminUiStaticTokenService staticTokenService;

    @Bean
    public FilterRegistrationBean<Filter> staticTokenAuthFilter() {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter((request, response, chain) -> {
            HttpServletRequest req = (HttpServletRequest) request;
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")
                    && staticTokenService.matches(auth.substring(7).trim())) {
                chain.doFilter(new StaticTokenRequest(req, staticTokenService.resolveJwt()), response);
                return;
            }
            chain.doFilter(request, response);
        });
        reg.addUrlPatterns("/v1/*");
        reg.setOrder(Integer.MIN_VALUE + 100); // 尽早执行（先于一切业务过滤器）
        reg.setName("staticTokenAuthFilter");
        return reg;
    }

    /** 只覆写 Authorization 头的不可变请求包装 */
    static final class StaticTokenRequest extends HttpServletRequestWrapper {
        private final String bearer;

        StaticTokenRequest(HttpServletRequest delegate, String jwt) {
            super(delegate);
            this.bearer = "Bearer " + jwt;
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) return bearer;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) return Collections.enumeration(List.of(bearer));
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return super.getHeaderNames();
        }
    }
}
