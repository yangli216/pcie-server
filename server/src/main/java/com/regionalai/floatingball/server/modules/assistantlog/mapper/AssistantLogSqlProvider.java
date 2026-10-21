package com.regionalai.floatingball.server.modules.assistantlog.mapper;

import com.regionalai.floatingball.server.modules.assistantlog.dto.AssistantLogQuery;
import java.util.Map;

public class AssistantLogSqlProvider {
    private static final String FROM = " FROM c_ai_op_log l LEFT JOIN c_ai_org o ON o.id_org = l.id_org ";
    private static final String SCOPE = " WHERE l.fg_active = '1' AND l.sd_log_type = 'ai_proxy' "
        + "AND l.na_module = 'ai' AND l.source_module = 'chat_panel' AND l.scene_code IN ('chat-stream', 'chat') ";
    private static final String ORG_NAME = "COALESCE(l.na_his_org, o.na_org, l.id_org)";
    private static final String COLUMNS = "l.id_log, l.id_org, l.id_his_org AS his_org_id, l.operation_time, l.op_result, "
        + "l.payload_json, " + ORG_NAME + " AS organization_name";

    public String count(Map<String, Object> args) {
        return "SELECT COUNT(*)" + FROM + filters(args);
    }

    public String list(Map<String, Object> args) {
        String pagination = Boolean.TRUE.equals(args.get("pg"))
            ? " LIMIT #{limit} OFFSET #{offset}"
            : " OFFSET #{offset} ROWS FETCH NEXT #{limit} ROWS ONLY";
        return "SELECT " + COLUMNS + FROM + filters(args)
            + " ORDER BY l.operation_time DESC, l.id_log DESC" + pagination;
    }

    public String exportRows(Map<String, Object> args) {
        return "SELECT " + COLUMNS + FROM + filters(args)
            + " ORDER BY l.operation_time DESC, l.id_log DESC"
            + (Boolean.TRUE.equals(args.get("pg")) ? " LIMIT 1001" : " FETCH FIRST 1001 ROWS ONLY");
    }

    public String detail() {
        return "SELECT " + COLUMNS + FROM + SCOPE + " AND l.id_log = #{idLog}";
    }

    public String organizations() {
        return "SELECT l.id_org, l.id_his_org AS his_org_id, MAX(" + ORG_NAME + ") AS org_name"
            + FROM + SCOPE + " GROUP BY l.id_org, l.id_his_org ORDER BY org_name";
    }

    private String filters(Map<String, Object> args) {
        AssistantLogQuery query = (AssistantLogQuery) args.get("query");
        StringBuilder sql = new StringBuilder(SCOPE);
        sql.append(" AND l.operation_time >= #{query.startTime} AND l.operation_time < #{query.endTime}");
        if (query.getIdOrg() != null) sql.append(" AND l.id_org = #{query.idOrg}");
        if (query.getHisOrgId() != null) sql.append(" AND l.id_his_org = #{query.hisOrgId}");
        if (query.getDoctorName() != null) {
            // Only fixed JSON paths and bound values; literal substring matching preserves % and _.
            sql.append(Boolean.TRUE.equals(args.get("pg"))
                ? " AND POSITION(#{query.doctorName} IN (l.payload_json::jsonb ->> 'doctorName')) > 0"
                : " AND INSTR(JSON_VALUE(l.payload_json, '$.doctorName' RETURNING VARCHAR2(128) NULL ON ERROR), #{query.doctorName}) > 0");
        }
        return sql.toString();
    }
}
