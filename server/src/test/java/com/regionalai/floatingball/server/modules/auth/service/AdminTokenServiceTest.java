package com.regionalai.floatingball.server.modules.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.util.AesUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminTokenServiceTest {

    @Test
    void bbpOrganizationScopeShouldSurviveTokenRoundTrip() {
        AdminTokenService tokenService = new AdminTokenService(
            new AesUtils("1234567890abcdef"), new ObjectMapper());
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("BBP-USER-1");
        user.setCdUser("doctor01");
        user.setNaUser("机构管理员");
        user.setRoles(Collections.singletonList("ORG_ADMIN"));
        user.setAuthProvider("BBP");
        user.setAuthSessionId("SESSION-1");
        user.setBbpTenantId("TENANT-A");
        user.setBbpUserId("BBP-USER-1");
        user.setBbpRoleId("BBP-ROLE-1");
        user.setBbpOrgId("ORG-A");
        user.setBbpOrgCode("ORG-CODE-A");
        user.setBbpOrgName("市第二人民医院");

        AdminCurrentUser parsed = tokenService.parse(tokenService.issue(user).getToken());

        assertNotNull(parsed);
        assertEquals("TENANT-A", parsed.getBbpTenantId());
        assertEquals("ORG-A", parsed.getBbpOrgId());
        assertEquals("ORG-CODE-A", parsed.getBbpOrgCode());
        assertEquals("市第二人民医院", parsed.getBbpOrgName());
        assertEquals(Collections.singletonList("ORG_ADMIN"), parsed.getRoles());
    }
}
