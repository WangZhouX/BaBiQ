package com.wzx.babiq.server.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * BaBiQ 本地持久化配置。
 *
 * <p>P2 使用 SQLite 作为本地单用户数据库。本配置集中保存数据库文件路径和
 * SQLite PRAGMA 选项，避免 DataSource、migration 测试和后续 repository
 * 分别写一套默认值。</p>
 */
@ConfigurationProperties(prefix = "babiq.persistence")
public class BaBiQPersistenceProperties {

    /**
     * SQLite 数据库文件路径。
     *
     * <p>默认写入用户目录下的 `.babiq/babiq.db`；测试可以覆盖到 target/test-db，
     * 桌面端真实运行时也可以通过 application.yml 指向其他位置。</p>
     */
    private Path databasePath = Path.of(System.getProperty("user.home"), ".babiq", "babiq.db");

    /**
     * SQLite 遇到短暂写锁时等待的毫秒数。
     *
     * <p>桌面端通常是单用户单进程，但 WebSocket worker、历史查询和设置保存可能并发访问，
     * busy timeout 能避免瞬时锁竞争直接变成业务失败。</p>
     */
    private int busyTimeoutMillis = 5000;

    /**
     * 是否启用 WAL 日志模式。
     *
     * <p>WAL 让读写并发体验更平滑，适合本地桌面应用；测试也会验证该 PRAGMA 已被应用。</p>
     */
    private boolean enableWal = true;

    /**
     * 是否对每个 SQLite 连接启用外键约束。
     *
     * <p>SQLite 外键默认关闭，而且是连接级设置；因此 DataSource 每次取连接后都要重新应用。</p>
     */
    private boolean enableForeignKeys = true;

    public Path databasePath() {
        return databasePath;
    }

    public void setDatabasePath(Path databasePath) {
        this.databasePath = databasePath;
    }

    public int busyTimeoutMillis() {
        return busyTimeoutMillis;
    }

    public void setBusyTimeoutMillis(int busyTimeoutMillis) {
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    public boolean enableWal() {
        return enableWal;
    }

    public void setEnableWal(boolean enableWal) {
        this.enableWal = enableWal;
    }

    public boolean enableForeignKeys() {
        return enableForeignKeys;
    }

    public void setEnableForeignKeys(boolean enableForeignKeys) {
        this.enableForeignKeys = enableForeignKeys;
    }
}
