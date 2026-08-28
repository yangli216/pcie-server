package com.regionalai.floatingball.server.modules.userpermission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAdminScope;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionListView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionOrgRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateResponse;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionDepartmentView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionRecordView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionUpdateRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckResponse;
import com.regionalai.floatingball.server.modules.userpermission.entity.AiUserAiPermission;
import com.regionalai.floatingball.server.modules.userpermission.mapper.AiUserAiPermissionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiUserPermissionService {

    private final AiUserAiPermissionMapper permissionMapper;
    private final BbpDirectoryService directoryService;
    private final BbpAdminScope adminScope;
    private final AdminAuthMode authMode;
    private final AiUserPermissionAuditService auditService;
    private final AiUserPermissionSchemaService schemaService;

    public AiUserPermissionService(AiUserAiPermissionMapper permissionMapper,
                                   BbpDirectoryService directoryService,
                                   BbpAdminScope adminScope,
                                   AdminAuthMode authMode,
                                   AiUserPermissionAuditService auditService,
                                   AiUserPermissionSchemaService schemaService) {
        this.permissionMapper = permissionMapper;
        this.directoryService = directoryService;
        this.adminScope = adminScope;
        this.authMode = authMode;
        this.auditService = auditService;
        this.schemaService = schemaService;
    }

    public AiUserPermissionListView list(AdminCurrentUser currentUser,
                                         String orgId,
                                         String keyword,
                                         Boolean configured,
                                         String departmentId,
                                         Boolean active,
                                         int current,
        int size) {
        validatePage(current, size);
        String tenantId = adminScope.requireTenantId(currentUser);
        String permittedOrgId = adminScope.requireOrgAccess(currentUser, orgId);
        schemaService.requireReady();
        List<BbpPersonView> persons = directoryService.persons(currentUser, permittedOrgId);
        Map<String, AiUserAiPermission> permissions = permissionMap(tenantId, permittedOrgId);

        List<AiUserPermissionRecordView> allRecords = persons.stream()
            .map(person -> toView(tenantId, permittedOrgId, person, permissions.get(subjectKey(person))))
            .collect(Collectors.toList());
        allRecords.sort(Comparator
            .comparing((AiUserPermissionRecordView item) -> normalize(item.getDepartmentName()))
            .thenComparing(item -> normalize(item.getPersonName()))
            .thenComparing(item -> normalize(item.getPersonCd()))
            .thenComparing(item -> normalize(item.getPersonId())));
        int configuredCount = (int) allRecords.stream().filter(AiUserPermissionRecordView::isConfigured).count();
        Map<String, BbpPersonView> personsBySubject = persons.stream().collect(Collectors.toMap(
            this::subjectKey,
            person -> person,
            (left, right) -> left,
            LinkedHashMap::new));
        int riskAuthorizedCount = (int) permissions.entrySet().stream()
            .filter(entry -> "1".equals(entry.getValue().getSdStatus()))
            .filter(entry -> {
                BbpPersonView person = personsBySubject.get(entry.getKey());
                return person == null || !Boolean.TRUE.equals(person.getActive());
            })
            .count();

        String normalizedKeyword = normalize(keyword);
        String normalizedDepartmentId = StringUtils.hasText(departmentId) ? departmentId.trim() : null;
        List<AiUserPermissionRecordView> filtered = allRecords.stream()
            .filter(item -> configured == null || configured.booleanValue() == item.isConfigured())
            .filter(item -> active == null || active.equals(item.getActive()))
            .filter(item -> normalizedDepartmentId == null || normalizedDepartmentId.equals(item.getDepartmentId()))
            .filter(item -> !StringUtils.hasText(normalizedKeyword) || containsKeyword(item, normalizedKeyword))
            .collect(Collectors.toList());
        long requestedFrom = (long) (current - 1) * size;
        int from = requestedFrom >= filtered.size() ? filtered.size() : (int) requestedFrom;
        int to = Math.min(filtered.size(), from + size);
        List<AiUserPermissionRecordView> paged = new ArrayList<AiUserPermissionRecordView>(filtered.subList(from, to));

        Map<String, String> departmentMap = new LinkedHashMap<String, String>();
        for (AiUserPermissionRecordView item : allRecords) {
            if (StringUtils.hasText(item.getDepartmentId())) {
                departmentMap.putIfAbsent(item.getDepartmentId(),
                    identity(item.getDepartmentName(), item.getDepartmentId()));
            }
        }
        List<AiUserPermissionDepartmentView> departments = departmentMap.entrySet().stream()
            .map(entry -> new AiUserPermissionDepartmentView(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(item -> normalize(item.getName())))
            .collect(Collectors.toList());

        AiUserPermissionListView result = new AiUserPermissionListView();
        result.setOrgId(permittedOrgId);
        result.setManageable(adminScope.canManageAiPermissions(currentUser));
        result.setTotal(allRecords.size());
        result.setConfiguredCount(configuredCount);
        result.setUnconfiguredCount(allRecords.size() - configuredCount);
        result.setRiskAuthorizedCount(riskAuthorizedCount);
        result.setFilteredTotal(filtered.size());
        result.setCurrent(current);
        result.setSize(size);
        result.setDepartments(departments);
        result.setRecords(paged);
        return result;
    }

    @Transactional
    public AiUserPermissionRecordView update(AdminCurrentUser currentUser,
                                             String personId,
                                             AiUserPermissionUpdateRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException("权限状态不能为空");
        }
        if (!StringUtils.hasText(personId)) {
            throw new BusinessException("人员 ID 不能为空");
        }
        adminScope.requireAiPermissionWriteAccess(currentUser);
        schemaService.requireReady();
        String tenantId = adminScope.requireTenantId(currentUser);
        String orgId = adminScope.requireOrgAccess(currentUser, request.getOrgId());
        BbpPersonView person = directoryService.persons(currentUser, orgId).stream()
            .filter(item -> personId.trim().equals(stablePersonId(item)))
            .findFirst()
            .orElseThrow(() -> new BusinessException("所选人员不属于当前机构或已不在 BBP 人员目录"));
        if (request.getEnabled() && !Boolean.TRUE.equals(person.getActive())) {
            throw new BusinessException("BBP人员未明确处于启用状态，不能授予 AI 使用权限");
        }

        PermissionWriteResult writeResult = writePermission(
            tenantId, orgId, person, currentUser, request.getEnabled(),
            findBySubject(tenantId, orgId, subjectKey(person)));
        return toView(tenantId, orgId, person, writeResult.permission);
    }

    @Transactional
    public AiUserPermissionBatchUpdateResponse batchUpdate(AdminCurrentUser currentUser,
                                                           AiUserPermissionBatchUpdateRequest request) {
        validateBatchRequest(request);
        adminScope.requireAiPermissionWriteAccess(currentUser);
        schemaService.requireReady();
        String tenantId = adminScope.requireTenantId(currentUser);
        String orgId = adminScope.requireOrgAccess(currentUser, request.getOrgId());
        Set<String> requestedPersonIds = normalizePersonIds(request.getPersonIds());
        List<BbpPersonView> persons = directoryService.persons(currentUser, orgId);
        Map<String, BbpPersonView> personMap = persons.stream().collect(Collectors.toMap(
            this::stablePersonId,
            person -> person,
            (left, right) -> left,
            LinkedHashMap::new));

        List<BbpPersonView> targets = new ArrayList<BbpPersonView>();
        for (String personId : requestedPersonIds) {
            BbpPersonView person = personMap.get(personId);
            if (person == null) {
                throw new BusinessException("人员 " + personId + " 不属于当前机构或已不在 BBP 人员目录，整批未处理");
            }
            if (request.getEnabled() && !Boolean.TRUE.equals(person.getActive())) {
                throw new BusinessException("BBP人员 " + identity(person.getName(), personId)
                    + " 未明确处于启用状态，整批未处理");
            }
            targets.add(person);
        }

        Map<String, AiUserAiPermission> permissions = permissionMap(tenantId, orgId);
        List<AiUserPermissionRecordView> records = new ArrayList<AiUserPermissionRecordView>();
        int changedCount = 0;
        for (BbpPersonView person : targets) {
            PermissionWriteResult writeResult = writePermission(
                tenantId, orgId, person, currentUser, request.getEnabled(), permissions.get(subjectKey(person)));
            if (writeResult.changed) {
                changedCount++;
            }
            records.add(toView(tenantId, orgId, person, writeResult.permission));
        }

        AiUserPermissionBatchUpdateResponse response = new AiUserPermissionBatchUpdateResponse();
        response.setOrgId(orgId);
        response.setEnabled(request.getEnabled());
        response.setRequestedCount(targets.size());
        response.setChangedCount(changedCount);
        response.setUnchangedCount(targets.size() - changedCount);
        response.setRecords(records);
        return response;
    }

    @Transactional
    public AiUserPermissionBatchUpdateResponse revokeRiskPermissions(AdminCurrentUser currentUser,
                                                                     AiUserPermissionOrgRequest request) {
        if (request == null || !StringUtils.hasText(request.getOrgId())) {
            throw new BusinessException("机构 ID 不能为空");
        }
        adminScope.requireAiPermissionWriteAccess(currentUser);
        schemaService.requireReady();
        String tenantId = adminScope.requireTenantId(currentUser);
        String orgId = adminScope.requireOrgAccess(currentUser, request.getOrgId());
        List<BbpPersonView> persons = directoryService.persons(currentUser, orgId);
        Map<String, BbpPersonView> personsBySubject = persons.stream().collect(Collectors.toMap(
            this::subjectKey,
            person -> person,
            (left, right) -> left,
            LinkedHashMap::new));
        Map<String, AiUserAiPermission> permissions = permissionMap(tenantId, orgId);

        List<AiUserPermissionRecordView> records = new ArrayList<AiUserPermissionRecordView>();
        int changedCount = 0;
        for (Map.Entry<String, AiUserAiPermission> entry : permissions.entrySet()) {
            AiUserAiPermission permission = entry.getValue();
            if (!"1".equals(permission.getSdStatus())) {
                continue;
            }
            BbpPersonView person = personsBySubject.get(entry.getKey());
            if (person != null && Boolean.TRUE.equals(person.getActive())) {
                continue;
            }
            BbpPersonView auditPerson = person == null ? personFromPermission(permission) : person;
            PermissionWriteResult writeResult = writePermission(
                tenantId, orgId, auditPerson, currentUser, false, permission);
            if (writeResult.changed) {
                changedCount++;
            }
            records.add(toView(tenantId, orgId, auditPerson, writeResult.permission));
        }

        AiUserPermissionBatchUpdateResponse response = new AiUserPermissionBatchUpdateResponse();
        response.setOrgId(orgId);
        response.setEnabled(false);
        response.setRequestedCount(records.size());
        response.setChangedCount(changedCount);
        response.setUnchangedCount(records.size() - changedCount);
        response.setRecords(records);
        return response;
    }

    public PhisAiPermissionCheckResponse check(PhisAiPermissionCheckRequest request) {
        validateCheckRequest(request);
        schemaService.requireReady();
        String tenantId = resolveTenantId(request.getTenantId());
        QueryWrapper<AiUserAiPermission> wrapper = new QueryWrapper<AiUserAiPermission>()
            .eq("tenant_id", tenantId)
            .eq("org_id", request.getOrgId().trim())
            .eq("fg_active", "1");
        if (StringUtils.hasText(request.getPersonId())) {
            wrapper.eq("person_id", request.getPersonId().trim());
        }
        if (StringUtils.hasText(request.getUserId())) {
            wrapper.eq("user_id", request.getUserId().trim());
        }
        if (StringUtils.hasText(request.getPersonCd())) {
            wrapper.eq("person_cd", request.getPersonCd().trim());
        }
        List<AiUserAiPermission> matches = permissionMapper.selectList(wrapper);
        AiUserAiPermission permission = matches == null ? null : matches.stream()
            .filter(item -> "1".equals(item.getSdStatus()))
            .findFirst()
            .orElse(null);

        PhisAiPermissionCheckResponse response = new PhisAiPermissionCheckResponse();
        response.setAllowed(permission != null);
        response.setReason(permission == null ? "NOT_AUTHORIZED" : "AUTHORIZED");
        response.setPermissionId(permission == null ? null : permission.getIdPermission());
        response.setOrgId(request.getOrgId().trim());
        response.setPersonId(identity(request.getPersonId(), permission == null ? null : permission.getPersonId()));
        response.setUserId(identity(request.getUserId(), permission == null ? null : permission.getUserId()));
        response.setPersonCd(identity(request.getPersonCd(), permission == null ? null : permission.getPersonCd()));
        return response;
    }

    private Map<String, AiUserAiPermission> permissionMap(String tenantId, String orgId) {
        List<AiUserAiPermission> permissions = permissionMapper.selectList(
            new LambdaQueryWrapper<AiUserAiPermission>()
                .eq(AiUserAiPermission::getTenantId, tenantId)
                .eq(AiUserAiPermission::getOrgId, orgId)
                .eq(AiUserAiPermission::getFgActive, "1"));
        if (permissions == null || permissions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, AiUserAiPermission> result = new LinkedHashMap<String, AiUserAiPermission>();
        for (AiUserAiPermission permission : permissions) {
            result.put(permission.getSubjectKey(), permission);
        }
        return result;
    }

    private AiUserAiPermission findBySubject(String tenantId, String orgId, String subjectKey) {
        List<AiUserAiPermission> records = permissionMapper.selectList(
            new LambdaQueryWrapper<AiUserAiPermission>()
                .eq(AiUserAiPermission::getTenantId, tenantId)
                .eq(AiUserAiPermission::getOrgId, orgId)
                .eq(AiUserAiPermission::getSubjectKey, subjectKey)
                .eq(AiUserAiPermission::getFgActive, "1"));
        return records == null || records.isEmpty() ? null : records.get(0);
    }

    private void applySnapshot(AiUserAiPermission permission,
                               BbpPersonView person,
                               AdminCurrentUser currentUser,
                               boolean enabled) {
        permission.setOrgName(person.getOrgName());
        permission.setPersonId(identity(person.getPersonId(), person.getId()));
        permission.setUserId(person.getUserId());
        permission.setPersonCd(identity(person.getCode(), person.getLoginName()));
        permission.setPersonName(person.getName());
        permission.setDeptId(person.getDepartmentId());
        permission.setDeptName(person.getDepartmentName());
        permission.setSdStatus(enabled ? "1" : "0");
        permission.setOperatorUserId(currentUser.getIdUser());
        permission.setOperatorUserName(identity(currentUser.getNaUser(), currentUser.getCdUser()));
    }

    private AiUserPermissionRecordView toView(String tenantId,
                                              String orgId,
                                              BbpPersonView person,
                                              AiUserAiPermission permission) {
        AiUserPermissionRecordView view = new AiUserPermissionRecordView();
        view.setPermissionId(permission == null ? null : permission.getIdPermission());
        view.setTenantId(tenantId);
        view.setOrgId(orgId);
        view.setOrgName(identity(person.getOrgName(), permission == null ? null : permission.getOrgName()));
        view.setPersonId(stablePersonId(person));
        view.setUserId(person.getUserId());
        view.setPersonCd(identity(person.getCode(), person.getLoginName()));
        view.setPersonName(person.getName());
        view.setDepartmentId(person.getDepartmentId());
        view.setDepartmentName(person.getDepartmentName());
        view.setPersonType(person.getPersonType());
        view.setPersonTypeText(person.getPersonTypeText());
        view.setTitleType(person.getTitleType());
        view.setTitleTypeText(person.getTitleTypeText());
        view.setActive(person.getActive());
        view.setConfigured(permission != null && "1".equals(permission.getSdStatus()));
        view.setOperatorUserName(permission == null ? null : permission.getOperatorUserName());
        view.setUpdateTime(permission == null ? null : permission.getUpdateTime());
        return view;
    }

    private PermissionWriteResult writePermission(String tenantId,
                                                  String orgId,
                                                  BbpPersonView person,
                                                  AdminCurrentUser currentUser,
                                                  boolean enabled,
                                                  AiUserAiPermission permission) {
        boolean configured = permission != null && "1".equals(permission.getSdStatus());
        if (configured == enabled) {
            return new PermissionWriteResult(permission, false);
        }
        if (permission == null && !enabled) {
            return new PermissionWriteResult(null, false);
        }

        boolean creating = permission == null;
        if (creating) {
            permission = new AiUserAiPermission();
            permission.setTenantId(tenantId);
            permission.setOrgId(orgId);
            permission.setSubjectKey(subjectKey(person));
            permission.setFgActive("1");
        }
        applySnapshot(permission, person, currentUser, enabled);
        try {
            if (creating) {
                permissionMapper.insert(permission);
            } else {
                permissionMapper.updateById(permission);
            }
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("AI-PERMISSION-CONFLICT", "权限状态已被其他操作更新，请刷新后重试");
        }
        auditService.record(currentUser, tenantId, orgId, person, enabled);
        return new PermissionWriteResult(permission, true);
    }

    private String subjectKey(BbpPersonView person) {
        if (StringUtils.hasText(person.getPersonId())) {
            return "P:" + person.getPersonId().trim();
        }
        if (StringUtils.hasText(person.getId())) {
            return "P:" + person.getId().trim();
        }
        if (StringUtils.hasText(person.getUserId())) {
            return "U:" + person.getUserId().trim();
        }
        String personCd = identity(person.getCode(), person.getLoginName());
        if (StringUtils.hasText(personCd)) {
            return "C:" + personCd.trim();
        }
        throw new BusinessException("BBP人员缺少可用于授权的稳定身份标识");
    }

    private String stablePersonId(BbpPersonView person) {
        String personId = identity(person.getPersonId(), person.getId());
        return identity(personId, person.getUserId());
    }

    private BbpPersonView personFromPermission(AiUserAiPermission permission) {
        BbpPersonView person = new BbpPersonView();
        person.setId(permission.getPersonId());
        person.setPersonId(permission.getPersonId());
        person.setUserId(permission.getUserId());
        person.setCode(permission.getPersonCd());
        person.setName(permission.getPersonName());
        person.setOrgId(permission.getOrgId());
        person.setOrgName(permission.getOrgName());
        person.setDepartmentId(permission.getDeptId());
        person.setDepartmentName(permission.getDeptName());
        person.setActive(false);
        return person;
    }

    private String resolveTenantId(String requestedTenantId) {
        String configuredTenantId = authMode.configuredTenantId();
        if (StringUtils.hasText(configuredTenantId)) {
            if (StringUtils.hasText(requestedTenantId) && !configuredTenantId.equals(requestedTenantId.trim())) {
                throw new BusinessException("租户与 PCIE 配置不一致");
            }
            return configuredTenantId;
        }
        if (!StringUtils.hasText(requestedTenantId)) {
            throw new BusinessException("租户 ID 不能为空");
        }
        return requestedTenantId.trim();
    }

    private void validateCheckRequest(PhisAiPermissionCheckRequest request) {
        if (request == null || !StringUtils.hasText(request.getOrgId())) {
            throw new BusinessException("机构 ID 不能为空");
        }
        if (!StringUtils.hasText(request.getPersonId())
            && !StringUtils.hasText(request.getUserId())) {
            throw new BusinessException("personId、userId 至少提供一个，personCd 不能单独用于判权");
        }
    }

    private void validateBatchRequest(AiUserPermissionBatchUpdateRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getOrgId())) {
            throw new BusinessException("机构 ID 不能为空");
        }
        if (request.getEnabled() == null) {
            throw new BusinessException("权限状态不能为空");
        }
        if (request.getPersonIds() == null || request.getPersonIds().isEmpty()) {
            throw new BusinessException("人员 ID 列表不能为空");
        }
        if (request.getPersonIds().size() > 200) {
            throw new BusinessException("每次批量操作最多 200 人");
        }
    }

    private void validatePage(int current, int size) {
        if (current < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("每页数量必须在 1 到 100 之间");
        }
    }

    private Set<String> normalizePersonIds(List<String> personIds) {
        Set<String> result = new LinkedHashSet<String>();
        for (String personId : personIds) {
            if (!StringUtils.hasText(personId)) {
                throw new BusinessException("人员 ID 不能为空");
            }
            result.add(personId.trim());
        }
        return result;
    }

    private boolean containsKeyword(AiUserPermissionRecordView item, String keyword) {
        List<String> values = new ArrayList<String>();
        values.add(item.getPersonCd());
        values.add(item.getPersonName());
        values.add(item.getDepartmentName());
        values.add(item.getPersonTypeText());
        values.add(item.getTitleTypeText());
        return values.stream().anyMatch(value -> normalize(value).contains(keyword));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String identity(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred.trim()
            : (StringUtils.hasText(fallback) ? fallback.trim() : null);
    }

    private static class PermissionWriteResult {
        private final AiUserAiPermission permission;
        private final boolean changed;

        private PermissionWriteResult(AiUserAiPermission permission, boolean changed) {
            this.permission = permission;
            this.changed = changed;
        }
    }
}
