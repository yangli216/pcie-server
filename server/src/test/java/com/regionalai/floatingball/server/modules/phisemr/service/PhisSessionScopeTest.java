package com.regionalai.floatingball.server.modules.phisemr.service;

import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpCookieJar;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpSessionRegistry;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhisSessionScopeTest {

    @Mock
    private AdminAuthMode authMode;

    private BbpSessionRegistry registry;
    private PhisSessionScope scope;

    @BeforeEach
    void setUp() {
        registry = new BbpSessionRegistry();
        scope = new PhisSessionScope(authMode, registry, new BbpStatisticsScope());
    }

    @Test
    void nonBbpAuthModeIsForbidden() {
        when(authMode.isBbp()).thenReturn(false);

        ForbiddenException exception = assertThrows(ForbiddenException.class,
            () -> scope.require(user("local", null)));

        assertEquals("AUTH-403", exception.getCode());
    }

    @Test
    void missingSessionReturnsStableConflictCode() {
        when(authMode.isBbp()).thenReturn(true);

        ConflictException exception = assertThrows(ConflictException.class,
            () -> scope.require(user("BBP", "missing-session")));

        assertEquals("EMR-PHIS-SESSION", exception.getCode());

        PhisSessionScope.Scope described = scope.describe(user("BBP", "missing-session"));
        assertFalse(described.isAvailable());
        assertEquals("EMR-PHIS-SESSION", described.getUnavailableCode());
    }

    @Test
    void roleIsNotRestrictedToOutpatientDoctor() {
        when(authMode.isBbp()).thenReturn(true);
        registry.register("s1", "tenant", new BbpCookieJar(), System.currentTimeMillis() + 60000,
            role("住院医生", "ZY01", "4046", "1188"));

        PhisSessionScope.Scope resolved = scope.require(user("BBP", "s1"));

        assertTrue(resolved.isAvailable());
        assertEquals("4046", resolved.getHisOrgId());
        assertEquals("住院医生", resolved.getRoleName());
    }

    @Test
    void missingRoleFallsBackToLoggedInOrgAndLetsTokenDecideScope() {
        when(authMode.isBbp()).thenReturn(true);
        registry.register("s1", "tenant", new BbpCookieJar(), System.currentTimeMillis() + 60000, null);

        AdminCurrentUser currentUser = user("BBP", "s1");
        currentUser.setBbpOrgId("BBP-ORG-1");
        currentUser.setBbpOrgName("萧山区第一人民医院");

        PhisSessionScope.Scope resolved = scope.require(currentUser);

        assertTrue(resolved.isAvailable());
        assertEquals("BBP-ORG-1", resolved.getHisOrgId());
        assertEquals("萧山区第一人民医院", resolved.getHisOrgName());
    }

    @Test
    void statisticsOnlyUserIsForbiddenEvenWithOutpatientRole() {
        when(authMode.isBbp()).thenReturn(true);
        registry.register("s1", "tenant", new BbpCookieJar(), System.currentTimeMillis() + 60000,
            role("门诊医生", "MZ01", "4046", "1188"));

        AdminCurrentUser statisticsUser = user("BBP", "s1");
        statisticsUser.setRoles(Arrays.asList("ORG_ANALYST"));

        ForbiddenException exception = assertThrows(ForbiddenException.class,
            () -> scope.require(statisticsUser));

        assertEquals("AUTH-403", exception.getCode());
    }

    @Test
    void analystWithEmrReviewerRoleCanAccessOutpatientQualityControl() {
        when(authMode.isBbp()).thenReturn(true);
        registry.register("s1", "tenant", new BbpCookieJar(), System.currentTimeMillis() + 60000,
            role("门诊医生", "MZ01", "4046", "1188"));

        AdminCurrentUser mixedUser = user("BBP", "s1");
        mixedUser.setRoles(Arrays.asList("ORG_ANALYST", "EMR_QC_REVIEWER"));

        assertTrue(scope.require(mixedUser).isAvailable());
    }

    @Test
    void availableScopeUsesRoleOrgCodeDepartmentAndSessionExpiry() {
        when(authMode.isBbp()).thenReturn(true);
        long expiresAt = System.currentTimeMillis() + 60000;
        registry.register("s1", "tenant", new BbpCookieJar(), expiresAt,
            role("门诊医生", "MZ01", "4046", "1188"));

        PhisSessionScope.Scope resolved = scope.require(user("BBP", "s1"));

        assertTrue(resolved.isAvailable());
        assertEquals("4046", resolved.getHisOrgId());
        assertEquals("1188", resolved.getDeptId());
        assertEquals("门诊内科", resolved.getDeptName());
        assertEquals("张三", resolved.getDoctorName());
        assertEquals("门诊医生", resolved.getRoleName());
        assertTrue(resolved.getSessionExpiresAt() != null);
    }

    @Test
    void oldTokenReusesSessionReestablishedByTheSameBbpIdentity() {
        when(authMode.isBbp()).thenReturn(true);
        BbpRoleView role = role("门诊医生", "MZ01", "4046", "1188");
        role.setTenantId("tenant");
        role.setUserId("user-1");
        role.setRoleId("role-1");
        registry.register("new-session", "tenant", new BbpCookieJar(), System.currentTimeMillis() + 60000, role);

        AdminCurrentUser currentUser = user("BBP", "old-session");
        currentUser.setBbpTenantId("tenant");
        currentUser.setBbpOrgId(role.getOrgId());
        currentUser.setBbpUserId("user-1");
        currentUser.setBbpRoleId("role-1");

        assertTrue(scope.require(currentUser).isAvailable());
    }

    private AdminCurrentUser user(String provider, String sessionId) {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider(provider);
        user.setAuthSessionId(sessionId);
        user.setIdOrg("ORG001");
        user.setNaUser("本地管理员");
        user.setRoles(Arrays.asList("SYSTEM_ADMIN"));
        return user;
    }

    private BbpRoleView role(String roleName, String roleCode, String orgCode, String deptId) {
        BbpRoleView role = new BbpRoleView();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setOrgCode(orgCode);
        role.setOrgId("org-" + orgCode);
        role.setOrgName("萧山区第一人民医院");
        role.setDepartmentId(deptId);
        role.setDepartmentName("门诊内科");
        role.setUserName("张三");
        return role;
    }
}
