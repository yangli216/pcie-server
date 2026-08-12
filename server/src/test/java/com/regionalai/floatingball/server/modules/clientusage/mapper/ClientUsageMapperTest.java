package com.regionalai.floatingball.server.modules.clientusage.mapper;

import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientUsageMapperTest {

    @Test
    void mapperShouldKeepProviderContract() throws Exception {
        assertTrue(ClientUsageMapper.class.isAnnotationPresent(Mapper.class));
        Method countMethod = ClientUsageMapper.class.getMethod("countClientUsage", ClientUsageQueryDTO.class);
        assertEquals("query", countMethod.getParameters()[0].getAnnotation(Param.class).value());
        SelectProvider provider = countMethod.getAnnotation(SelectProvider.class);
        assertEquals(ClientUsageSqlProvider.class, provider.type());
        assertEquals("countClientUsage", provider.method());

        Method queryMethod = ClientUsageMapper.class.getMethod(
            "queryClientUsage",
            ClientUsageQueryDTO.class,
            long.class,
            long.class
        );
        assertEquals("query", queryMethod.getParameters()[0].getAnnotation(Param.class).value());
        assertEquals("offset", queryMethod.getParameters()[1].getAnnotation(Param.class).value());
        assertEquals("endRow", queryMethod.getParameters()[2].getAnnotation(Param.class).value());
        provider = queryMethod.getAnnotation(SelectProvider.class);
        assertEquals(ClientUsageSqlProvider.class, provider.type());
        assertEquals("queryClientUsage", provider.method());
    }

    @Test
    void providerShouldAggregateDoctorsByCurrentVersionInteractionFacts() {
        String sql = new ClientUsageSqlProvider().queryClientUsage();

        assertTrue(sql.contains("JOIN c_ai_org"));
        assertTrue(sql.contains("o.fg_active = '1' AND o.sd_status = '1'"));
        assertTrue(sql.contains("PARTITION BY e.id_org, e.id_his_org, e.cd_doctor"));
        assertTrue(sql.contains("ORDER BY e.event_time DESC, e.id_event DESC"));
        assertTrue(sql.contains("current_usage.usage_rank = 1"));
        assertTrue(sql.contains("version_usage.client_version = current_usage.client_version"));
        assertTrue(sql.contains("SELECT MIN(version_usage.event_time)"));
        assertTrue(sql.contains("LENGTH(TRIM(e.cd_doctor)) &gt; 0"));
        assertTrue(sql.contains("LENGTH(TRIM(e.client_version)) &gt; 0"));
        assertTrue(sql.contains("query.keyword"));
        assertTrue(sql.contains("ORDER BY lastActiveTime DESC, doctorWorkNo ASC, usageOrgId ASC"));
        assertTrue(sql.contains("COALESCE(usageHisOrgId, '') ASC"));
        assertTrue(sql.contains("page_row_num &gt; #{offset}"));
        assertTrue(sql.contains("page_row_num &lt;= #{endRow}"));
        assertTrue(sql.contains("ORDER BY page_row_num"));
    }

    @Test
    void providerShouldCountWithExactlyTheSameFilteredUsageCte() {
        String countSql = new ClientUsageSqlProvider().countClientUsage();

        assertTrue(countSql.contains("WITH ranked_events AS"));
        assertTrue(countSql.contains("filtered_usage AS"));
        assertTrue(countSql.contains("query.keyword"));
        assertTrue(countSql.contains("SELECT COUNT(*) FROM filtered_usage"));
    }
}
