package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.config.AdminSecurityProperties;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginResponse;
import com.regionalai.floatingball.server.modules.auth.dto.BbpLoginRequest;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import com.regionalai.floatingball.server.modules.auth.service.AdminAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BbpAuthServiceTest {

    @Mock
    private BbpHttpClient httpClient;

    @Mock
    private BbpSessionRegistry sessionRegistry;

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private BbpAdminAccessService adminAccessService;

    private BbpAuthService service;

    @BeforeEach
    void setUp() {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.getAuth().setMode("bbp");
        properties.getAuth().getBbp().setBaseUrl("https://phis.example/phis");
        properties.getAuth().getBbp().setTenantId("xiaoshan");
        service = new BbpAuthService(new AdminAuthMode(properties), httpClient, sessionRegistry,
            adminAuthService, adminAccessService);
    }

    @Test
    void loginShouldAutomaticallyUseOnePcieAuthorizedIdentity() {
        BbpCookieJar cookies = new BbpCookieJar();
        BbpRoleView first = role("AUTH-Z", "doctor", "ORG001", "USER001");
        BbpRoleView selected = role("AUTH-A", "reportDoctor", "ORG001", "USER001");
        when(httpClient.findRoles("ZH0006", "secret", "xiaoshan"))
            .thenReturn(new BbpHttpClient.RoleResult(cookies, Arrays.asList(first, selected)));
        when(adminAccessService.findActiveRoleCodes(first))
            .thenReturn(Collections.singletonList(BbpAdminAccessService.ORG_ANALYST));
        AdminLoginResponse expected = new AdminLoginResponse();
        expected.setExpiresAt(1770000000000L);
        when(adminAuthService.loginWithBbp(eq("ZH0006"), same(selected), anyString())).thenReturn(expected);

        AdminLoginResponse actual = service.login(request("ZH0006"));

        assertSame(expected, actual);
        verify(httpClient).establishSession(cookies, "AUTH-A", null);
        verify(adminAccessService).findActiveRoleCodes(first);
        verify(adminAccessService, never()).findActiveRoleCodes(selected);
        verify(sessionRegistry).register(anyString(), eq("xiaoshan"), same(cookies), eq(1770000000000L));
    }

    @Test
    void loginShouldRejectMultiplePcieAuthorizedInstitutions() {
        BbpRoleView first = role("AUTH-A", "doctor", "ORG001", "USER001");
        BbpRoleView second = role("AUTH-B", "doctor", "ORG002", "USER001");
        when(httpClient.findRoles("ZH0006", "secret", "xiaoshan"))
            .thenReturn(new BbpHttpClient.RoleResult(new BbpCookieJar(), Arrays.asList(first, second)));
        when(adminAccessService.findActiveRoleCodes(first))
            .thenReturn(Collections.singletonList(BbpAdminAccessService.ORG_ANALYST));
        when(adminAccessService.findActiveRoleCodes(second))
            .thenReturn(Collections.singletonList(BbpAdminAccessService.ORG_ADMIN));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.login(request("ZH0006")));

        assertEquals("BBP-LOGIN-MULTIPLE-AUTHORIZED-SCOPES", exception.getCode());
        assertEquals("当前账号在多个机构获得 PCIE 后台权限，无法自动确定登录机构，请联系系统管理员收敛授权",
            exception.getMessage());
        verify(httpClient, never()).establishSession(org.mockito.ArgumentMatchers.any(), anyString(), nullable(String.class));
        verifyNoInteractions(adminAuthService, sessionRegistry);
    }

    @Test
    void loginShouldRejectIdentityWithoutPcieGrant() {
        BbpRoleView role = role("AUTH-A", "doctor", "ORG001", "USER001");
        when(httpClient.findRoles("ZH0006", "secret", "xiaoshan"))
            .thenReturn(new BbpHttpClient.RoleResult(new BbpCookieJar(), Collections.singletonList(role)));
        when(adminAccessService.findActiveRoleCodes(role)).thenReturn(Collections.emptyList());

        BusinessException exception = assertThrows(BusinessException.class, () -> service.login(request("ZH0006")));

        assertEquals("BBP账号认证成功，但尚未获得 PCIE 后台访问权限", exception.getMessage());
        verifyNoInteractions(adminAuthService, sessionRegistry);
    }

    @Test
    void systemShouldAutomaticallyUseTenantSystemIdentity() {
        BbpCookieJar cookies = new BbpCookieJar();
        BbpRoleView ordinary = role("AUTH-A", "doctor", "ORG001", "SYSTEM");
        BbpRoleView tenantSystem = role("AUTH-SYSTEM", "tenantSystem", null, "SYSTEM");
        when(httpClient.findRoles("system", "secret", "xiaoshan"))
            .thenReturn(new BbpHttpClient.RoleResult(cookies, Arrays.asList(ordinary, tenantSystem)));
        AdminLoginResponse expected = new AdminLoginResponse();
        expected.setExpiresAt(1770000000000L);
        when(adminAuthService.loginWithBbp(eq("system"), same(tenantSystem), anyString())).thenReturn(expected);

        assertSame(expected, service.login(request("system")));

        verify(httpClient).establishSession(cookies, "AUTH-SYSTEM", null);
        verifyNoInteractions(adminAccessService);
    }

    private BbpLoginRequest request(String username) {
        BbpLoginRequest request = new BbpLoginRequest();
        request.setUsername(username);
        request.setPassword("secret");
        return request;
    }

    private BbpRoleView role(String authorizationId, String roleCode, String orgId, String userId) {
        BbpRoleView role = new BbpRoleView();
        role.setAuthorizationId(authorizationId);
        role.setRoleCode(roleCode);
        role.setTenantId("xiaoshan");
        role.setOrgId(orgId);
        role.setUserId(userId);
        return role;
    }
}
