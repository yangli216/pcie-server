package com.regionalai.floatingball.server.modules.auth.config;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminAuthModeTest {

    @Test
    void localModeShouldAllowDirectoryWrites() {
        AdminAuthMode authMode = new AdminAuthMode(new AdminSecurityProperties());

        assertDoesNotThrow(authMode::requireDirectoryWritable);
    }

    @Test
    void bbpModeShouldRejectDirectoryWrites() {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.getAuth().setMode("bbp");
        properties.getAuth().getBbp().setBaseUrl("https://phis.example/phis");
        AdminAuthMode authMode = new AdminAuthMode(properties);

        BusinessException exception = assertThrows(BusinessException.class, authMode::requireDirectoryWritable);

        assertEquals("BBP模式下机构和人员由 PHIS 统一维护，仅允许查看", exception.getMessage());
    }

    @Test
    void systemTenantAdministratorShouldMapToLocalAdmin() {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        AdminAuthMode authMode = new AdminAuthMode(properties);

        assertEquals("admin", authMode.resolveLocalUsername("system", "tenantSystem"));
        assertEquals("doctor001", authMode.resolveLocalUsername("doctor001", "orgAdmin"));
    }

    @Test
    void systemShouldNotMapToAdminWithOrdinaryRole() {
        AdminAuthMode authMode = new AdminAuthMode(new AdminSecurityProperties());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> authMode.resolveLocalUsername("system", "ordinary"));

        assertEquals("BBP system 账号必须使用租户管理员身份", exception.getMessage());
    }
}
