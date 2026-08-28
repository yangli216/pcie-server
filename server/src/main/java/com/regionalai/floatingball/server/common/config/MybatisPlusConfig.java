package com.regionalai.floatingball.server.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class MybatisPlusConfig {

    private final MybatisPlusDatabaseProperties databaseProperties;

    public MybatisPlusConfig(MybatisPlusDatabaseProperties databaseProperties) {
        this.databaseProperties = databaseProperties;
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(resolveDbType());
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    private DbType resolveDbType() {
        String configured = databaseProperties.getDbType();
        if (!StringUtils.hasText(configured)) {
            return DbType.ORACLE;
        }
        String normalized = configured.trim().toLowerCase();
        if ("gauss".equals(normalized) || "gaussdb".equals(normalized) || "opengauss".equals(normalized)) {
            return DbType.OPENGAUSS;
        }
        if ("postgres".equals(normalized) || "postgresql".equals(normalized) || "postgre_sql".equals(normalized)) {
            return DbType.POSTGRE_SQL;
        }
        if ("oracle".equals(normalized) || "oracle12c".equals(normalized) || "oracle_12c".equals(normalized)) {
            return DbType.ORACLE;
        }
        DbType dbType = DbType.getDbType(configured);
        return dbType == null || dbType == DbType.OTHER ? DbType.ORACLE : dbType;
    }
}
