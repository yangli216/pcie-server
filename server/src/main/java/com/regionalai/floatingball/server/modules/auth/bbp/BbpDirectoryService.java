package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BbpDirectoryService {

    private final AdminAuthMode authMode;
    private final BbpSessionRegistry sessionRegistry;
    private final BbpHttpClient httpClient;
    private final BbpAdminScope adminScope;

    public BbpDirectoryService(AdminAuthMode authMode,
                               BbpSessionRegistry sessionRegistry,
                               BbpHttpClient httpClient,
                               BbpAdminScope adminScope) {
        this.authMode = authMode;
        this.sessionRegistry = sessionRegistry;
        this.httpClient = httpClient;
        this.adminScope = adminScope;
    }

    public List<BbpOrganizationView> organizations(AdminCurrentUser currentUser) {
        BbpSessionRegistry.Session session = requireSession(currentUser);
        List<BbpOrganizationView> organizations = httpClient.findOrganizations(session.getCookies(), session.getTenantId());
        if (adminScope.isSystemAdmin(currentUser)) {
            return organizations;
        }
        if (!StringUtils.hasText(currentUser.getBbpOrgId())) {
            throw new BusinessException("当前 BBP 登录角色缺少机构范围，无法查看机构目录");
        }
        String orgId = currentUser.getBbpOrgId().trim();
        return organizations.stream()
            .filter(item -> orgId.equals(item.getId()) || orgId.equals(item.getOrgId()))
            .collect(Collectors.toList());
    }

    public List<BbpPersonView> persons(AdminCurrentUser currentUser, String orgId) {
        BbpSessionRegistry.Session session = requireSession(currentUser);
        String permittedOrgId = adminScope.requireOrgAccess(currentUser, orgId);
        return httpClient.findPersons(session.getCookies(), permittedOrgId).stream()
            .filter(item -> belongsToTenant(item, session.getTenantId()))
            .filter(item -> !StringUtils.hasText(item.getOrgId()) || permittedOrgId.equals(item.getOrgId()))
            .collect(Collectors.toList());
    }

    public List<BbpPersonView> personsByDepartment(AdminCurrentUser currentUser, String deptId) {
        if (!StringUtils.hasText(deptId)) {
            throw new BusinessException("BBP部门 ID 不能为空");
        }
        BbpSessionRegistry.Session session = requireSession(currentUser);
        if (!adminScope.isSystemAdmin(currentUser)) {
            String orgId = adminScope.requireOrgAccess(currentUser, currentUser.getBbpOrgId());
            String normalizedDeptId = deptId.trim();
            return httpClient.findPersons(session.getCookies(), orgId).stream()
                .filter(item -> belongsToTenant(item, session.getTenantId()))
                .filter(item -> normalizedDeptId.equals(item.getDepartmentId()))
                .collect(Collectors.toList());
        }
        String normalizedDeptId = deptId.trim();
        return httpClient.findPersonsByDepartment(session.getCookies(), normalizedDeptId).stream()
            .filter(item -> belongsToTenant(item, session.getTenantId()))
            .filter(item -> !StringUtils.hasText(item.getDepartmentId())
                || normalizedDeptId.equals(item.getDepartmentId()))
            .collect(Collectors.toList());
    }

    public void logout(AdminCurrentUser currentUser) {
        if (currentUser != null) {
            sessionRegistry.removeSession(currentUser.getAuthSessionId());
        }
    }

    private BbpSessionRegistry.Session requireSession(AdminCurrentUser currentUser) {
        authMode.requireBbp();
        if (currentUser == null || !"BBP".equals(currentUser.getAuthProvider()) || !StringUtils.hasText(currentUser.getAuthSessionId())) {
            throw new BusinessException("当前登录不是有效的 BBP 会话");
        }
        return sessionRegistry.requireSession(currentUser.getAuthSessionId());
    }

    private boolean belongsToTenant(BbpPersonView person, String tenantId) {
        return person != null
            && (!StringUtils.hasText(person.getTenantId())
                || tenantId.equals(person.getTenantId().trim()));
    }
}
