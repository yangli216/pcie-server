package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.AdminAuthCapabilities;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginResponse;
import com.regionalai.floatingball.server.modules.auth.dto.BbpLoginRequest;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import com.regionalai.floatingball.server.modules.auth.service.AdminAuthService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BbpAuthService {

    private final AdminAuthMode authMode;
    private final BbpHttpClient httpClient;
    private final BbpSessionRegistry sessionRegistry;
    private final AdminAuthService adminAuthService;
    private final BbpAdminAccessService adminAccessService;

    public BbpAuthService(AdminAuthMode authMode,
                          BbpHttpClient httpClient,
                          BbpSessionRegistry sessionRegistry,
                          AdminAuthService adminAuthService,
                          BbpAdminAccessService adminAccessService) {
        this.authMode = authMode;
        this.httpClient = httpClient;
        this.sessionRegistry = sessionRegistry;
        this.adminAuthService = adminAuthService;
        this.adminAccessService = adminAccessService;
    }

    public AdminAuthCapabilities capabilities() {
        AdminAuthCapabilities result = new AdminAuthCapabilities();
        result.setMode(authMode.mode());
        result.setBbpEnabled(authMode.isBbp());
        result.setTenantId(authMode.configuredTenantId());
        result.setTenantRequired(authMode.isBbp() && !StringUtils.hasText(result.getTenantId()));
        return result;
    }

    public AdminLoginResponse login(BbpLoginRequest request) {
        authMode.requireBbp();
        if (request == null || !StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            throw new BusinessException("BBP账号或密码不能为空");
        }
        String username = request.getUsername().trim();
        String tenantId = authMode.resolveTenantId(request.getTenantId());
        BbpHttpClient.RoleResult roleResult = httpClient.findRoles(username, request.getPassword(), tenantId);
        BbpRoleView role = selectAuthorizedIdentity(username, roleResult.getRoles());

        httpClient.establishSession(roleResult.getCookies(), role.getAuthorizationId(), role.getDepartmentAuthorizationId());
        String sessionId = UUID.randomUUID().toString();
        AdminLoginResponse response = adminAuthService.loginWithBbp(username, role, sessionId);
        sessionRegistry.register(sessionId, tenantId, roleResult.getCookies(), response.getExpiresAt());
        return response;
    }

    private BbpRoleView selectAuthorizedIdentity(String username, List<BbpRoleView> roles) {
        if (authMode.isSystemLoginName(username)) {
            return roles.stream()
                .filter(item -> "tenantSystem".equals(item.getRoleCode()))
                .sorted(roleComparator())
                .findFirst()
                .orElseThrow(() -> new BusinessException("BBP system 账号未返回租户管理员身份"));
        }

        Map<String, List<BbpRoleView>> authorizedIdentities = new LinkedHashMap<String, List<BbpRoleView>>();
        Map<String, List<String>> grantCache = new LinkedHashMap<String, List<String>>();
        for (BbpRoleView role : roles) {
            String identityKey = identityKey(role);
            if (identityKey == null) {
                continue;
            }
            List<String> grantedRoles = grantCache.get(identityKey);
            if (grantedRoles == null) {
                grantedRoles = adminAccessService.findActiveRoleCodes(role);
                grantCache.put(identityKey, grantedRoles);
            }
            if (!grantedRoles.isEmpty()) {
                authorizedIdentities.computeIfAbsent(identityKey, key -> new ArrayList<BbpRoleView>()).add(role);
            }
        }
        if (authorizedIdentities.isEmpty()) {
            throw new BusinessException("BBP账号认证成功，但尚未获得 PCIE 后台访问权限");
        }
        if (authorizedIdentities.size() > 1) {
            throw new BusinessException("BBP-LOGIN-MULTIPLE-AUTHORIZED-SCOPES",
                "当前账号在多个机构获得 PCIE 后台权限，无法自动确定登录机构，请联系系统管理员收敛授权");
        }
        return authorizedIdentities.values().iterator().next().stream()
            .sorted(roleComparator())
            .findFirst()
            .orElseThrow(() -> new BusinessException("BBP账号认证成功，但未找到可用的授权身份"));
    }

    private String identityKey(BbpRoleView role) {
        if (role == null || !StringUtils.hasText(role.getTenantId())
            || !StringUtils.hasText(role.getOrgId()) || !StringUtils.hasText(role.getUserId())) {
            return null;
        }
        return role.getTenantId().trim() + '\u0000' + role.getOrgId().trim() + '\u0000' + role.getUserId().trim();
    }

    private Comparator<BbpRoleView> roleComparator() {
        return Comparator
            .comparing((BbpRoleView item) -> text(item.getAuthorizationId()))
            .thenComparing(item -> text(item.getRoleCode()))
            .thenComparing(item -> text(item.getDepartmentAuthorizationId()));
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
