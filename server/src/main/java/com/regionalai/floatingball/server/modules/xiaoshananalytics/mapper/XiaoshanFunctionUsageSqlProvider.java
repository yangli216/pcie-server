package com.regionalai.floatingball.server.modules.xiaoshananalytics.mapper;

import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.db.DatabaseDialectHolder;

public class XiaoshanFunctionUsageSqlProvider {

    public String queryRanking() {
        return rankingSql();
    }

    public String queryPreviousRanking() {
        return rankingSql();
    }

    public String queryTrend() {
        DatabaseDialect dialect = dialect();
        String day = dialect.dayText("usage_data.usage_time");
        return "<script>"
            + "SELECT " + day + " AS dayStr, usage_data.module_name AS moduleName, COUNT(1) AS cnt "
            + "FROM " + usageDataSql(dialect) + " usage_data "
            + "WHERE 1 = 1 "
            + filters()
            + " GROUP BY " + day + ", usage_data.module_name"
            + " ORDER BY " + day + ", usage_data.module_name"
            + "</script>";
    }

    private String rankingSql() {
        DatabaseDialect dialect = dialect();
        String doctorCount = "COUNT(DISTINCT usage_data.doctor_identity)";
        return "<script>"
            + "SELECT usage_data.module_name AS moduleName, COUNT(1) AS callCount, "
            + doctorCount + " AS doctorCount, "
            + dialect.nvl("ROUND(COUNT(1) / NULLIF(" + doctorCount + ", 0))", "0") + " AS avgPerDoctor "
            + "FROM " + usageDataSql(dialect) + " usage_data "
            + "WHERE 1 = 1 "
            + filters()
            + " GROUP BY usage_data.module_name"
            + " ORDER BY callCount DESC"
            + "</script>";
    }

    private String usageDataSql(DatabaseDialect dialect) {
        String featureDoctor = doctorIdentity(dialect, "e.id_doctor", "e.id_device");
        String consultationDoctor = doctorIdentity(dialect, "ucl.id_doctor", "ucl.id_device");
        return "("
            + "SELECT CASE "
            + "WHEN e.feature_code = 'chat' THEN '医学助手' END AS module_name, "
            + "e.event_time AS usage_time, " + featureDoctor + " AS doctor_identity, "
            + "e.id_org AS id_org, o.id_region AS id_region, e.id_his_org AS id_his_org "
            + "FROM c_ai_feature_event e "
            + "JOIN c_ai_org o ON o.id_org = e.id_org AND o.fg_active = '1' AND o.sd_status = '1' "
            + "JOIN c_ai_region r ON r.id_region = o.id_region AND r.fg_active = '1' AND r.sd_status = '1' "
            + "WHERE e.fg_active = '1' AND LOWER(e.event_status) = 'success' "
            + "AND e.feature_code = 'chat' "
            + "UNION ALL "
            + "SELECT CASE "
            + "WHEN ucl.consultation_type = 'voice' THEN '语音问诊' "
            + "WHEN ucl.consultation_type = 'chronic_refill' THEN '慢病配药' "
            + "WHEN ucl.consultation_type = 'report_consultation' THEN '报告回诊' "
            + "WHEN ucl.consultation_type = 'report_interpretation' THEN '报告解读' END AS module_name, "
            + "ucl.consultation_time AS usage_time, " + consultationDoctor + " AS doctor_identity, "
            + "ucl.id_org AS id_org, o.id_region AS id_region, ucl.id_his_org AS id_his_org "
            + "FROM c_ai_user_consultation_log ucl "
            + "JOIN c_ai_org o ON o.id_org = ucl.id_org AND o.fg_active = '1' AND o.sd_status = '1' "
            + "JOIN c_ai_region r ON r.id_region = o.id_region AND r.fg_active = '1' AND r.sd_status = '1' "
            + "WHERE ucl.fg_active = '1' "
            + "AND ucl.consultation_type IN ('voice', 'chronic_refill', 'report_consultation', 'report_interpretation')"
            + ")";
    }

    private String filters() {
        return "<if test='query.dateFromTime != null'> AND usage_data.usage_time &gt;= #{query.dateFromTime}</if>"
            + "<if test='query.dateToExclusiveTime != null'> AND usage_data.usage_time &lt; #{query.dateToExclusiveTime}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND usage_data.id_org = #{query.idOrg}</if>"
            + "<if test='query.idRegion != null and query.idRegion != \"\"'> AND usage_data.id_region = #{query.idRegion}</if>"
            + "<if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND usage_data.id_his_org = #{query.hisOrgId}</if>"
            + "<if test='query.functionModules != null and query.functionModules.size() &gt; 0'>"
            + " AND usage_data.module_name IN "
            + " <foreach collection='query.functionModules' item='m' open='(' separator=',' close=')'>#{m}</foreach>"
            + "</if>";
    }

    private String doctorIdentity(DatabaseDialect dialect, String doctorColumn, String deviceColumn) {
        if (dialect.isPgCompatible()) {
            return "COALESCE(NULLIF(TRIM(" + doctorColumn + "), ''), " + deviceColumn + ")";
        }
        return dialect.nvl("TRIM(" + doctorColumn + ")", deviceColumn);
    }

    private DatabaseDialect dialect() {
        return DatabaseDialectHolder.get();
    }
}
