package com.wzx.babiq.server.persistence.config;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接初始化器。
 *
 * <p>SQLite 的部分关键行为是“连接级”的，例如外键开关和 busy timeout。
 * 因此 BaBiQ 不只在启动时执行一次 PRAGMA，而是在 DataSource 每次取出连接后
 * 都调用本类，确保 mapper、Flyway、测试和未来查询服务拿到的连接行为一致。</p>
 */
@Component
public class SQLiteConnectionInitializer {

    /** 当前应用绑定的 SQLite 配置，决定是否开启外键、WAL 和锁等待时间。 */
    private final BaBiQPersistenceProperties properties;

    /**
     * 创建连接初始化器。
     *
     * @param properties 本地持久化配置
     */
    public SQLiteConnectionInitializer(BaBiQPersistenceProperties properties) {
        this.properties = properties;
    }

    /**
     * 对一个新取出的 SQLite 连接应用 PRAGMA。
     *
     * @param connection 刚从 DataSource 获取的连接
     * @throws SQLException PRAGMA 执行失败时抛出，让调用方明确知道数据库初始化失败
     */
    public void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (properties.enableForeignKeys()) {
                // SQLite 外键默认关闭，而且按连接生效；不显式开启会让 migration 外键形同虚设。
                statement.execute("PRAGMA foreign_keys = ON");
            }
            if (properties.enableWal()) {
                // WAL 适合桌面本地数据库：读历史记录时不容易被短写事务阻塞。
                statement.execute("PRAGMA journal_mode = WAL");
            }
            // busy_timeout 能把毫秒级写锁竞争转成等待，而不是直接抛 SQLITE_BUSY。
            statement.execute("PRAGMA busy_timeout = " + Math.max(0, properties.busyTimeoutMillis()));
        }
    }
}
