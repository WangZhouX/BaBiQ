package com.wzx.babiq.server;

import com.wzx.babiq.server.application.auth.BusinessDirectDevelopmentSessionBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * BaBiQ 后端服务启动入口。
 *
 * <p>该类只负责启动 Spring Boot 应用上下文,协议、对话和 provider 等业务能力
 * 都放在各自包内,避免启动入口承载业务逻辑。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.wzx.babiq.server")
@EnableScheduling
public class BaBiQApplication {

    /**
     * 启动 BaBiQ 后端服务。
     *
     * @param args JVM 启动参数
     */
    public static void main(String[] args) {
        if (BusinessDirectDevelopmentSessionBootstrap.isDirectDevelopment(args)) {
            System.setProperty("spring.devtools.restart.enabled", "false");
            System.setProperty("spring.devtools.livereload.enabled", "false");
        }
        BusinessDirectDevelopmentSessionBootstrap.PreparedSession directSession =
                BusinessDirectDevelopmentSessionBootstrap.prepareIfRequested(args, System.getenv());
        if (directSession != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(directSession::close,
                    "business-direct-development-session-cleanup"));
        }
        try {
            SpringApplication.run(BaBiQApplication.class, args);
        } catch (RuntimeException | Error failure) {
            if (directSession != null) {
                directSession.close();
            }
            throw failure;
        }
    }

}
