package com.regionalai.floatingball.server.modules.useractivity.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivityQueryDTO;
import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivitySummaryVO;
import com.regionalai.floatingball.server.modules.useractivity.service.UserActivityService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminUserActivityControllerTest {

    @Mock
    private UserActivityService userActivityService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminUserActivityController(userActivityService, new BbpStatisticsScope()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        AdminContextHolder.clear();
    }

    @Test
    void bbpOrganizationUserShouldBeLockedToOwnOrganization() throws Exception {
        AdminContextHolder.set(bbpOrganizationUser());
        when(userActivityService.getSummary(any(UserActivityQueryDTO.class)))
            .thenReturn(new UserActivitySummaryVO());

        mockMvc.perform(get("/admin/api/user-activity/summary")
                .param("idRegion", "REG-OTHER")
                .param("idOrg", "PCIE-OTHER"))
            .andExpect(status().isOk());

        ArgumentCaptor<UserActivityQueryDTO> captor = ArgumentCaptor.forClass(UserActivityQueryDTO.class);
        verify(userActivityService).getSummary(captor.capture());
        assertEquals("ORG-A", captor.getValue().getHisOrgId());
        assertNull(captor.getValue().getIdRegion());
        assertNull(captor.getValue().getIdOrg());
    }

    @Test
    void bbpOrganizationUserShouldBeForbiddenFromOtherOrganization() throws Exception {
        AdminContextHolder.set(bbpOrganizationUser());

        mockMvc.perform(get("/admin/api/user-activity/summary")
                .param("hisOrgId", "ORG-B"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH-403"));
    }

    private AdminCurrentUser bbpOrganizationUser() {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setBbpOrgId("ORG-A");
        user.setBbpOrgName("第一医院");
        user.setRoles(Collections.singletonList("ORG_ANALYST"));
        return user;
    }
}
