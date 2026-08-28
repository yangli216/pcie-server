package com.regionalai.floatingball.server.modules.xiaoshananalytics.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageTrendVO;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.xiaoshananalytics.service.XiaoshanFunctionUsageService;
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

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminXiaoshanAnalyticsControllerTest {

    @Mock
    private XiaoshanFunctionUsageService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminXiaoshanAnalyticsController(service, new BbpStatisticsScope()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        AdminContextHolder.clear();
    }

    @Test
    void dedicatedModuleEndpointShouldReturnFiveXiaoshanOptions() throws Exception {
        when(service.getFunctionModuleOptions()).thenReturn(
            Arrays.asList("语音问诊", "慢病配药", "报告回诊", "报告解读", "医学助手")
        );

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-modules")
                .header("X-Request-Id", "RID-XIAOSHAN-MODULES"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-XIAOSHAN-MODULES"))
            .andExpect(jsonPath("$.data.length()").value(5))
            .andExpect(jsonPath("$.data[4]").value("医学助手"));
    }

    @Test
    void dedicatedUsageEndpointShouldBindOnlyItsOwnRequestPath() throws Exception {
        FunctionUsageTrendVO trend = new FunctionUsageTrendVO();
        trend.setModules(Collections.<String>emptyList());
        trend.setDays(Collections.<String>emptyList());
        trend.setValues(Collections.<java.util.List<Long>>emptyList());
        FunctionUsageResponseVO response = new FunctionUsageResponseVO();
        response.setUsageRate("0%");
        response.setRanking(Collections.emptyList());
        response.setRecords(Collections.emptyList());
        response.setTrend(trend);
        when(service.getFunctionUsage(any(FunctionUsageQueryDTO.class))).thenReturn(response);

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-usage")
                .param("dateFrom", "2026-08-01")
                .param("dateTo", "2026-08-02")
                .param("hisOrgId", "HIS-XIAOSHAN")
                .param("functionModules", "报告解读")
                .param("functionModules", "医学助手"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usageRate").value("0%"));

        ArgumentCaptor<FunctionUsageQueryDTO> captor = ArgumentCaptor.forClass(FunctionUsageQueryDTO.class);
        verify(service).getFunctionUsage(captor.capture());
        assertEquals("HIS-XIAOSHAN", captor.getValue().getHisOrgId());
        assertIterableEquals(Arrays.asList("报告解读", "医学助手"), captor.getValue().getFunctionModules());
    }

    @Test
    void dedicatedExportShouldUseXiaoshanFilename() throws Exception {
        when(service.exportFunctionUsageExcel(any(FunctionUsageQueryDTO.class))).thenReturn(new byte[] { 1, 2, 3 });

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-usage/export"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("xiaoshan-function-usage-")));
    }

    @Test
    void organizationAnalystUsageShouldBeLockedToOwnOrganization() throws Exception {
        AdminContextHolder.set(organizationAnalyst());
        FunctionUsageResponseVO response = new FunctionUsageResponseVO();
        response.setUsageRate("0%");
        response.setRanking(Collections.emptyList());
        response.setRecords(Collections.emptyList());
        when(service.getFunctionUsage(any(FunctionUsageQueryDTO.class))).thenReturn(response);

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-usage")
                .param("idRegion", "REGION-OTHER")
                .param("idOrg", "PLATFORM-OTHER"))
            .andExpect(status().isOk());

        ArgumentCaptor<FunctionUsageQueryDTO> captor = ArgumentCaptor.forClass(FunctionUsageQueryDTO.class);
        verify(service).getFunctionUsage(captor.capture());
        assertEquals("ORG-A", captor.getValue().getHisOrgId());
        assertNull(captor.getValue().getIdRegion());
        assertNull(captor.getValue().getIdOrg());
    }

    @Test
    void organizationAnalystUsageShouldRejectOtherOrganization() throws Exception {
        AdminContextHolder.set(organizationAnalyst());

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-usage")
                .param("hisOrgId", "ORG-B")
                .header("X-Request-Id", "RID-XIAOSHAN-FORBIDDEN"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH-403"))
            .andExpect(jsonPath("$.requestId").value("RID-XIAOSHAN-FORBIDDEN"));

        verify(service, never()).getFunctionUsage(any(FunctionUsageQueryDTO.class));
    }

    @Test
    void organizationAnalystExportShouldBeLockedToOwnOrganization() throws Exception {
        AdminContextHolder.set(organizationAnalyst());
        when(service.exportFunctionUsageExcel(any(FunctionUsageQueryDTO.class))).thenReturn(new byte[] { 1 });

        mockMvc.perform(get("/admin/api/xiaoshan-analytics/function-usage/export")
                .param("idRegion", "REGION-OTHER")
                .param("idOrg", "PLATFORM-OTHER"))
            .andExpect(status().isOk());

        ArgumentCaptor<FunctionUsageQueryDTO> captor = ArgumentCaptor.forClass(FunctionUsageQueryDTO.class);
        verify(service).exportFunctionUsageExcel(captor.capture());
        assertEquals("ORG-A", captor.getValue().getHisOrgId());
        assertNull(captor.getValue().getIdRegion());
        assertNull(captor.getValue().getIdOrg());
    }

    private AdminCurrentUser organizationAnalyst() {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setBbpOrgId("ORG-A");
        user.setBbpOrgName("第一医院");
        user.setRoles(Collections.singletonList("ORG_ANALYST"));
        return user;
    }
}
