package com.regionalai.floatingball.server.modules.analytics.mapper;

import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.db.DatabaseDialectHolder;

public class AnalyticsSqlProvider {

    public static final String FUNCTION_USAGE_MODULE_EXPR = "CASE "
        + "WHEN e.feature_code = 'voice_consultation' THEN '语音问诊' "
        + "WHEN e.feature_code = 'smart_consultation' THEN '智能问诊' "
        + "WHEN e.feature_code = 'report_interpretation' THEN '报告单解读' "
        + "WHEN e.feature_code = 'chat' THEN '聊天' "
        + "WHEN e.feature_code = 'diagnosis_checklist' THEN 'AI诊断鉴别' "
        + "WHEN e.feature_code = 'diagnosis_recommendation' THEN 'AI推荐诊断' "
        + "WHEN e.feature_code = 'medication_recommendation' THEN 'AI推荐用药' "
        + "WHEN e.feature_code = 'examination_recommendation' THEN 'AI推荐检查' "
        + "WHEN e.feature_code = 'lab_test_recommendation' THEN 'AI推荐检验' "
        + "WHEN e.feature_code = 'procedure_recommendation' THEN 'AI推荐处置' "
        + "WHEN e.feature_code = 'treatment_plan_recommendation' THEN 'AI推荐治疗方案' "
        + "WHEN e.feature_code = 'knowledge_usage' THEN '知识库使用' "
        + "WHEN e.feature_name = 'AI诊疗方案推荐' THEN 'AI推荐治疗方案' "
        + "ELSE e.feature_name END";

    private static final String FEATURE_SCOPE_JOIN = "JOIN c_ai_org o ON o.id_org = e.id_org AND o.fg_active = '1' AND o.sd_status = '1'";
    private static final String FEATURE_REGION_JOIN = "JOIN c_ai_region r ON r.id_region = o.id_region AND r.fg_active = '1' AND r.sd_status = '1'";
    private static final String CONSULTATION_SCOPE_JOIN = "JOIN c_ai_org o ON o.id_org = ucl.id_org AND o.fg_active = '1' AND o.sd_status = '1'";
    private static final String CONSULTATION_REGION_JOIN = "JOIN c_ai_region r ON r.id_region = o.id_region AND r.fg_active = '1' AND r.sd_status = '1'";

    public String countAiService() {
        DatabaseDialect dialect = dialect();
        return featureBaseCountSql("COUNT(1) AS cnt");
    }

    public String countConsultation() {
        DatabaseDialect dialect = dialect();
        return consultationBaseCountSql("COUNT(1) AS cnt", null);
    }

    public String countActiveDoctors() {
        DatabaseDialect dialect = dialect();
        return consultationBaseCountSql("COUNT(DISTINCT ucl.id_doctor) AS cnt", null);
    }

    public String countAdoptedConsultations() {
        DatabaseDialect dialect = dialect();
        return consultationBaseCountSql("COUNT(1) AS cnt", "AND ucl.status = 'completed'");
    }

    public String countFinalizedConsultations() {
        DatabaseDialect dialect = dialect();
        return consultationBaseCountSql("COUNT(1) AS cnt", "AND ucl.status IN ('completed','abandoned')");
    }

    public String countDiagnosisMatchedConsultations() {
        DatabaseDialect dialect = dialect();
        return consultationBaseCountSql(
            "COUNT(1) AS cnt",
            "AND ucl.status IN ('completed','abandoned') AND " + dialect.jsonNumber("ucl.change_summary_json", "$.diagnosisChanges") + " = 0"
        );
    }

    public String queryFinalizedChangeSummaries() {
        return consultationBaseCountSql("ucl.change_summary_json", "AND ucl.status IN ('completed','abandoned')");
    }

    public String queryAiServiceTrend() {
        DatabaseDialect dialect = dialect();
        String day = dialect.dayText("e.event_time");
        return "<script>"
            + "SELECT " + day + " AS day_str, COUNT(1) AS cnt "
            + "FROM c_ai_feature_event e "
            + FEATURE_SCOPE_JOIN + " "
            + FEATURE_REGION_JOIN + " "
            + "WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + featureFilters()
            + " GROUP BY " + day
            + " ORDER BY " + day
            + "</script>";
    }

    public String queryConsultationTrend() {
        DatabaseDialect dialect = dialect();
        String day = dialect.dayText("ucl.consultation_time");
        return "<script>"
            + "SELECT " + day + " AS day_str, COUNT(1) AS cnt "
            + "FROM c_ai_user_consultation_log ucl "
            + CONSULTATION_SCOPE_JOIN + " "
            + CONSULTATION_REGION_JOIN + " "
            + "WHERE ucl.fg_active = '1' "
            + consultationFilters()
            + " GROUP BY " + day
            + " ORDER BY " + day
            + "</script>";
    }

    public String queryOrgDistribution() {
        DatabaseDialect dialect = dialect();
        return "<script>"
            + "SELECT " + dialect.nvl("MAX(e.na_his_org)", dialect.nvl("e.id_his_org", "'未上报HIS机构'")) + " AS name, "
            + "COUNT(1) AS value "
            + "FROM c_ai_feature_event e "
            + FEATURE_SCOPE_JOIN + " "
            + FEATURE_REGION_JOIN + " "
            + "WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + featureFilters()
            + " GROUP BY e.id_his_org"
            + " ORDER BY value DESC"
            + "</script>";
    }

    public String queryHisOrgOptions() {
        return "SELECT his_org_id AS hisOrgId, MAX(his_org_name) AS hisOrgName FROM ("
            + "SELECT e.id_his_org AS his_org_id, e.na_his_org AS his_org_name "
            + "FROM c_ai_feature_event e "
            + FEATURE_SCOPE_JOIN + " "
            + FEATURE_REGION_JOIN + " "
            + "WHERE e.fg_active = '1' AND e.id_his_org IS NOT NULL "
            + "UNION ALL "
            + "SELECT ucl.id_his_org AS his_org_id, ucl.na_org AS his_org_name "
            + "FROM c_ai_user_consultation_log ucl "
            + CONSULTATION_SCOPE_JOIN + " "
            + CONSULTATION_REGION_JOIN + " "
            + "WHERE ucl.fg_active = '1' AND ucl.id_his_org IS NOT NULL"
            + ") his_orgs GROUP BY his_org_id ORDER BY hisOrgName, hisOrgId";
    }

    public String queryRegionDistributionRaw() {
        DatabaseDialect dialect = dialect();
        return "<script>"
            + "SELECT r.na_region AS name, SUM(" + dialect.nvl("l.cnt", "0") + ") AS value "
            + "FROM c_ai_region r "
            + "LEFT JOIN c_ai_org o ON o.id_region = r.id_region AND o.fg_active = '1' AND o.sd_status = '1' "
            + "LEFT JOIN ("
            + "  SELECT e.id_org, COUNT(1) AS cnt FROM c_ai_feature_event e "
            + "  JOIN c_ai_org o2 ON o2.id_org = e.id_org AND o2.fg_active = '1' AND o2.sd_status = '1' "
            + "  JOIN c_ai_region r2 ON r2.id_region = o2.id_region AND r2.fg_active = '1' AND r2.sd_status = '1' "
            + "  WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + "  <if test='query.dateFromTime != null'> AND e.event_time &gt;= #{query.dateFromTime}</if>"
            + "  <if test='query.dateToExclusiveTime != null'> AND e.event_time &lt; #{query.dateToExclusiveTime}</if>"
            + "  <if test='query.idRegion != null and query.idRegion != \"\"'> AND o2.id_region = #{query.idRegion}</if>"
            + "  <if test='query.idOrg != null and query.idOrg != \"\"'> AND o2.id_org = #{query.idOrg}</if>"
            + "  <if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND e.id_his_org = #{query.hisOrgId}</if>"
            + "  GROUP BY e.id_org"
            + ") l ON o.id_org = l.id_org "
            + "WHERE r.fg_active = '1' AND r.sd_status = '1' "
            + "<if test='query.idRegion != null and query.idRegion != \"\"'> AND r.id_region = #{query.idRegion}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND o.id_org = #{query.idOrg}</if>"
            + " GROUP BY r.id_region, r.na_region"
            + " ORDER BY value DESC"
            + "</script>";
    }

    public String queryConsultationOrgDistribution() {
        DatabaseDialect dialect = dialect();
        return "<script>"
            + "SELECT " + dialect.nvl("MAX(ucl.na_org)", dialect.nvl("ucl.id_his_org", "'未上报HIS机构'")) + " AS name, "
            + "COUNT(1) AS value "
            + "FROM c_ai_user_consultation_log ucl "
            + CONSULTATION_SCOPE_JOIN + " "
            + CONSULTATION_REGION_JOIN + " "
            + "WHERE ucl.fg_active = '1' "
            + consultationFilters()
            + " GROUP BY ucl.id_his_org"
            + " ORDER BY value DESC"
            + "</script>";
    }

    public String queryConsultationRegionDistributionRaw() {
        DatabaseDialect dialect = dialect();
        return "<script>"
            + "SELECT r.na_region AS name, SUM(" + dialect.nvl("l.cnt", "0") + ") AS value "
            + "FROM c_ai_region r "
            + "LEFT JOIN c_ai_org o ON o.id_region = r.id_region AND o.fg_active = '1' AND o.sd_status = '1' "
            + "LEFT JOIN ("
            + "  SELECT ucl.id_org, COUNT(1) AS cnt FROM c_ai_user_consultation_log ucl "
            + "  JOIN c_ai_org o2 ON o2.id_org = ucl.id_org AND o2.fg_active = '1' AND o2.sd_status = '1' "
            + "  JOIN c_ai_region r2 ON r2.id_region = o2.id_region AND r2.fg_active = '1' AND r2.sd_status = '1' "
            + "  WHERE ucl.fg_active = '1' "
            + "  <if test='query.dateFromTime != null'> AND ucl.consultation_time &gt;= #{query.dateFromTime}</if>"
            + "  <if test='query.dateToExclusiveTime != null'> AND ucl.consultation_time &lt; #{query.dateToExclusiveTime}</if>"
            + "  <if test='query.idRegion != null and query.idRegion != \"\"'> AND o2.id_region = #{query.idRegion}</if>"
            + "  <if test='query.idOrg != null and query.idOrg != \"\"'> AND o2.id_org = #{query.idOrg}</if>"
            + "  <if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND ucl.id_his_org = #{query.hisOrgId}</if>"
            + "  GROUP BY ucl.id_org"
            + ") l ON o.id_org = l.id_org "
            + "WHERE r.fg_active = '1' AND r.sd_status = '1' "
            + "<if test='query.idRegion != null and query.idRegion != \"\"'> AND r.id_region = #{query.idRegion}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND o.id_org = #{query.idOrg}</if>"
            + " GROUP BY r.id_region, r.na_region"
            + " ORDER BY value DESC"
            + "</script>";
    }

    public String queryDistinctModules() {
        DatabaseDialect dialect = dialect();
        if (dialect.isPgCompatible()) {
            return "SELECT module_name FROM (VALUES "
                + "('语音问诊'),('智能问诊'),('报告单解读'),('聊天'),('AI诊断鉴别'),('AI推荐诊断'),"
                + "('AI推荐用药'),('AI推荐检查'),('AI推荐检验'),('AI推荐处置'),('AI推荐治疗方案'),('知识库使用')"
                + ") AS modules(module_name)";
        }
        return "SELECT '语音问诊' AS module_name FROM dual UNION ALL "
            + "SELECT '智能问诊' FROM dual UNION ALL "
            + "SELECT '报告单解读' FROM dual UNION ALL "
            + "SELECT '聊天' FROM dual UNION ALL "
            + "SELECT 'AI诊断鉴别' FROM dual UNION ALL "
            + "SELECT 'AI推荐诊断' FROM dual UNION ALL "
            + "SELECT 'AI推荐用药' FROM dual UNION ALL "
            + "SELECT 'AI推荐检查' FROM dual UNION ALL "
            + "SELECT 'AI推荐检验' FROM dual UNION ALL "
            + "SELECT 'AI推荐处置' FROM dual UNION ALL "
            + "SELECT 'AI推荐治疗方案' FROM dual UNION ALL "
            + "SELECT '知识库使用' FROM dual";
    }

    public String queryFunctionUsageRanking() {
        DatabaseDialect dialect = dialect();
        return functionUsageRankingSql();
    }

    public String queryFunctionUsagePreviousRanking() {
        DatabaseDialect dialect = dialect();
        return functionUsageRankingSql();
    }

    public String queryFunctionUsageTrend() {
        DatabaseDialect dialect = dialect();
        String day = dialect.dayText("e.event_time");
        return "<script>"
            + "SELECT " + day + " AS dayStr, " + FUNCTION_USAGE_MODULE_EXPR + " AS moduleName, COUNT(1) AS cnt "
            + "FROM c_ai_feature_event e "
            + FEATURE_SCOPE_JOIN + " "
            + FEATURE_REGION_JOIN + " "
            + "WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + functionUsageFilters()
            + " GROUP BY " + day + ", " + FUNCTION_USAGE_MODULE_EXPR
            + " ORDER BY " + day + ", " + FUNCTION_USAGE_MODULE_EXPR
            + "</script>";
    }

    private String featureBaseCountSql(String selectExpr) {
        return "<script>"
            + "SELECT " + selectExpr + " FROM c_ai_feature_event e "
            + FEATURE_SCOPE_JOIN + " "
            + FEATURE_REGION_JOIN + " "
            + "WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + featureFilters()
            + "</script>";
    }

    private String consultationBaseCountSql(String selectExpr, String extraCondition) {
        return "<script>"
            + "SELECT " + selectExpr + " FROM c_ai_user_consultation_log ucl "
            + CONSULTATION_SCOPE_JOIN + " "
            + CONSULTATION_REGION_JOIN + " "
            + "WHERE ucl.fg_active = '1' "
            + (extraCondition == null ? "" : extraCondition + " ")
            + consultationFilters()
            + "</script>";
    }

    private String functionUsageRankingSql() {
        DatabaseDialect dialect = dialect();
        String doctorIdentity = doctorIdentityExpression(dialect);
        return "<script>"
            + "SELECT " + FUNCTION_USAGE_MODULE_EXPR + " AS moduleName, COUNT(1) AS callCount, "
            + "COUNT(DISTINCT " + doctorIdentity + ") AS doctorCount, "
            + dialect.nvl("ROUND(COUNT(1) / NULLIF(COUNT(DISTINCT " + doctorIdentity + "), 0))", "0") + " AS avgPerDoctor "
            + "FROM c_ai_feature_event e "
            + FEATURE_SCOPE_JOIN + " "
            + FEATURE_REGION_JOIN + " "
            + "WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + functionUsageFilters()
            + " GROUP BY " + FUNCTION_USAGE_MODULE_EXPR
            + " ORDER BY callCount DESC"
            + "</script>";
    }

    private String doctorIdentityExpression(DatabaseDialect dialect) {
        if (dialect.isPgCompatible()) {
            return "COALESCE(NULLIF(TRIM(e.id_doctor), ''), e.id_device)";
        }
        return dialect.nvl("TRIM(e.id_doctor)", "e.id_device");
    }

    private String featureFilters() {
        return "<if test='query.dateFromTime != null'> AND e.event_time &gt;= #{query.dateFromTime}</if>"
            + "<if test='query.dateToExclusiveTime != null'> AND e.event_time &lt; #{query.dateToExclusiveTime}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND o.id_org = #{query.idOrg}</if>"
            + "<if test='query.idRegion != null and query.idRegion != \"\"'> AND o.id_region = #{query.idRegion}</if>"
            + "<if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND e.id_his_org = #{query.hisOrgId}</if>";
    }

    private String consultationFilters() {
        return "<if test='query.dateFromTime != null'> AND ucl.consultation_time &gt;= #{query.dateFromTime}</if>"
            + "<if test='query.dateToExclusiveTime != null'> AND ucl.consultation_time &lt; #{query.dateToExclusiveTime}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND o.id_org = #{query.idOrg}</if>"
            + "<if test='query.idRegion != null and query.idRegion != \"\"'> AND o.id_region = #{query.idRegion}</if>"
            + "<if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND ucl.id_his_org = #{query.hisOrgId}</if>";
    }

    private String functionUsageFilters() {
        return "<if test='query.dateFromTime != null'> AND e.event_time &gt;= #{query.dateFromTime}</if>"
            + "<if test='query.dateToExclusiveTime != null'> AND e.event_time &lt; #{query.dateToExclusiveTime}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND o.id_org = #{query.idOrg}</if>"
            + "<if test='query.idRegion != null and query.idRegion != \"\"'> AND o.id_region = #{query.idRegion}</if>"
            + "<if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND e.id_his_org = #{query.hisOrgId}</if>"
            + "<if test='query.functionModules != null and query.functionModules.size() &gt; 0'>"
            + " AND " + FUNCTION_USAGE_MODULE_EXPR + " IN "
            + " <foreach collection='query.functionModules' item='m' open='(' separator=',' close=')'>#{m}</foreach>"
            + "</if>";
    }

    private DatabaseDialect dialect() {
        return DatabaseDialectHolder.get();
    }
}
