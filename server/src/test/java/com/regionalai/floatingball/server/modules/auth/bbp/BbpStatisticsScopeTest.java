package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbpStatisticsScopeTest {

    private final BbpStatisticsScope scope = new BbpStatisticsScope();

    @Test
    void bbpOrganizationUserShouldResolveOwnOrganization() {
        BbpStatisticsScope.Scope resolved = scope.resolve(user("ORG_ANALYST"), null);

        assertTrue(resolved.isRestricted());
        assertEquals("ORG-A", resolved.getHisOrgId());
        assertEquals("第一医院", resolved.getHisOrgName());
    }

    @Test
    void bbpOrganizationUserShouldRejectOtherOrganization() {
        ForbiddenException exception = assertThrows(ForbiddenException.class,
            () -> scope.resolve(user("ORG_ADMIN"), "ORG-B"));

        assertEquals("AUTH-403", exception.getCode());
    }

    @Test
    void systemAdministratorShouldRemainUnrestricted() {
        BbpStatisticsScope.Scope resolved = scope.resolve(user("SYSTEM_ADMIN"), "ORG-B");

        assertFalse(resolved.isRestricted());
        assertEquals("ORG-B", resolved.getHisOrgId());
    }

    @Test
    void statisticsOnlyShouldExcludeAccountsWithAdministrativeRole() {
        assertTrue(scope.isStatisticsOnly(user("ORG_ANALYST")));

        AdminCurrentUser mixed = user("ORG_ANALYST");
        mixed.setRoles(Arrays.asList("ORG_ANALYST", "ORG_ADMIN"));
        assertFalse(scope.isStatisticsOnly(mixed));
    }

    @Test
    void organizationAnalystShouldReachXiaoshanStatisticsApis() {
        assertTrue(scope.isAllowedStatisticsApi("/admin/api/xiaoshan-analytics/function-modules"));
        assertTrue(scope.isAllowedStatisticsApi("/admin/api/xiaoshan-analytics/function-usage"));
        assertTrue(scope.isAllowedStatisticsApi("/admin/api/xiaoshan-analytics/function-usage/export"));
    }

    @Test
    void emrReviewerShouldOnlyReachEmrQualityControlApis() {
        AdminCurrentUser reviewer = user("EMR_QC_REVIEWER");

        assertTrue(scope.isRestrictedToAssignedFunctions(reviewer));
        assertTrue(scope.isAllowedRestrictedApi(reviewer, "/admin/api/emr-qc/records"));
        assertTrue(scope.isAllowedRestrictedApi(reviewer, "/admin/api/auth/me"));
        assertFalse(scope.isAllowedRestrictedApi(reviewer, "/admin/api/users"));
        assertFalse(scope.isAllowedRestrictedApi(reviewer, "/admin/api/analytics/summary"));
    }

    @Test
    void analystAndEmrReviewerShouldReceiveUnionWithoutAdministrativeApis() {
        AdminCurrentUser mixed = user("ORG_ANALYST");
        mixed.setRoles(Arrays.asList("ORG_ANALYST", "EMR_QC_REVIEWER"));

        assertTrue(scope.isRestrictedToAssignedFunctions(mixed));
        assertTrue(scope.isAllowedRestrictedApi(mixed, "/admin/api/analytics/summary"));
        assertTrue(scope.isAllowedRestrictedApi(mixed, "/admin/api/emr-qc/session"));
        assertFalse(scope.isAllowedRestrictedApi(mixed, "/admin/api/configs"));
        assertTrue(scope.canAccessEmrQc(mixed));
    }

    @Test
    void emrQualityControlAccessRequiresBbpAndAnAuthorizedRole() {
        assertTrue(scope.canAccessEmrQc(user("SYSTEM_ADMIN")));
        assertTrue(scope.canAccessEmrQc(user("ORG_ADMIN")));
        assertTrue(scope.canAccessEmrQc(user("EMR_QC_REVIEWER")));
        assertFalse(scope.canAccessEmrQc(user("ORG_ANALYST")));

        AdminCurrentUser local = user("EMR_QC_REVIEWER");
        local.setAuthProvider("LOCAL");
        assertFalse(scope.canAccessEmrQc(local));
    }

    private AdminCurrentUser user(String role) {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setBbpOrgId("ORG-A");
        user.setBbpOrgName("第一医院");
        user.setRoles(Collections.singletonList(role));
        return user;
    }
}
