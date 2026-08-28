package com.regionalai.floatingball.server.modules.useractivity.mapper;

import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivityQueryDTO;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserActivityMapperTest {

    @Test
    void mapperShouldKeepMyBatisProviderAnnotations() throws Exception {
        assertTrue(UserActivityMapper.class.isAnnotationPresent(Mapper.class));

        Method countActiveDoctors = UserActivityMapper.class.getMethod("countActiveDoctors", UserActivityQueryDTO.class);
        assertHasQueryParam(countActiveDoctors);
        assertProvider(countActiveDoctors, "countActiveDoctors");

        Method queryDoctorActivityList = UserActivityMapper.class.getMethod("queryDoctorActivityList", UserActivityQueryDTO.class);
        assertHasQueryParam(queryDoctorActivityList);
        assertProvider(queryDoctorActivityList, "queryDoctorActivityList");
    }

    @Test
    void activeAndTotalCountsShouldUseDistinctDoctorIdentity() {
        UserActivitySqlProvider provider = new UserActivitySqlProvider();
        String activeSql = provider.countActiveDoctors();
        String totalSql = provider.countTotalDoctors();

        assertEnabledConsultationScope(activeSql);
        assertTrue(activeSql.contains("COUNT(DISTINCT ucl.id_doctor)"));
        assertTrue(activeSql.contains("ucl.id_doctor IS NOT NULL"));
        assertTrue(activeSql.contains("query.dateFromTime"));
        assertTrue(activeSql.contains("query.dateToExclusiveTime"));
        assertTrue(activeSql.contains("ucl.id_his_org = #{query.hisOrgId}"));
        assertFalse(activeSql.contains("c_ai_device"));

        assertEnabledConsultationScope(totalSql);
        assertTrue(totalSql.contains("COUNT(DISTINCT ucl.id_doctor)"));
        assertFalse(totalSql.contains("query.dateFromTime"));
        assertFalse(totalSql.contains("query.dateToExclusiveTime"));
    }

    @Test
    void doctorActivityListShouldMergeDevicesAndApplyPeriodToActivityFacts() {
        String sql = new UserActivitySqlProvider().queryDoctorActivityList();

        assertEnabledConsultationScope(sql);
        assertTrue(sql.contains("ucl.id_doctor AS idDoctor"));
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY ucl.id_doctor"));
        assertTrue(sql.contains("COUNT(DISTINCT facts.idDevice) AS deviceCount"));
        assertTrue(sql.contains("GROUP BY facts.idDoctor"));
        assertTrue(sql.contains("HAVING SUM("));
        assertTrue(sql.contains("query.activeStatus == \"active\""));
        assertTrue(sql.contains("query.activeStatus == \"inactive\""));
        assertTrue(sql.contains("query.dateFromTime"));
        assertTrue(sql.contains("query.dateToExclusiveTime"));
        assertTrue(sql.contains(
            "ORDER BY consultationCount DESC, effectiveConsultationCount DESC, "
                + "lastActiveTime DESC NULLS LAST, idDoctor ASC"
        ));
        assertFalse(sql.contains("GROUP BY ucl.id_device"));
    }

    @Test
    void regionCountShouldCountDistinctDoctors() {
        String sql = new UserActivitySqlProvider().countActiveDoctorsByRegion();

        assertEnabledConsultationScope(sql);
        assertTrue(sql.contains("COUNT(DISTINCT ucl.id_doctor)"));
        assertTrue(sql.contains("GROUP BY o.id_region"));
    }

    @Test
    void doctorActivityListShouldRenderValidDynamicSqlForStatusFilter() {
        UserActivityQueryDTO query = new UserActivityQueryDTO();
        query.setDateFromTime(LocalDateTime.of(2026, 8, 1, 0, 0));
        query.setDateToExclusiveTime(LocalDateTime.of(2026, 9, 1, 0, 0));
        query.setHisOrgId("HIS-ORG-001");
        query.setActiveStatus("active");

        String sql = renderSql(new UserActivitySqlProvider().queryDoctorActivityList(), query);

        assertTrue(sql.contains("GROUP BY facts.idDoctor HAVING SUM"));
        assertTrue(sql.contains("facts.consultation_time >= ?"));
        assertTrue(sql.contains("facts.consultation_time < ?"));
        assertTrue(sql.contains("ucl.id_his_org = ?"));
        assertTrue(sql.contains("> 0 ORDER BY"));
        assertFalse(sql.contains("= 0 ORDER BY"));

        query.setActiveStatus("inactive");
        String inactiveSql = renderSql(new UserActivitySqlProvider().queryDoctorActivityList(), query);
        assertTrue(inactiveSql.contains("= 0 ORDER BY"));
        assertFalse(inactiveSql.contains("> 0 ORDER BY"));
    }

    private void assertHasQueryParam(Method method) {
        Parameter parameter = method.getParameters()[0];
        Param annotation = parameter.getAnnotation(Param.class);
        assertEquals("query", annotation.value());
    }

    private void assertProvider(Method method, String providerMethod) {
        SelectProvider provider = method.getAnnotation(SelectProvider.class);
        assertEquals(UserActivitySqlProvider.class, provider.type());
        assertEquals(providerMethod, provider.method());
    }

    private void assertEnabledConsultationScope(String sql) {
        assertTrue(sql.contains("FROM c_ai_user_consultation_log ucl"));
        assertTrue(sql.contains("JOIN c_ai_org"));
        assertTrue(sql.contains("JOIN c_ai_region"));
        assertTrue(sql.contains("o.sd_status = '1'"));
        assertTrue(sql.contains("r.sd_status = '1'"));
        assertTrue(sql.contains("o.id_region = #{query.idRegion}"));
    }

    private String renderSql(String script, UserActivityQueryDTO query) {
        Configuration configuration = new Configuration();
        SqlSource source = new XMLLanguageDriver().createSqlSource(configuration, script, Map.class);
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("query", query);
        BoundSql boundSql = source.getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
