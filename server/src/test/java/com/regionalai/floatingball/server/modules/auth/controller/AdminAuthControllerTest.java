package com.regionalai.floatingball.server.modules.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginResponse;
import com.regionalai.floatingball.server.modules.auth.service.AdminAuthService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAuthService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAuthControllerTest {

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private BbpAuthService bbpAuthService;

    @Mock
    private BbpDirectoryService bbpDirectoryService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAuthController(adminAuthService, bbpAuthService, bbpDirectoryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        AdminContextHolder.clear();
    }

    @Test
    void loginShouldReturnWrappedTokenPayload() throws Exception {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("USER001");
        user.setCdUser("admin");
        user.setNaUser("系统管理员");
        user.setIdOrg("ORG001");
        user.setRoles(Collections.singletonList("SYSTEM_ADMIN"));

        AdminLoginResponse response = new AdminLoginResponse();
        response.setToken("encrypted-token");
        response.setExpiresAt(1770000000000L);
        response.setUser(user);

        when(adminAuthService.login(any())).thenReturn(response);

        mockMvc.perform(post("/admin/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-auth-login")
                .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-auth-login"))
            .andExpect(jsonPath("$.data.token").value("encrypted-token"))
            .andExpect(jsonPath("$.data.expiresAt").value(1770000000000L))
            .andExpect(jsonPath("$.data.user.cdUser").value("admin"))
            .andExpect(jsonPath("$.data.user.roles[0]").value("SYSTEM_ADMIN"));
    }

    @Test
    void loginShouldRejectBlankUsername() throws Exception {
        mockMvc.perform(post("/admin/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-auth-validate")
                .content("{\"username\":\"\",\"password\":\"admin123\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-001"))
            .andExpect(jsonPath("$.requestId").value("RID-auth-validate"))
            .andExpect(jsonPath("$.message").value("管理员账号不能为空"));
    }

    @Test
    void bbpLoginShouldAcceptCredentialsWithoutRoleSelection() throws Exception {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setCdUser("ZH0006");
        user.setAuthProvider("BBP");
        user.setRoles(Collections.singletonList("ORG_ANALYST"));
        AdminLoginResponse response = new AdminLoginResponse();
        response.setToken("bbp-token");
        response.setExpiresAt(1770000000000L);
        response.setUser(user);
        when(bbpAuthService.login(any())).thenReturn(response);

        mockMvc.perform(post("/admin/api/auth/bbp/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-bbp-login")
                .content("{\"username\":\"ZH0006\",\"password\":\"secret\",\"tenantId\":\"xiaoshan\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-bbp-login"))
            .andExpect(jsonPath("$.data.token").value("bbp-token"))
            .andExpect(jsonPath("$.data.user.authProvider").value("BBP"))
            .andExpect(jsonPath("$.data.user.roles[0]").value("ORG_ANALYST"));

        verify(bbpAuthService).login(argThat(request -> "ZH0006".equals(request.getUsername())
            && "secret".equals(request.getPassword()) && "xiaoshan".equals(request.getTenantId())));
    }

    @Test
    void meShouldReadCurrentUserFromContextHolder() throws Exception {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("USER001");
        user.setCdUser("admin");
        user.setNaUser("系统管理员");
        user.setRoles(Collections.singletonList("SYSTEM_ADMIN"));

        AdminContextHolder.set(user);
        when(adminAuthService.currentUser(user)).thenReturn(user);

        mockMvc.perform(get("/admin/api/auth/me")
                .header("X-Request-Id", "RID-auth-me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-auth-me"))
            .andExpect(jsonPath("$.data.cdUser").value("admin"))
            .andExpect(jsonPath("$.data.roles[0]").value("SYSTEM_ADMIN"));

        verify(adminAuthService).currentUser(user);
    }

    @Test
    void changePasswordShouldReturnWrappedOkPayload() throws Exception {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("USER001");
        user.setCdUser("admin");

        AdminContextHolder.set(user);

        mockMvc.perform(put("/admin/api/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-auth-password")
                .content("{\"oldPassword\":\"admin123\",\"newPassword\":\"newPass123\",\"confirmPassword\":\"newPass123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-auth-password"))
            .andExpect(jsonPath("$.data.status").value("ok"));

        verify(adminAuthService).changePassword(eq(user), any());
    }
}
