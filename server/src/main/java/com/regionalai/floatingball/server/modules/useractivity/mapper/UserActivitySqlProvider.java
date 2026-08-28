package com.regionalai.floatingball.server.modules.useractivity.mapper;

public class UserActivitySqlProvider {

    private static final String CONSULTATION_SCOPE_JOIN = "JOIN c_ai_org o ON o.id_org = ucl.id_org AND o.fg_active = '1' AND o.sd_status = '1'";
    private static final String CONSULTATION_REGION_JOIN = "JOIN c_ai_region r ON r.id_region = o.id_region AND r.fg_active = '1' AND r.sd_status = '1'";

    public String countActiveDoctors() {
        return doctorCount(true);
    }

    public String countTotalDoctors() {
        return doctorCount(false);
    }

    public String countEffectiveConsultations() {
        return consultationCount("AND ucl.status = 'completed'");
    }

    public String countConsultations() {
        return consultationCount("");
    }

    public String queryAllRegions() {
        return "SELECT id_region AS id, na_region AS name, sd_region_type AS type, id_parent AS parentId "
            + "FROM c_ai_region WHERE fg_active = '1' AND sd_status = '1' "
            + "ORDER BY sort_order, na_region";
    }

    public String countActiveDoctorsByRegion() {
        return "<script>"
            + "SELECT o.id_region, COUNT(DISTINCT ucl.id_doctor) AS cnt "
            + consultationFactFrom()
            + "WHERE ucl.fg_active = '1' AND ucl.id_doctor IS NOT NULL "
            + consultationFactFilters("ucl")
            + scopeFilters()
            + " GROUP BY o.id_region"
            + "</script>";
    }

    public String queryDoctorActivityList() {
        String activityCase = activityCase("facts", null);
        String effectiveActivityCase = activityCase("facts", "facts.status = 'completed'");
        return "<script>"
            + "SELECT facts.idDoctor AS idDoctor, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.naDoctor END) AS naDoctor, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.idOrg END) AS idOrg, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.naOrg END) AS naOrg, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.hisOrgId END) AS hisOrgId, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.hisOrgName END) AS hisOrgName, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.idRegion END) AS idRegion, "
            + "MAX(CASE WHEN facts.latestRank = 1 THEN facts.naRegion END) AS naRegion, "
            + "COUNT(DISTINCT facts.idDevice) AS deviceCount, "
            + "SUM(" + activityCase + ") AS consultationCount, "
            + "SUM(" + effectiveActivityCase + ") AS effectiveConsultationCount, "
            + "MAX(CASE WHEN 1 = 1 " + consultationTimeFilters("facts")
            + " THEN facts.consultation_time ELSE NULL END) AS lastActiveTime "
            + "FROM ("
            + "SELECT ucl.id_doctor AS idDoctor, ucl.na_doctor AS naDoctor, "
            + "o.id_org AS idOrg, o.na_org AS naOrg, "
            + "ucl.id_his_org AS hisOrgId, ucl.na_org AS hisOrgName, "
            + "o.id_region AS idRegion, r.na_region AS naRegion, "
            + "ucl.id_device AS idDevice, ucl.status AS status, "
            + "ucl.consultation_time AS consultation_time, "
            + "ROW_NUMBER() OVER (PARTITION BY ucl.id_doctor "
            + "ORDER BY ucl.consultation_time DESC, ucl.id_log DESC) AS latestRank "
            + consultationFactFrom()
            + "WHERE ucl.fg_active = '1' AND ucl.id_doctor IS NOT NULL "
            + hisOrgFilter("ucl")
            + scopeFilters()
            + ") facts"
            + " GROUP BY facts.idDoctor"
            + "<if test='query.activeStatus == \"active\"'>"
            + " HAVING SUM(" + activityCase + ") &gt; 0"
            + "</if>"
            + "<if test='query.activeStatus == \"inactive\"'>"
            + " HAVING SUM(" + activityCase + ") = 0"
            + "</if>"
            + " ORDER BY consultationCount DESC, effectiveConsultationCount DESC, "
            + "lastActiveTime DESC NULLS LAST, idDoctor ASC"
            + "</script>";
    }

    private String doctorCount(boolean currentPeriodOnly) {
        return "<script>"
            + "SELECT COUNT(DISTINCT ucl.id_doctor) AS cnt "
            + consultationFactFrom()
            + "WHERE ucl.fg_active = '1' AND ucl.id_doctor IS NOT NULL "
            + (currentPeriodOnly ? consultationTimeFilters("ucl") : "")
            + hisOrgFilter("ucl")
            + scopeFilters()
            + "</script>";
    }

    private String consultationCount(String extraCondition) {
        return "<script>"
            + "SELECT COUNT(1) AS cnt "
            + consultationFactFrom()
            + "WHERE ucl.fg_active = '1' "
            + extraCondition + " "
            + consultationFactFilters("ucl")
            + scopeFilters()
            + "</script>";
    }

    private String consultationFactFrom() {
        return "FROM c_ai_user_consultation_log ucl "
            + CONSULTATION_SCOPE_JOIN + " "
            + CONSULTATION_REGION_JOIN + " ";
    }

    private String activityCase(String alias, String extraCondition) {
        return "CASE WHEN 1 = 1 "
            + (extraCondition == null ? "" : "AND " + extraCondition + " ")
            + consultationTimeFilters(alias)
            + " THEN 1 ELSE 0 END";
    }

    private String consultationFactFilters(String alias) {
        return consultationTimeFilters(alias) + hisOrgFilter(alias);
    }

    private String consultationTimeFilters(String alias) {
        return "<if test='query.dateFromTime != null'> AND " + alias + ".consultation_time &gt;= #{query.dateFromTime}</if>"
            + "<if test='query.dateToExclusiveTime != null'> AND " + alias + ".consultation_time &lt; #{query.dateToExclusiveTime}</if>";
    }

    private String hisOrgFilter(String alias) {
        return "<if test='query.hisOrgId != null and query.hisOrgId != \"\"'> AND "
            + alias + ".id_his_org = #{query.hisOrgId}</if>";
    }

    private String scopeFilters() {
        return "<if test='query.idRegion != null and query.idRegion != \"\"'> AND o.id_region = #{query.idRegion}</if>"
            + "<if test='query.idOrg != null and query.idOrg != \"\"'> AND o.id_org = #{query.idOrg}</if>";
    }
}
