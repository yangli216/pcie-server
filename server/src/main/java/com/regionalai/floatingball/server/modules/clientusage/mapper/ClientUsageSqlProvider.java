package com.regionalai.floatingball.server.modules.clientusage.mapper;

public class ClientUsageSqlProvider {

    private static final String RANKED_EVENTS_CTE = "WITH ranked_events AS ("
        + "SELECT e.id_event, e.id_org, e.id_his_org, e.na_his_org, e.cd_doctor, "
        + "e.na_doctor, e.client_version, e.event_time, o.na_org AS platform_org_name, "
        + "ROW_NUMBER() OVER (PARTITION BY e.id_org, e.id_his_org, e.cd_doctor "
        + "ORDER BY e.event_time DESC, e.id_event DESC) AS usage_rank "
        + "FROM c_ai_feature_event e "
        + "JOIN c_ai_org o ON o.id_org = e.id_org AND o.fg_active = '1' AND o.sd_status = '1' "
        + "WHERE e.fg_active = '1' "
        + "AND e.cd_doctor IS NOT NULL AND LENGTH(TRIM(e.cd_doctor)) &gt; 0 "
        + "AND e.client_version IS NOT NULL AND LENGTH(TRIM(e.client_version)) &gt; 0"
        + ") ";

    private static final String FILTERED_USAGE_CTE = ", filtered_usage AS ("
        + "SELECT current_usage.id_org AS usageOrgId, current_usage.id_his_org AS usageHisOrgId, "
        + "COALESCE(NULLIF(TRIM(current_usage.na_his_org), ''), current_usage.platform_org_name) AS orgName, "
        + "current_usage.na_doctor AS doctorName, current_usage.cd_doctor AS doctorWorkNo, "
        + "(SELECT MIN(version_usage.event_time) FROM c_ai_feature_event version_usage "
        + "WHERE version_usage.fg_active = '1' "
        + "AND version_usage.id_org = current_usage.id_org "
        + "AND (version_usage.id_his_org = current_usage.id_his_org "
        + "OR (version_usage.id_his_org IS NULL AND current_usage.id_his_org IS NULL)) "
        + "AND version_usage.cd_doctor = current_usage.cd_doctor "
        + "AND version_usage.client_version = current_usage.client_version) AS firstInteractionTime, "
        + "current_usage.client_version AS clientVersion, current_usage.event_time AS lastActiveTime "
        + "FROM ranked_events current_usage WHERE current_usage.usage_rank = 1 "
        + "<if test='query.keyword != null and query.keyword != \"\"'>"
        + "AND (COALESCE(NULLIF(TRIM(current_usage.na_his_org), ''), current_usage.platform_org_name) "
        + "LIKE '%' || #{query.keyword} || '%' "
        + "OR current_usage.na_doctor LIKE '%' || #{query.keyword} || '%' "
        + "OR current_usage.cd_doctor LIKE '%' || #{query.keyword} || '%' "
        + "OR current_usage.client_version LIKE '%' || #{query.keyword} || '%')"
        + "</if> "
        + ") ";

    public String countClientUsage() {
        return "<script>"
            + RANKED_EVENTS_CTE
            + FILTERED_USAGE_CTE
            + "SELECT COUNT(*) FROM filtered_usage"
            + "</script>";
    }

    public String queryClientUsage() {
        return "<script>"
            + RANKED_EVENTS_CTE
            + FILTERED_USAGE_CTE
            + ", numbered_usage AS ("
            + "SELECT filtered_usage.*, ROW_NUMBER() OVER ("
            + "ORDER BY lastActiveTime DESC, doctorWorkNo ASC, usageOrgId ASC, "
            + "COALESCE(usageHisOrgId, '') ASC) AS page_row_num "
            + "FROM filtered_usage"
            + ") "
            + "SELECT orgName, doctorName, doctorWorkNo, firstInteractionTime, clientVersion, lastActiveTime "
            + "FROM numbered_usage "
            + "WHERE page_row_num &gt; #{offset} AND page_row_num &lt;= #{endRow} "
            + "ORDER BY page_row_num"
            + "</script>";
    }
}
