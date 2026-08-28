package com.regionalai.floatingball.server.modules.auth.bbp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessUpdateRequest;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessView;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BbpAdminAccessService {

    public static final String ORG_ADMIN = "ORG_ADMIN";
    public static final String ORG_ANALYST = "ORG_ANALYST";

    private static final Logger log = LoggerFactory.getLogger(BbpAdminAccessService.class);

    private final BbpAdminGrantMapper grantMapper;
    private final BbpAdminGrantSchemaService schemaService;
    private final BbpDirectoryService directoryService;
    private final BbpAdminScope adminScope;
    private final BbpAdminAccessAuditService auditService;

    public BbpAdminAccessService(BbpAdminGrantMapper grantMapper,
                                 BbpAdminGrantSchemaService schemaService,
                                 BbpDirectoryService directoryService,
                                 BbpAdminScope adminScope,
                                 BbpAdminAccessAuditService auditService) {
        this.grantMapper = grantMapper;
        this.schemaService = schemaService;
        this.directoryService = directoryService;
        this.adminScope = adminScope;
        this.auditService = auditService;
    }

    public List<String> findActiveRoleCodes(BbpRoleView bbpRole) {
        if (bbpRole == null || !StringUtils.hasText(bbpRole.getTenantId())
            || !StringUtils.hasText(bbpRole.getOrgId()) || !StringUtils.hasText(bbpRole.getUserId())
            || !schemaService.isReady()) {
            return Collections.emptyList();
        }
        try {
            return grantMapper.selectList(identityQuery(bbpRole.getTenantId(), bbpRole.getOrgId())
                    .eq(BbpAdminGrant::getBbpUserId, bbpRole.getUserId().trim())
                    .eq(BbpAdminGrant::getSdStatus, "1"))
                .stream()
                .map(BbpAdminGrant::getRoleCode)
                .filter(this::isSupportedRole)
                .distinct()
                .collect(Collectors.toList());
        } catch (RuntimeException ex) {
            log.warn("BBP admin grant lookup failed; denying PCIE admin access. tenantId={}, orgId={}, bbpUserId={}",
                bbpRole.getTenantId(), bbpRole.getOrgId(), bbpRole.getUserId());
            return Collections.emptyList();
        }
    }

    public List<BbpAdminAccessView> list(AdminCurrentUser currentUser,
                                         String requestedOrgId,
                                         String requestedRoleCode) {
        adminScope.requireSystemAdmin(currentUser);
        schemaService.requireReady();
        String tenantId = adminScope.requireTenantId(currentUser);
        String orgId = adminScope.requireOrgAccess(currentUser, requestedOrgId);
        String roleCode = normalizeRoleCode(requestedRoleCode);
        List<BbpPersonView> persons = directoryService.persons(currentUser, orgId);
        List<BbpAdminGrant> grants = grantMapper.selectList(roleQuery(tenantId, orgId, roleCode));

        Map<String, BbpAdminGrant> grantByUserId = grants.stream()
            .collect(Collectors.toMap(BbpAdminGrant::getBbpUserId, item -> item, (left, right) -> left,
                LinkedHashMap::new));
        List<BbpAdminAccessView> result = new ArrayList<BbpAdminAccessView>();
        for (BbpPersonView person : persons) {
            if (!StringUtils.hasText(person.getUserId())) {
                continue;
            }
            result.add(toView(tenantId, orgId, roleCode, person, grantByUserId.remove(person.getUserId())));
        }
        for (BbpAdminGrant grant : grantByUserId.values()) {
            if ("1".equals(grant.getSdStatus())) {
                result.add(toView(tenantId, orgId, roleCode, null, grant));
            }
        }
        result.sort(Comparator
            .comparing(BbpAdminAccessView::isGrantRisk).reversed()
            .thenComparing(Comparator.comparing(BbpAdminAccessView::isAdminAccessEnabled).reversed())
            .thenComparing(item -> text(item.getPersonName(), item.getLoginName(), item.getBbpUserId())));
        return result;
    }

    @Transactional
    public BbpAdminAccessView update(AdminCurrentUser currentUser,
                                     String bbpUserId,
                                     BbpAdminAccessUpdateRequest request) {
        adminScope.requireSystemAdmin(currentUser);
        schemaService.requireReady();
        if (!StringUtils.hasText(bbpUserId)) {
            throw new BusinessException("BBP 用户 ID 不能为空");
        }
        String tenantId = adminScope.requireTenantId(currentUser);
        String orgId = adminScope.requireOrgAccess(currentUser, request.getOrgId());
        String roleCode = normalizeRoleCode(request.getRoleCode());
        String normalizedUserId = bbpUserId.trim();
        BbpAdminGrant existing = findGrant(tenantId, orgId, normalizedUserId, roleCode);
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());

        BbpPersonView person = null;
        if (enabled || existing == null) {
            person = directoryService.persons(currentUser, orgId).stream()
                .filter(item -> normalizedUserId.equals(item.getUserId()))
                .findFirst()
                .orElse(null);
        }
        if (enabled && person == null) {
            throw new BusinessException("目标 BBP 用户不属于当前机构人员目录");
        }
        if (enabled && !Boolean.TRUE.equals(person.getActive())) {
            throw new BusinessException("仅 BBP 明确处于启用状态的人员可以授予 PCIE 后台访问权限");
        }
        if (!enabled && existing == null) {
            throw new BusinessException("目标用户当前没有可撤销的 PCIE 后台访问权限");
        }
        if (existing != null && enabled == "1".equals(existing.getSdStatus())) {
            return toView(tenantId, orgId, roleCode, person, existing);
        }

        BbpAdminGrant grant = existing == null ? new BbpAdminGrant() : existing;
        grant.setTenantId(tenantId);
        grant.setOrgId(orgId);
        grant.setBbpUserId(normalizedUserId);
        grant.setRoleCode(roleCode);
        grant.setSdStatus(enabled ? "1" : "0");
        grant.setOperatorUserId(currentUser.getIdUser());
        grant.setOperatorUserName(text(currentUser.getNaUser(), currentUser.getCdUser()));
        grant.setFgActive("1");
        if (person != null) {
            grant.setOrgName(person.getOrgName());
            grant.setPersonId(text(person.getPersonId(), person.getId()));
            grant.setLoginName(person.getLoginName());
            grant.setPersonName(person.getName());
        }
        try {
            if (existing == null) {
                grantMapper.insert(grant);
            } else {
                grantMapper.updateById(grant);
            }
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("BBP-ADMIN-GRANT-CONFLICT", "后台访问权限已被其他管理员更新，请刷新后重试");
        }
        auditService.record(currentUser, grant, enabled);
        return toView(tenantId, orgId, roleCode, person, grant);
    }

    private LambdaQueryWrapper<BbpAdminGrant> identityQuery(String tenantId, String orgId) {
        return new LambdaQueryWrapper<BbpAdminGrant>()
            .eq(BbpAdminGrant::getTenantId, tenantId.trim())
            .eq(BbpAdminGrant::getOrgId, orgId.trim())
            .eq(BbpAdminGrant::getFgActive, "1");
    }

    private LambdaQueryWrapper<BbpAdminGrant> roleQuery(String tenantId, String orgId, String roleCode) {
        return identityQuery(tenantId, orgId).eq(BbpAdminGrant::getRoleCode, roleCode);
    }

    private BbpAdminGrant findGrant(String tenantId, String orgId, String bbpUserId, String roleCode) {
        return grantMapper.selectOne(roleQuery(tenantId, orgId, roleCode)
            .eq(BbpAdminGrant::getBbpUserId, bbpUserId));
    }

    private BbpAdminAccessView toView(String tenantId,
                                      String orgId,
                                      String roleCode,
                                      BbpPersonView person,
                                      BbpAdminGrant grant) {
        BbpAdminAccessView view = new BbpAdminAccessView();
        view.setTenantId(tenantId);
        view.setOrgId(orgId);
        view.setOrgName(person != null && StringUtils.hasText(person.getOrgName())
            ? person.getOrgName() : grant == null ? null : grant.getOrgName());
        view.setBbpUserId(person != null ? person.getUserId() : grant.getBbpUserId());
        view.setPersonId(person != null ? text(person.getPersonId(), person.getId())
            : grant == null ? null : grant.getPersonId());
        view.setLoginName(person != null ? person.getLoginName() : grant == null ? null : grant.getLoginName());
        view.setPersonCode(person == null ? null : person.getCode());
        view.setPersonName(person != null ? person.getName() : grant == null ? null : grant.getPersonName());
        view.setDepartmentId(person == null ? null : person.getDepartmentId());
        view.setDepartmentName(person == null ? null : person.getDepartmentName());
        view.setPersonType(person == null ? null : person.getPersonType());
        view.setPersonTypeText(person == null ? null : person.getPersonTypeText());
        view.setTitleType(person == null ? null : person.getTitleType());
        view.setTitleTypeText(person == null ? null : person.getTitleTypeText());
        view.setDirectoryPresent(person != null);
        view.setActive(person == null ? null : person.getActive());
        boolean enabled = grant != null && "1".equals(grant.getSdStatus());
        view.setAdminAccessEnabled(enabled);
        view.setRoleCode(grant == null ? roleCode : grant.getRoleCode());
        view.setGrantRisk(enabled && (person == null || !Boolean.TRUE.equals(person.getActive())));
        if (grant != null) {
            view.setGrantId(grant.getIdGrant());
            view.setOperatorUserName(grant.getOperatorUserName());
            view.setUpdateTime(grant.getUpdateTime());
        }
        return view;
    }

    private String normalizeRoleCode(String roleCode) {
        String normalized = StringUtils.hasText(roleCode) ? roleCode.trim().toUpperCase() : ORG_ADMIN;
        if (!isSupportedRole(normalized)) {
            throw new BusinessException("仅支持配置 ORG_ADMIN 或 ORG_ANALYST 后台角色");
        }
        return normalized;
    }

    private boolean isSupportedRole(String roleCode) {
        return ORG_ADMIN.equals(roleCode) || ORG_ANALYST.equals(roleCode);
    }

    private String text(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }
}
