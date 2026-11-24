package com.wechat.sync.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * admin SPA（Vue 3 + Fc SDK，{@code admin-ui/} 构建）静态托管：{@code /admin/**} →
 * {@code classpath:/static/admin/}。History 路由深链（如 {@code /admin/tokens}）没有
 * 对应物理文件——PathResourceResolver 回落 {@code index.html}，由前端路由接管
 * （admin-ui 规范：HTML5 History 模式禁 hash）。/v1/** 与 swagger 不受影响。
 */
@Configuration
public class AdminUiConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/static/admin/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        return requested.exists() && requested.isReadable()
                                ? requested
                                : location.createRelative("index.html");
                    }
                });
    }
}
