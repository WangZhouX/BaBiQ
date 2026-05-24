package com.wzx.babiq.server.persistence;

import com.wzx.babiq.server.persistence.config.BaBiQPersistenceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-1 持久化配置测试。
 *
 * <p>这些测试先锁住 SQLite 默认路径、WAL、外键和 busy timeout 的约定，
 * 避免后续实现 DataSource 时把桌面本地数据库的基础行为写散。</p>
 */
class MyBatisPlusConfigTest {

    /** ApplicationContextRunner 只启动最小配置上下文，比完整 SpringBootTest 更适合测试属性绑定。 */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PersistencePropertiesTestConfig.class);

    @Test
    @DisplayName("持久化配置默认指向用户目录下的 .babiq/babiq.db")
    void properties_should_use_user_home_database_by_default() {
        contextRunner.run(context -> {
            BaBiQPersistenceProperties properties = context.getBean(BaBiQPersistenceProperties.class);

            assertThat(properties.databasePath().toString())
                    .endsWith(Path.of(".babiq", "babiq.db").toString());
            assertThat(properties.busyTimeoutMillis()).isEqualTo(5000);
            assertThat(properties.enableWal()).isTrue();
            assertThat(properties.enableForeignKeys()).isTrue();
        });
    }

    @Test
    @DisplayName("持久化配置允许测试或用户显式覆盖数据库路径和 PRAGMA 选项")
    void properties_should_bind_explicit_values() {
        contextRunner
                .withPropertyValues(
                        "babiq.persistence.database-path=target/test-db/override.db",
                        "babiq.persistence.busy-timeout-millis=1234",
                        "babiq.persistence.enable-wal=false",
                        "babiq.persistence.enable-foreign-keys=false")
                .run(context -> {
                    BaBiQPersistenceProperties properties = context.getBean(BaBiQPersistenceProperties.class);

                    assertThat(properties.databasePath().toString()).endsWith(Path.of("target", "test-db", "override.db").toString());
                    assertThat(properties.busyTimeoutMillis()).isEqualTo(1234);
                    assertThat(properties.enableWal()).isFalse();
                    assertThat(properties.enableForeignKeys()).isFalse();
                });
    }

    /**
     * 最小属性绑定配置，只让 ConfigurationProperties 进入上下文。
     */
    @EnableConfigurationProperties(BaBiQPersistenceProperties.class)
    static class PersistencePropertiesTestConfig {
    }
}
