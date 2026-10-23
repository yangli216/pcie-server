package com.regionalai.floatingball.server.modules.analytics.mapper;

import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.db.DatabaseDialectHolder;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsMapperTest {

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.Kind.class, names = {"OPENGAUSS", "POSTGRESQL", "GBASE_POSTGRESQL"})
    void finalizedJsonReadShouldKeepAllScopeAndPeriodFiltersWithoutCasting(DatabaseDialect.Kind kind) {
        DatabaseDialectHolder.set(new DatabaseDialect(kind));
        String sql = new AnalyticsSqlProvider().queryFinalizedChangeSummaries();
        assertTrue(sql.contains("SELECT ucl.change_summary_json FROM c_ai_user_consultation_log"));
        assertTrue(sql.contains("ucl.status IN ('completed','abandoned')"));
        assertTrue(sql.contains("ucl.fg_active = '1'"));
        assertTrue(sql.contains("o.fg_active = '1' AND o.sd_status = '1'"));
        assertTrue(sql.contains("r.fg_active = '1' AND r.sd_status = '1'"));
        for (String parameter : new String[]{"idOrg", "idRegion", "hisOrgId", "dateFromTime", "dateToExclusiveTime"}) {
            assertTrue(sql.contains("#{query." + parameter + "}"), parameter);
        }
        assertFalse(sql.contains("::json"));
        assertFalse(sql.contains("JSON_VALUE"));
        assertFalse(sql.contains("SUBSTR"));
    }

    @AfterEach
    void tearDown() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
    }

    @Test
    void mapperShouldKeepMyBatisProviderAnnotations() throws Exception {
        assertTrue(AnalyticsMapper.class.isAnnotationPresent(Mapper.class));

        Method countAiService = AnalyticsMapper.class.getMethod("countAiService", AnalyticsQueryDTO.class);
        assertHasQueryParam(countAiService);
        assertProvider(countAiService, "countAiService");

        Method functionRanking = AnalyticsMapper.class.getMethod("queryFunctionUsageRanking", FunctionUsageQueryDTO.class);
        assertHasQueryParam(functionRanking);
        assertProvider(functionRanking, "queryFunctionUsageRanking");

        Method functionTrend = AnalyticsMapper.class.getMethod("queryFunctionUsageTrend", FunctionUsageQueryDTO.class);
        assertHasQueryParam(functionTrend);
        assertProvider(functionTrend, "queryFunctionUsageTrend");

        Method hisOrgOptions = AnalyticsMapper.class.getMethod("queryHisOrgOptions");
        assertProvider(hisOrgOptions, "queryHisOrgOptions");

        Method consultationOrgDistribution = AnalyticsMapper.class.getMethod("queryConsultationOrgDistribution", AnalyticsQueryDTO.class);
        assertHasQueryParam(consultationOrgDistribution);
        assertProvider(consultationOrgDistribution, "queryConsultationOrgDistribution");

        Method consultationRegionDistribution = AnalyticsMapper.class.getMethod("queryConsultationRegionDistributionRaw", AnalyticsQueryDTO.class);
        assertHasQueryParam(consultationRegionDistribution);
        assertProvider(consultationRegionDistribution, "queryConsultationRegionDistributionRaw");
    }

    @Test
    void oracleProviderShouldKeepOracleSpecificExpressions() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
        AnalyticsSqlProvider provider = new AnalyticsSqlProvider();

        String trend = provider.queryFunctionUsageTrend();
        assertTrue(trend.contains("TO_CHAR(TRUNC(e.event_time)"));
        assertTrue(trend.contains("AS moduleName"));
        assertTrue(trend.contains("dayStr"));

        String distinctModules = provider.queryDistinctModules();
        assertTrue(distinctModules.contains("FROM dual"));
        assertTrue(distinctModules.contains("AI推荐治疗方案"));

        String diagnosis = provider.countDiagnosisMatchedConsultations();
        assertTrue(diagnosis.contains("JSON_VALUE"));
        assertTrue(diagnosis.contains("$.diagnosisChanges"));

        String ranking = provider.queryFunctionUsageRanking();
        assertTrue(ranking.contains("NVL(TRIM(e.id_doctor), e.id_device)"));
        assertFalse(ranking.contains("NULLIF(TRIM(e.id_doctor)"));

        String consultationOrgDistribution = provider.queryConsultationOrgDistribution();
        assertTrue(consultationOrgDistribution.contains("NVL(MAX(ucl.na_org)"));
        String consultationRegionDistribution = provider.queryConsultationRegionDistributionRaw();
        assertTrue(consultationRegionDistribution.contains("SUM(NVL(l.cnt, 0))"));
    }

    @Test
    void gaussdbProviderShouldGeneratePgCompatibleSql() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.OPENGAUSS));
        AnalyticsSqlProvider provider = new AnalyticsSqlProvider();

        String trend = provider.queryFunctionUsageTrend();
        assertTrue(trend.contains("TO_CHAR(e.event_time::date"));
        assertTrue(trend.contains("AS moduleName"));
        assertFalse(trend.contains("TRUNC("));
        assertFalse(trend.contains("TO_DATE("));

        String distinctModules = provider.queryDistinctModules();
        assertTrue(distinctModules.contains("VALUES"));
        assertFalse(distinctModules.contains("FROM dual"));

        String diagnosis = provider.countDiagnosisMatchedConsultations();
        assertTrue(diagnosis.contains("ucl.change_summary_json::jsonb ->> 'diagnosisChanges'"));
        assertFalse(diagnosis.contains("JSON_VALUE"));

        String ranking = provider.queryFunctionUsageRanking();
        assertTrue(ranking.contains("COALESCE(NULLIF(TRIM(e.id_doctor), ''), e.id_device)"));
        assertFalse(ranking.contains("COALESCE(TRIM(e.id_doctor), e.id_device)"));

        String consultationOrgDistribution = provider.queryConsultationOrgDistribution();
        assertTrue(consultationOrgDistribution.contains("COALESCE(MAX(ucl.na_org)"));
        assertFalse(consultationOrgDistribution.contains("NVL("));
        String consultationRegionDistribution = provider.queryConsultationRegionDistributionRaw();
        assertTrue(consultationRegionDistribution.contains("SUM(COALESCE(l.cnt, 0))"));
        assertFalse(consultationRegionDistribution.contains("NVL("));
    }

    @Test
    void analyticsSqlShouldJoinEnabledRegionAndOrgForScopeFilters() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
        AnalyticsSqlProvider provider = new AnalyticsSqlProvider();

        assertEnabledScopeJoin(provider.countAiService());
        assertEnabledScopeJoin(provider.countConsultation());
        assertEnabledScopeJoin(provider.queryFunctionUsageRanking());
    }

    @Test
    void analyticsSqlShouldUseIndependentHisOrganizationFacts() {
        DatabaseDialectHolder.set(new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
        AnalyticsSqlProvider provider = new AnalyticsSqlProvider();

        assertTrue(provider.countAiService().contains("e.id_his_org = #{query.hisOrgId}"));
        assertTrue(provider.countConsultation().contains("ucl.id_his_org = #{query.hisOrgId}"));
        assertTrue(provider.queryFunctionUsageRanking().contains("e.id_his_org = #{query.hisOrgId}"));
        assertTrue(provider.queryOrgDistribution().contains("GROUP BY e.id_his_org"));
        assertTrue(provider.queryConsultationOrgDistribution().contains("GROUP BY ucl.id_his_org"));
        assertTrue(provider.queryConsultationOrgDistribution().contains("ucl.id_his_org = #{query.hisOrgId}"));
        assertTrue(provider.queryConsultationRegionDistributionRaw().contains("c_ai_user_consultation_log"));
        assertTrue(provider.queryConsultationRegionDistributionRaw().contains("ucl.consultation_time"));

        String options = provider.queryHisOrgOptions();
        assertTrue(options.contains("c_ai_feature_event"));
        assertTrue(options.contains("c_ai_user_consultation_log"));
        assertTrue(options.contains("e.id_his_org IS NOT NULL"));
        assertFalse(options.contains("c_ai_op_log"));
    }

    private void assertHasQueryParam(Method method) {
        Parameter parameter = method.getParameters()[0];
        Param annotation = parameter.getAnnotation(Param.class);
        assertEquals("query", annotation.value());
    }

    private void assertProvider(Method method, String providerMethod) {
        SelectProvider provider = method.getAnnotation(SelectProvider.class);
        assertEquals(AnalyticsSqlProvider.class, provider.type());
        assertEquals(providerMethod, provider.method());
    }

    private void assertEnabledScopeJoin(String sql) {
        assertTrue(sql.contains("JOIN c_ai_org"));
        assertTrue(sql.contains("JOIN c_ai_region"));
        assertTrue(sql.contains("o.sd_status = '1'"));
        assertTrue(sql.contains("r.sd_status = '1'"));
        assertTrue(sql.contains("o.id_region = #{query.idRegion}"));
    }
}
