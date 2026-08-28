package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

@Service
public class BbpAdminGrantSchemaService implements SmartInitializingSingleton {

    static final String SCHEMA_PROBE_SQL =
        "SELECT id_grant, tenant_id, org_id, org_name, bbp_user_id, person_id, login_name, person_name, "
            + "role_code, sd_status, operator_user_id, operator_user_name, fg_active, insert_time, update_time "
            + "FROM c_ai_bbp_admin_grant WHERE 1 = 0";

    static final String ERROR_CODE = "BBP-ADMIN-GRANT-SCHEMA-NOT-READY";
    static final String REMEDIATION_MESSAGE =
        "BBP 后台访问授权表尚未初始化，请联系 DBA 根据当前数据库 init.sql 完成一次性迁移";

    private static final Logger log = LoggerFactory.getLogger(BbpAdminGrantSchemaService.class);

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean ready;

    public BbpAdminGrantSchemaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!probe()) {
            log.error("{}. New BBP admin grants cannot be managed until the schema is migrated; legacy login mapping remains available.",
                REMEDIATION_MESSAGE);
        }
    }

    public boolean isReady() {
        return ready || probe();
    }

    public void requireReady() {
        if (!isReady()) {
            throw new ServiceUnavailableException(ERROR_CODE, REMEDIATION_MESSAGE);
        }
    }

    private boolean probe() {
        try {
            jdbcTemplate.query(SCHEMA_PROBE_SQL, (ResultSetExtractor<Void>) resultSet -> null);
            ready = true;
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
