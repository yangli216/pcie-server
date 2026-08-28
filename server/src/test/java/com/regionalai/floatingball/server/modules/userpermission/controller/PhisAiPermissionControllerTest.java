package com.regionalai.floatingball.server.modules.userpermission.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckResponse;
import com.regionalai.floatingball.server.modules.userpermission.service.AiUserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PhisAiPermissionControllerTest {

    @Mock
    private AiUserPermissionService permissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PhisAiPermissionController controller = new PhisAiPermissionController(permissionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void shouldReturnPciePermissionDecisionToPhis() throws Exception {
        PhisAiPermissionCheckResponse response = new PhisAiPermissionCheckResponse();
        response.setAllowed(true);
        response.setReason("AUTHORIZED");
        response.setOrgId("ORG-1");
        response.setPersonId("PERSON-1");
        response.setPersonCd("D001");
        when(permissionService.check(any())).thenReturn(response);

        mockMvc.perform(post("/integration/api/phis/ai-permissions/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"TENANT-1\",\"orgId\":\"ORG-1\",\"personId\":\"PERSON-1\",\"personCd\":\"D001\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.data.allowed").value(true))
            .andExpect(jsonPath("$.data.reason").value("AUTHORIZED"));
    }

    @Test
    void shouldStillValidateBusinessRequestWithoutServiceKey() throws Exception {
        mockMvc.perform(post("/integration/api/phis/ai-permissions/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"TENANT-1\",\"personCd\":\"D001\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION-001"));
    }

    @Test
    void shouldReportPermissionSchemaNotReadyWithoutExposingSqlDetails() throws Exception {
        when(permissionService.check(any())).thenThrow(new ServiceUnavailableException(
            "AI-PERMISSION-SCHEMA-NOT-READY", "AI 使用权限数据表尚未初始化，请联系 DBA 完成一次性迁移"));

        mockMvc.perform(post("/integration/api/phis/ai-permissions/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"TENANT-1\",\"orgId\":\"ORG-1\",\"personId\":\"PERSON-1\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AI-PERMISSION-SCHEMA-NOT-READY"))
            .andExpect(jsonPath("$.message").value("AI 使用权限数据表尚未初始化，请联系 DBA 完成一次性迁移"));
    }
}
