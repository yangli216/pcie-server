package com.regionalai.floatingball.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.service.AdminTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAuthFilterTest {

    @Mock
    private AdminTokenService adminTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedAdminController())
            .addFilters(new AdminAuthFilter(adminTokenService, new ObjectMapper(), new BbpStatisticsScope()))
            .build();
    }

    @AfterEach
    void tearDown() {
        AdminContextHolder.clear();
    }

    @Test
    void protectedRouteShouldReturnUnauthorizedWhenTokenMissing() throws Exception {
        mockMvc.perform(get("/admin/api/stats/overview")
                .header("X-Request-Id", "RID-filter-missing"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("AUTH-401"))
            .andExpect(jsonPath("$.requestId").value("RID-filter-missing"))
            .andExpect(jsonPath("$.message").value("缺少管理员令牌"));
    }

    @Test
    void loginRouteShouldBypassFilter() throws Exception {
        mockMvc.perform(post("/admin/api/auth/login"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        verifyNoInteractions(adminTokenService);
    }

    @Test
    void bbpLoginRouteShouldBypassFilter() throws Exception {
        mockMvc.perform(post("/admin/api/auth/bbp/login"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        verifyNoInteractions(adminTokenService);
    }

    @Test
    void adminStaticRouteShouldBypassFilter() throws Exception {
        mockMvc.perform(get("/admin/"))
            .andExpect(status().isOk())
            .andExpect(content().string("admin-index"));

        verifyNoInteractions(adminTokenService);
    }

    @Test
    void protectedRouteShouldPassWhenTokenValid() throws Exception {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("USER001");
        user.setCdUser("admin");
        user.setNaUser("系统管理员");
        user.setRoles(Collections.singletonList("SYSTEM_ADMIN"));

        when(adminTokenService.parse("valid-token")).thenReturn(user);
        mockMvc.perform(get("/admin/api/stats/overview")
                .header("Authorization", "Bearer valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cdUser").value("admin"))
            .andExpect(jsonPath("$.roles[0]").value("SYSTEM_ADMIN"));
    }

    @Test
    void protectedRouteShouldRejectInvalidToken() throws Exception {
        when(adminTokenService.parse("bad-token")).thenReturn(null);

        mockMvc.perform(get("/admin/api/stats/overview")
                .header("Authorization", "Bearer bad-token")
                .header("X-Request-Id", "RID-filter-invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH-401"))
            .andExpect(jsonPath("$.requestId").value("RID-filter-invalid"))
            .andExpect(jsonPath("$.message").value("管理员令牌无效或已过期"));
    }

    @Test
    void organizationAnalystShouldOnlyReachStatisticsApis() throws Exception {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setBbpUserId("USER-ANALYST");
        user.setBbpOrgId("ORG-A");
        user.setRoles(Collections.singletonList("ORG_ANALYST"));
        when(adminTokenService.parse("analyst-token")).thenReturn(user);

        mockMvc.perform(get("/admin/api/analytics/summary")
                .header("Authorization", "Bearer analyst-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-usage")
                .header("Authorization", "Bearer analyst-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(get("/admin/api/stats/overview")
                .header("Authorization", "Bearer analyst-token")
                .header("X-Request-Id", "RID-analyst-forbidden"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH-403"))
            .andExpect(jsonPath("$.requestId").value("RID-analyst-forbidden"));
    }

    @RestController
    static class ProtectedAdminController {

        @GetMapping("/admin/api/stats/overview")
        public Map<String, Object> overview() {
            AdminCurrentUser currentUser = AdminContextHolder.get();
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("cdUser", currentUser == null ? null : currentUser.getCdUser());
            result.put("roles", currentUser == null ? Collections.emptyList() : currentUser.getRoles());
            return result;
        }

        @GetMapping("/admin/api/analytics/summary")
        public Map<String, String> analyticsSummary() {
            return Collections.singletonMap("status", "ok");
        }

        @GetMapping("/admin/api/xiaoshan-analytics/function-usage")
        public Map<String, String> xiaoshanFunctionUsage() {
            return Collections.singletonMap("status", "ok");
        }

        @PostMapping("/admin/api/auth/login")
        public Map<String, String> login() {
            return Collections.singletonMap("status", "ok");
        }

        @PostMapping("/admin/api/auth/bbp/login")
        public Map<String, String> bbpLogin() {
            return Collections.singletonMap("status", "ok");
        }

        @GetMapping("/admin/")
        public String adminIndex() {
            return "admin-index";
        }
    }
}
