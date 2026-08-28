package com.regionalai.floatingball.server.modules.userpermission.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateResponse;
import com.regionalai.floatingball.server.modules.userpermission.service.AiUserPermissionService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAiUserPermissionControllerTest {

    @Mock
    private AiUserPermissionService permissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAiUserPermissionController(permissionService))
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
    void shouldBatchUpdatePermissions() throws Exception {
        AiUserPermissionBatchUpdateResponse response = new AiUserPermissionBatchUpdateResponse();
        response.setOrgId("ORG-1");
        response.setEnabled(true);
        response.setRequestedCount(2);
        response.setChangedCount(2);
        response.setUnchangedCount(0);
        response.setRecords(Collections.emptyList());
        when(permissionService.batchUpdate(any(), any())).thenReturn(response);

        mockMvc.perform(put("/admin/api/ai-user-permissions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"ORG-1\",\"personIds\":[\"PERSON-1\",\"PERSON-2\"],\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.requestedCount").value(2))
            .andExpect(jsonPath("$.data.changedCount").value(2));
    }

    @Test
    void shouldRejectEmptyBatch() throws Exception {
        mockMvc.perform(put("/admin/api/ai-user-permissions/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"ORG-1\",\"personIds\":[],\"enabled\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-001"));
    }

    @Test
    void shouldRevokeRiskPermissions() throws Exception {
        AiUserPermissionBatchUpdateResponse response = new AiUserPermissionBatchUpdateResponse();
        response.setOrgId("ORG-1");
        response.setEnabled(false);
        response.setRequestedCount(3);
        response.setChangedCount(3);
        response.setUnchangedCount(0);
        response.setRecords(Collections.emptyList());
        when(permissionService.revokeRiskPermissions(any(), any())).thenReturn(response);

        mockMvc.perform(put("/admin/api/ai-user-permissions/revoke-risk")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"ORG-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.changedCount").value(3));
    }
}
