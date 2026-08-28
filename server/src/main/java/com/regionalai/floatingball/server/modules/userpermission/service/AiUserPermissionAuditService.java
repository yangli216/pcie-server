package com.regionalai.floatingball.server.modules.userpermission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AiUserPermissionAuditService {

    private final AiOpLogMapper opLogMapper;
    private final ObjectMapper objectMapper;

    public AiUserPermissionAuditService(AiOpLogMapper opLogMapper, ObjectMapper objectMapper) {
        this.opLogMapper = opLogMapper;
        this.objectMapper = objectMapper;
    }

    public void record(AdminCurrentUser currentUser,
                       String tenantId,
                       String orgId,
                       BbpPersonView person,
                       boolean enabled) {
        String personId = firstText(person.getPersonId(), firstText(person.getId(), person.getUserId()));
        String personName = firstText(person.getName(), personId);
        String action = enabled ? "grant" : "revoke";

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tenantId", tenantId);
        payload.put("orgId", orgId);
        payload.put("orgName", person.getOrgName());
        payload.put("personId", personId);
        payload.put("userId", person.getUserId());
        payload.put("personCd", firstText(person.getCode(), person.getLoginName()));
        payload.put("personName", person.getName());
        payload.put("departmentId", person.getDepartmentId());
        payload.put("departmentName", person.getDepartmentName());
        payload.put("enabled", enabled);
        payload.put("operatorUserId", currentUser.getIdUser());
        payload.put("operatorUserName", firstText(currentUser.getNaUser(), currentUser.getCdUser()));

        AiOpLog log = new AiOpLog();
        log.setIdLog(UUID.randomUUID().toString().replace("-", ""));
        log.setIdOrg(orgId);
        log.setSdLogType("operation");
        log.setNaModule("ai-user-permission");
        log.setOpAction(action);
        log.setOpTitle((enabled ? "授予" : "撤销") + " AI 使用权限：" + personName);
        log.setSourceModule("admin");
        log.setSceneCode("ai-user-permission");
        log.setDesOp("机构 " + orgId + "，人员 " + personName + "，目标状态 " + (enabled ? "已授权" : "未授权"));
        log.setOpResult("1");
        log.setOperationTime(LocalDateTime.now());
        log.setFgActive("1");
        try {
            log.setPayloadJson(objectMapper.writeValueAsString(payload));
            opLogMapper.insert(log);
        } catch (Exception ex) {
            throw new BusinessException("AI 权限操作日志写入失败，请稍后重试");
        }
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim()
            : (StringUtils.hasText(fallback) ? fallback.trim() : "--");
    }
}
