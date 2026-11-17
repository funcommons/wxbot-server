package com.wechat.sync.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * wechat-sync-server 启动类。
 *
 * <p>Mapper 扫描显式声明（T2）：各模块 mapper 统一挂在 {@code **.mapper} 包下。
 */
@SpringBootApplication
@MapperScan("com.wechat.sync.server.**.mapper")
public class WxSyncServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WxSyncServerApplication.class, args);
    }
}
