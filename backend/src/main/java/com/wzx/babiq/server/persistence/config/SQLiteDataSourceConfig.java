package com.wzx.babiq.server.persistence.config;

import org.sqlite.SQLiteDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SQLite DataSource 配置。
 *
 * <p>BaBiQ 是本地桌面 Agent，P2 阶段使用单文件 SQLite 数据库。这里负责把
 * `babiq.persistence.database-path` 转成 JDBC URL，并在启动时创建父目录。</p>
 */
@Configuration
@EnableConfigurationProperties(BaBiQPersistenceProperties.class)
public class SQLiteDataSourceConfig {

    /**
     * 创建带 PRAGMA 初始化能力的 SQLite DataSource。
     *
     * @param properties 持久化配置
     * @param initializer SQLite 连接初始化器
     * @return Spring Boot、Flyway 和 MyBatis-Plus 共用的 DataSource
     */
    @Bean
    public DataSource dataSource(BaBiQPersistenceProperties properties, SQLiteConnectionInitializer initializer) {
        Path databasePath = properties.databasePath().toAbsolutePath().normalize();
        ensureParentDirectory(databasePath);

        SQLiteDataSource delegate = new SQLiteDataSource();
        delegate.setUrl("jdbc:sqlite:" + databasePath);
        return new SQLitePragmaDataSource(delegate, initializer);
    }

    private void ensureParentDirectory(Path databasePath) {
        Path parent = databasePath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("创建 SQLite 数据库目录失败: " + parent, exception);
        }
    }
}
