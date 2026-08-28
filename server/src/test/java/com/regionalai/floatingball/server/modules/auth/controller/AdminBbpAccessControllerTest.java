package com.regionalai.floatingball.server.modules.auth.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAdminAccessService;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessView;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminBbpAccessControllerTest {

    @Mock
    private BbpAdminAccessService accessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminBbpAccessController(accessService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("ADMIN-1");
        AdminContextHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        AdminContextHolder.clear();
    }

    @Test
    void shouldListOrganizationAdminAccess() throws Exception {
        BbpAdminAccessView view = new BbpAdminAccessView();
        view.setBbpUserId("USER-1");
        view.setPersonName("张三");
        view.setAdminAccessEnabled(true);
        when(accessService.list(any(), eq("ORG-A"), eq("ORG_ANALYST")))
            .thenReturn(Collections.singletonList(view));

        mockMvc.perform(get("/admin/api/bbp/admin-access")
                .param("orgId", "ORG-A")
                .param("roleCode", "ORG_ANALYST"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].bbpUserId").value("USER-1"))
            .andExpect(jsonPath("$.data[0].adminAccessEnabled").value(true));
    }

    @Test
    void shouldUpdateOrganizationAdminAccess() throws Exception {
        BbpAdminAccessView view = new BbpAdminAccessView();
        view.setBbpUserId("USER-1");
        view.setAdminAccessEnabled(true);
        when(accessService.update(any(), eq("USER-1"), any())).thenReturn(view);

        mockMvc.perform(put("/admin/api/bbp/admin-access/USER-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"ORG-A\",\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.adminAccessEnabled").value(true));
    }

    @Test
    void shouldValidateRequiredOrganization() throws Exception {
        mockMvc.perform(put("/admin/api/bbp/admin-access/USER-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-001"));
    }
}
