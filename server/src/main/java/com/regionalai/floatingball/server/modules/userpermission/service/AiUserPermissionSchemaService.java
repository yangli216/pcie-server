package com.regionalai.floatingball.server.modules.userpermission.service;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

@Service
public class AiUserPermissionSchemaService implements SmartInitializingSingleton {

    static final String SCHEMA_PROBE_SQL =
        "SELECT id_permission, tenant_id, org_id, org_name, person_id, user_id, person_cd, person_name, "
            + "dept_id, dept_name, subject_key, sd_status, operator_user_id, operator_user_name, "
            + "fg_active, insert_time, update_time FROM c_ai_user_ai_permission WHERE 1 = 0";

    static final String ERROR_CODE = "AI-PERMISSION-SCHEMA-NOT-READY";
    static final String REMEDIATION_MESSAGE =
        "AI 使用权限数据表尚未初始化，请联系 DBA 根据当前数据库 init.sql 完成一次性迁移";

    private static final Logger log = LoggerFactory.getLogger(AiUserPermissionSchemaService.class);

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean ready;

    public AiUserPermissionSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            verifySchema();
        } catch (RuntimeException ex) {
            log.error("{}. Permission APIs will return 503 until the schema is migrated.", REMEDIATION_MESSAGE);
        }
    }

    public void requireReady() {
        if (ready) {
            return;
        }
        try {
            verifySchema();
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException(ERROR_CODE, REMEDIATION_MESSAGE);
        }
    }

    private void verifySchema() {
        jdbcTemplate.query(SCHEMA_PROBE_SQL, (ResultSetExtractor<Void>) resultSet -> null);
        ready = true;
    }
}
