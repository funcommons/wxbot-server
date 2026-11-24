package com.wechat.sync.server.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * admin SPA 根路径（/admin、/admin/）转发：ResourceHttpRequestHandler 对空
 * resourcePath 直接 404（spring-web 空路径守卫），轮不到 AdminUiConfig 的
 * SPA fallback——显式转发到 index.html，URL 保持 /admin/ 不变。
 */
@Controller
public class AdminUiRootController {

    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "forward:/admin/index.html";
    }
}
