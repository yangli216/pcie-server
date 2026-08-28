package com.regionalai.floatingball.server.modules.auth.bbp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BbpAdminAccessAuditService {

    private final AiOpLogMapper opLogMapper;
    private final ObjectMapper objectMapper;

    public BbpAdminAccessAuditService(AiOpLogMapper opLogMapper, ObjectMapper objectMapper) {
        this.opLogMapper = opLogMapper;
        this.objectMapper = objectMapper;
    }

    public void record(AdminCurrentUser currentUser, BbpAdminGrant grant, boolean enabled) {
        String personName = text(grant.getPersonName(), grant.getBbpUserId());
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tenantId", grant.getTenantId());
        payload.put("orgId", grant.getOrgId());
        payload.put("orgName", grant.getOrgName());
        payload.put("bbpUserId", grant.getBbpUserId());
        payload.put("personId", grant.getPersonId());
        payload.put("loginName", grant.getLoginName());
        payload.put("personName", grant.getPersonName());
        payload.put("roleCode", grant.getRoleCode());
        payload.put("enabled", enabled);
        payload.put("operatorUserId", currentUser.getIdUser());
        payload.put("operatorUserName", text(currentUser.getNaUser(), currentUser.getCdUser()));

        AiOpLog log = new AiOpLog();
        log.setIdOrg(grant.getOrgId());
        log.setSdLogType("operation");
        log.setNaModule("bbp-admin-access");
        log.setOpAction(enabled ? "grant" : "revoke");
        log.setOpTitle((enabled ? "授予" : "撤销") + " PCIE " + roleName(grant.getRoleCode()) + "权限：" + personName);
        log.setSourceModule("admin");
        log.setSceneCode("bbp-admin-access");
        log.setDesOp("机构 " + grant.getOrgId() + "，BBP 用户 " + personName
            + "，目标角色 " + grant.getRoleCode() + "，目标状态 " + (enabled ? "已授权" : "未授权"));
        log.setOpResult("1");
        log.setOperationTime(LocalDateTime.now());
        log.setFgActive("1");
        try {
            log.setPayloadJson(objectMapper.writeValueAsString(payload));
            opLogMapper.insert(log);
        } catch (Exception ex) {
            throw new BusinessException("PCIE 后台权限操作日志写入失败，请稍后重试");
        }
    }

    private String text(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim()
            : (StringUtils.hasText(fallback) ? fallback.trim() : "--");
    }

    private String roleName(String roleCode) {
        return BbpAdminAccessService.ORG_ANALYST.equals(roleCode) ? "机构统计员" : "机构管理员";
    }
}
