package com.wzx.babiq.server.persistence.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>P2 使用 MyBatis-Plus 作为数据库访问层，但 Agent 领域层不能直接依赖 Mapper。
 * Mapper 只扫描 `persistence.mapper` 包，领域层通过 repository adapter 间接使用。</p>
 */
@Configuration
@MapperScan("com.wzx.babiq.server.persistence.mapper")
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 分页插件。
     *
     * @return 包含 SQLite 分页方言的 MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 单数据库项目显式指定 DbType.SQLITE，避免插件通过 JDBC 元数据猜错分页方言。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.SQLITE));
        return interceptor;
    }
}
