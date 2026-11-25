package com.regionalai.floatingball.server.common.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.regionalai.floatingball.server.modules.device.mapper.AiDeviceMapper;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserRoleMapper;
import org.junit.jupiter.api.Test;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiOutpatientEmrTemplateMappingMapper;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiOutpatientEmrTemplateSnapshotMapper;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalDeleteSqlContractTest {
    @Test
    void roleReplacementShouldReleaseUniqueKeyThroughAnActualLogicalDeleteStatement() {
        MybatisConfiguration configuration = configuration();
        configuration.addMapper(AiUserRoleMapper.class);
        String sql = sql(configuration, AiUserRoleMapper.class, "deleteById", "MAPPING001");

        assertTrue(sql.startsWith("UPDATE c_ai_user_role"), sql);
        assertTrue(sql.contains("fg_active='0'"), sql);
        assertTrue(sql.contains("id_user_role=?"), sql);
        assertTrue(sql.contains("fg_active='1'"), sql);
    }

    @Test
    void deviceBanLookupAndResetShouldIncludeLogicallyDeletedRows() {
        MybatisConfiguration configuration = configuration();
        configuration.addMapper(AiDeviceMapper.class);
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("cdDevice", "SYNTHETIC");
        parameters.put("idOrg", "ORG001");
        parameters.put("idDevice", "DEVICE001");

        String lookup = sql(configuration, AiDeviceMapper.class, "countDisabledByCodeAndOrg", parameters);
        assertTrue(lookup.contains("fg_active = '0'"), lookup);
        assertFalse(lookup.contains("fg_active='1'"), lookup);
        assertTrue(lookup.contains("cd_device = ? AND id_org = ?"), lookup);
        String reset = sql(configuration, AiDeviceMapper.class, "deleteForResetById", parameters);
        assertTrue(reset.startsWith("DELETE FROM c_ai_device WHERE id_device = ?"), reset);
        assertFalse(reset.contains("fg_active"), reset);
    }

    @Test
    void outpatientTemplateDeletionPhysicallyRemovesTheSnapshotAndAllMappingRows() {
        MybatisConfiguration configuration = configuration();
        configuration.addMapper(AiOutpatientEmrTemplateSnapshotMapper.class);
        configuration.addMapper(AiOutpatientEmrTemplateMappingMapper.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("id", "SYN-SNAPSHOT");
        String snapshot = sql(configuration, AiOutpatientEmrTemplateSnapshotMapper.class, "physicalDeleteById", parameters);
        String mappings = sql(configuration, AiOutpatientEmrTemplateMappingMapper.class, "physicalDeleteBySnapshotId", parameters);
        assertTrue(snapshot.equals("DELETE FROM c_ai_outpatient_emr_tpl_snapshot WHERE id_snapshot = ?"), snapshot);
        assertTrue(mappings.equals("DELETE FROM c_ai_outpatient_emr_tpl_mapping WHERE id_snapshot = ?"), mappings);
        assertFalse(snapshot.contains("fg_active"), snapshot);
        assertFalse(mappings.contains("fg_active"), mappings);
    }

    private MybatisConfiguration configuration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig global = new GlobalConfig();
        GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
        db.setLogicDeleteField("fgActive");
        db.setLogicDeleteValue("0");
        db.setLogicNotDeleteValue("1");
        global.setDbConfig(db);
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        return configuration;
    }

    private String sql(MybatisConfiguration configuration, Class<?> mapper, String method, Object parameter) {
        return configuration.getMappedStatement(mapper.getName() + "." + method)
            .getBoundSql(parameter).getSql().replaceAll("\\s+", " ").trim();
    }
}
