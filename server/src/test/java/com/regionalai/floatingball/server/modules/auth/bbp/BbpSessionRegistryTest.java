package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BbpSessionRegistryTest {

    @Test
    void reusesActiveSessionForTheSameBbpIdentity() {
        BbpSessionRegistry registry = new BbpSessionRegistry();
        BbpCookieJar cookies = new BbpCookieJar();
        registry.register("new-session", "tenant-1", cookies, System.currentTimeMillis() + 60000,
            role("tenant-1", "org-1", "user-1", "role-1"));

        BbpSessionRegistry.Session recovered = registry.requireSession(
            "old-session", "tenant-1", "org-1", "user-1", "role-1");

        assertSame(cookies, recovered.getCookies());
        assertSame(recovered, registry.requireSession("old-session"));
    }

    @Test
    void neverReusesSessionAcrossBbpIdentities() {
        BbpSessionRegistry registry = new BbpSessionRegistry();
        registry.register("new-session", "tenant-1", new BbpCookieJar(), System.currentTimeMillis() + 60000,
            role("tenant-1", "org-1", "user-1", "role-1"));

        assertThrows(BusinessException.class, () -> registry.requireSession(
            "old-session", "tenant-1", "org-2", "user-1", "role-1"));
        assertThrows(BusinessException.class, () -> registry.requireSession(
            "old-session", "tenant-1", "org-1", "user-2", "role-1"));
        assertThrows(BusinessException.class, () -> registry.requireSession(
            "old-session", "tenant-1", "org-1", "user-1", "role-2"));
    }

    @Test
    void expiredSessionCannotBeRecovered() {
        BbpSessionRegistry registry = new BbpSessionRegistry();
        registry.register("expired-session", "tenant-1", new BbpCookieJar(), System.currentTimeMillis() - 1,
            role("tenant-1", "org-1", "user-1", "role-1"));

        assertThrows(BusinessException.class, () -> registry.requireSession(
            "old-session", "tenant-1", "org-1", "user-1", "role-1"));
    }

    private BbpRoleView role(String tenantId, String orgId, String userId, String roleId) {
        BbpRoleView role = new BbpRoleView();
        role.setTenantId(tenantId);
        role.setOrgId(orgId);
        role.setUserId(userId);
        role.setRoleId(roleId);
        return role;
    }
}
