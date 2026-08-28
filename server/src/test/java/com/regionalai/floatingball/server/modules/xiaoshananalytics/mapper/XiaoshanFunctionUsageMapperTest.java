package com.regionalai.floatingball.server.modules.xiaoshananalytics.mapper;

import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.db.DatabaseDialectHolder;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XiaoshanFunctionUsageMapperTest {

    @AfterEach
    void tearDown() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
    }

    @Test
    void mapperShouldUseDedicatedSqlProvider() throws Exception {
        assertTrue(XiaoshanFunctionUsageMapper.class.isAnnotationPresent(Mapper.class));
        assertProvider("queryRanking", "queryRanking");
        assertProvider("queryPreviousRanking", "queryPreviousRanking");
        assertProvider("queryTrend", "queryTrend");
    }

    @Test
    void oracleSqlShouldUnionOnlyFiveXiaoshanBusinessFunctions() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
        XiaoshanFunctionUsageSqlProvider provider = new XiaoshanFunctionUsageSqlProvider();

        String ranking = provider.queryRanking();
        assertTrue(ranking.contains("c_ai_feature_event"));
        assertTrue(ranking.contains("c_ai_user_consultation_log"));
        assertTrue(ranking.contains("e.feature_code = 'chat'"));
        assertTrue(ranking.contains("ucl.consultation_type = 'voice' THEN '语音问诊'"));
        assertTrue(ranking.contains("'chronic_refill' THEN '慢病配药'"));
        assertTrue(ranking.contains("'report_consultation' THEN '报告回诊'"));
        assertTrue(ranking.contains("'report_interpretation' THEN '报告解读'"));
        assertTrue(ranking.contains("'chat' THEN '医学助手'"));
        assertTrue(ranking.contains(
            "ucl.consultation_type IN ('voice', 'chronic_refill', 'report_consultation', 'report_interpretation')"
        ));
        assertFalse(ranking.contains("e.feature_code = 'voice_consultation'"));
        assertFalse(ranking.contains("e.feature_code = 'report_interpretation'"));
        assertTrue(ranking.contains("NVL(TRIM(e.id_doctor), e.id_device)"));
        assertTrue(ranking.contains("NVL(TRIM(ucl.id_doctor), ucl.id_device)"));
        assertTrue(ranking.contains("usage_data.id_his_org = #{query.hisOrgId}"));
        assertTrue(ranking.contains("usage_data.module_name IN"));
        assertFalse(ranking.contains("智能问诊"));
        assertFalse(ranking.contains("AI推荐诊断"));
        assertFalse(ranking.contains("c_ai_op_log"));

        String trend = provider.queryTrend();
        assertTrue(trend.contains("TO_CHAR(TRUNC(usage_data.usage_time)"));
        assertTrue(trend.contains("AS dayStr"));
    }

    @Test
    void gaussdbSqlShouldUsePgCompatibleDoctorAndDayExpressions() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.OPENGAUSS));
        XiaoshanFunctionUsageSqlProvider provider = new XiaoshanFunctionUsageSqlProvider();

        String ranking = provider.queryRanking();
        assertTrue(ranking.contains("COALESCE(NULLIF(TRIM(e.id_doctor), ''), e.id_device)"));
        assertTrue(ranking.contains("COALESCE(NULLIF(TRIM(ucl.id_doctor), ''), ucl.id_device)"));
        assertFalse(ranking.contains("NVL("));

        String trend = provider.queryTrend();
        assertTrue(trend.contains("TO_CHAR(usage_data.usage_time::date"));
        assertFalse(trend.contains("TRUNC("));
    }

    private void assertProvider(String methodName, String providerMethod) throws Exception {
        Method method = XiaoshanFunctionUsageMapper.class.getMethod(methodName, FunctionUsageQueryDTO.class);
        SelectProvider provider = method.getAnnotation(SelectProvider.class);
        assertEquals(XiaoshanFunctionUsageSqlProvider.class, provider.type());
        assertEquals(providerMethod, provider.method());
        assertEquals("query", method.getParameters()[0].getAnnotation(Param.class).value());
    }
}
