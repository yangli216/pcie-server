package com.regionalai.floatingball.server.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.DialectFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MybatisPlusConfigTest {
    @Test
    void shouldCreateInterceptorForGaussdbProfile() {
        MybatisPlusDatabaseProperties properties = new MybatisPlusDatabaseProperties();
        properties.setDbType("opengauss");
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig(properties).mybatisPlusInterceptor();
        assertNotNull(interceptor);
    }

    @ParameterizedTest
    @CsvSource({"gbase-postgresql,POSTGRE_SQL", "GBASE-POSTGRESQL,POSTGRE_SQL",
        "postgresql,POSTGRE_SQL", "gaussdb,OPENGAUSS", "opengauss,OPENGAUSS", "oracle,ORACLE"})
    void configuredDatabaseShouldSelectMatchingPagination(String configured, DbType expected) {
        MybatisPlusDatabaseProperties properties = new MybatisPlusDatabaseProperties();
        properties.setDbType(" " + configured + " ");
        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor)
            new MybatisPlusConfig(properties).mybatisPlusInterceptor().getInterceptors().get(0);

        assertEquals(expected, pagination.getDbType());
        assertEquals(Long.valueOf(100L), pagination.getMaxLimit());
        if (configured.toLowerCase(java.util.Locale.ROOT).startsWith("gbase-")) {
            String sql = DialectFactory.getDialect(pagination.getDbType())
                .buildPaginationSql("SELECT id_org FROM c_ai_org", 20, 10)
                .getDialectSql();
            assertTrue(sql.contains("LIMIT ? OFFSET ?"), sql);
        }
    }
}
