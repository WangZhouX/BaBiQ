package com.wzx.babiq.server.persistence.config;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 会自动应用 SQLite PRAGMA 的 DataSource 包装器。
 *
 * <p>xerial SQLiteDataSource 负责真正创建 JDBC 连接，本类只在连接返回给上层前
 * 统一调用 {@link SQLiteConnectionInitializer}。这样 MyBatis、Flyway 和测试拿到的
 * 连接都具备相同的外键、WAL 和 busy timeout 行为。</p>
 */
final class SQLitePragmaDataSource implements DataSource {

    /** 真正负责创建 SQLite JDBC 连接的 DataSource。 */
    private final DataSource delegate;
    /** 每次连接创建后要执行的 SQLite PRAGMA 初始化器。 */
    private final SQLiteConnectionInitializer initializer;

    SQLitePragmaDataSource(DataSource delegate, SQLiteConnectionInitializer initializer) {
        this.delegate = delegate;
        this.initializer = initializer;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = delegate.getConnection();
        initializer.initialize(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = delegate.getConnection(username, password);
        initializer.initialize(connection);
        return connection;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
