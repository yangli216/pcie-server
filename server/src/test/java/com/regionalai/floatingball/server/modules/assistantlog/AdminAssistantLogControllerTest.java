package com.regionalai.floatingball.server.modules.assistantlog;

import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.modules.assistantlog.controller.AdminAssistantLogController;
import com.regionalai.floatingball.server.modules.assistantlog.dto.*;
import com.regionalai.floatingball.server.modules.assistantlog.service.AssistantLogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAssistantLogControllerTest {
    private final AssistantLogService service = mock(AssistantLogService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminAssistantLogController(service)).build();

    @Test
    void allClinicalLogEndpointsRequireAdminAndRejectStatisticsOnlyAccounts() throws Exception {
        com.regionalai.floatingball.server.modules.auth.service.AdminTokenService tokens = mock(com.regionalai.floatingball.server.modules.auth.service.AdminTokenService.class);
        com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser user = new com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser();
        user.setAuthProvider("BBP"); user.setRoles(Collections.singletonList("ORG_ANALYST"));
        when(tokens.parse("analyst")).thenReturn(user);
        MockMvc protectedMvc = MockMvcBuilders.standaloneSetup(new AdminAssistantLogController(service))
            .addFilters(new com.regionalai.floatingball.server.security.AdminAuthFilter(tokens, new com.fasterxml.jackson.databind.ObjectMapper(), new com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope())).build();
        for (String suffix : new String[]{"", "/export", "/organizations", "/id"}) {
            protectedMvc.perform(get("/admin/api/medical-assistant-logs" + suffix)).andExpect(status().isUnauthorized());
            protectedMvc.perform(get("/admin/api/medical-assistant-logs" + suffix).header("Authorization", "Bearer analyst"))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void returnsPageAndRoutesStaticExportAndOrganizationPathsBeforeDetail() throws Exception {
        when(service.list(any(), eq(2L), eq(20L))).thenReturn(new PageResponse<>(2, 20, 0, Collections.emptyList()));
        mvc.perform(get("/admin/api/medical-assistant-logs").param("current", "2").param("size", "20").param("doctorName", "张医生").header("X-Request-Id", "test-id"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.current").value(2)).andExpect(jsonPath("$.requestId").value("test-id"));
        when(service.exportCsv(any())).thenReturn(new byte[]{1, 2});
        mvc.perform(get("/admin/api/medical-assistant-logs/export")).andExpect(status().isOk())
            .andExpect(content().contentType("text/csv;charset=UTF-8")).andExpect(content().bytes(new byte[]{1, 2}));
        when(service.organizations()).thenReturn(Collections.emptyList());
        mvc.perform(get("/admin/api/medical-assistant-logs/organizations")).andExpect(jsonPath("$.data").isArray());
        verify(service, never()).detail(anyString());
    }
}
