package com.regionalai.floatingball.server.modules.analytics.controller;

import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsSummaryVO;
import com.regionalai.floatingball.server.modules.analytics.dto.DistributionDataVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageTrendVO;
import com.regionalai.floatingball.server.modules.analytics.dto.HisOrgOptionVO;
import com.regionalai.floatingball.server.modules.analytics.dto.TrendDataVO;
import com.regionalai.floatingball.server.modules.analytics.service.AnalyticsService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminAnalyticsController(analyticsService, new BbpStatisticsScope()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        AdminContextHolder.clear();
    }

    @Test
    void summaryTrendAndDistributionShouldBindQueryAndWrapResponses() throws Exception {
        AnalyticsSummaryVO summary = new AnalyticsSummaryVO();
        summary.setAiServiceTotal(42L);
        summary.setAiAdoptionRate("65.5");
        summary.setActiveDoctorCount(7L);
        when(analyticsService.getSummary(any(AnalyticsQueryDTO.class))).thenReturn(summary);

        TrendDataVO trend = new TrendDataVO();
        trend.setDays(Arrays.asList("2026-05-01", "2026-05-02"));
        trend.setAiServiceValues(Arrays.asList(3L, 5L));
        trend.setConsultationValues(Arrays.asList(1L, 2L));
        when(analyticsService.getTrend(any(AnalyticsQueryDTO.class))).thenReturn(trend);

        DistributionDataVO distribution = new DistributionDataVO();
        distribution.setTotalService(8L);
        distribution.setOrgDistribution(Collections.emptyList());
        distribution.setRegionDistribution(Collections.emptyList());
        distribution.setTotalConsultation(3L);
        distribution.setConsultationOrgDistribution(Collections.emptyList());
        distribution.setConsultationRegionDistribution(Collections.emptyList());
        when(analyticsService.getDistribution(any(AnalyticsQueryDTO.class))).thenReturn(distribution);

        mockMvc.perform(get("/admin/api/analytics/summary")
                .param("dateFrom", "2026-05-01")
                .param("dateTo", "2026-05-02")
                .param("idOrg", "ORG001")
                .param("hisOrgId", "HIS-ORG-001")
                .header("X-Request-Id", "RID-analytics-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-analytics-summary"))
            .andExpect(jsonPath("$.data.aiServiceTotal").value(42))
            .andExpect(jsonPath("$.data.aiAdoptionRate").value("65.5"));

        mockMvc.perform(get("/admin/api/analytics/trend")
                .param("dateFrom", "2026-05-01")
                .param("dateTo", "2026-05-02")
                .header("X-Request-Id", "RID-analytics-trend"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[1]").value("2026-05-02"))
            .andExpect(jsonPath("$.data.aiServiceValues[1]").value(5));

        mockMvc.perform(get("/admin/api/analytics/distribution")
                .param("idRegion", "REG001")
                .header("X-Request-Id", "RID-analytics-distribution"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalService").value(8))
            .andExpect(jsonPath("$.data.totalConsultation").value(3))
            .andExpect(jsonPath("$.data.consultationOrgDistribution").isArray())
            .andExpect(jsonPath("$.data.consultationRegionDistribution").isArray());

        ArgumentCaptor<AnalyticsQueryDTO> queryCaptor = ArgumentCaptor.forClass(AnalyticsQueryDTO.class);
        verify(analyticsService).getSummary(queryCaptor.capture());
        assertEquals("2026-05-01", queryCaptor.getValue().getDateFrom());
        assertEquals("ORG001", queryCaptor.getValue().getIdOrg());
        assertEquals("HIS-ORG-001", queryCaptor.getValue().getHisOrgId());
    }

    @Test
    void functionUsageShouldBindMultiValueModulesAndReturnTrend() throws Exception {
        FunctionUsageItemVO item = new FunctionUsageItemVO();
        item.setModuleName("语音问诊");
        item.setCallCount(12L);
        item.setGrowthRate("100");

        FunctionUsageTrendVO trend = new FunctionUsageTrendVO();
        trend.setModules(Collections.singletonList("语音问诊"));
        trend.setDays(Collections.singletonList("2026-05-01"));
        trend.setValues(Collections.singletonList(Collections.singletonList(12L)));

        FunctionUsageResponseVO response = new FunctionUsageResponseVO();
        response.setTotalCallCount(12L);
        response.setAvgDailyCalls(12L);
        response.setUsageRate("9%");
        response.setRanking(Collections.singletonList(item));
        response.setRecords(Collections.singletonList(item));
        response.setTotal(1L);
        response.setTrend(trend);

        when(analyticsService.getFunctionUsage(any(FunctionUsageQueryDTO.class))).thenReturn(response);

        mockMvc.perform(get("/admin/api/analytics/function-usage")
                .param("dateFrom", "2026-05-01")
                .param("dateTo", "2026-05-01")
                .param("functionModules", "语音问诊")
                .param("functionModules", "聊天")
                .header("X-Request-Id", "RID-analytics-function-usage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-analytics-function-usage"))
            .andExpect(jsonPath("$.data.totalCallCount").value(12))
            .andExpect(jsonPath("$.data.records[0].moduleName").value("语音问诊"))
            .andExpect(jsonPath("$.data.trend.values[0][0]").value(12));

        ArgumentCaptor<FunctionUsageQueryDTO> queryCaptor = ArgumentCaptor.forClass(FunctionUsageQueryDTO.class);
        verify(analyticsService).getFunctionUsage(queryCaptor.capture());
        assertEquals("2026-05-01", queryCaptor.getValue().getDateFrom());
        assertTrue(queryCaptor.getValue().getFunctionModules().contains("聊天"));
    }

    @Test
    void functionModulesShouldReturnOptions() throws Exception {
        when(analyticsService.getFunctionModuleOptions()).thenReturn(Arrays.asList("语音问诊", "智能问诊"));

        mockMvc.perform(get("/admin/api/analytics/function-modules")
                .header("X-Request-Id", "RID-analytics-options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-analytics-options"))
            .andExpect(jsonPath("$.data[0]").value("语音问诊"))
            .andExpect(jsonPath("$.data[1]").value("智能问诊"));
    }

    @Test
    void hisOrgOptionsShouldReturnStructuredOptions() throws Exception {
        HisOrgOptionVO option = new HisOrgOptionVO();
        option.setHisOrgId("HIS-ORG-001");
        option.setHisOrgName("市第一医院");
        when(analyticsService.getHisOrgOptions()).thenReturn(Collections.singletonList(option));

        mockMvc.perform(get("/admin/api/analytics/his-org-options")
                .header("X-Request-Id", "RID-his-org-options"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-his-org-options"))
            .andExpect(jsonPath("$.data[0].hisOrgId").value("HIS-ORG-001"))
            .andExpect(jsonPath("$.data[0].hisOrgName").value("市第一医院"));
    }

    @Test
    void bbpOrganizationUserShouldBeLockedToOwnOrganization() throws Exception {
        AdminCurrentUser user = bbpOrganizationUser();
        AdminContextHolder.set(user);
        when(analyticsService.getSummary(any(AnalyticsQueryDTO.class))).thenReturn(new AnalyticsSummaryVO());

        mockMvc.perform(get("/admin/api/analytics/summary")
                .param("idRegion", "REG-OTHER")
                .param("idOrg", "PCIE-OTHER"))
            .andExpect(status().isOk());

        ArgumentCaptor<AnalyticsQueryDTO> captor = ArgumentCaptor.forClass(AnalyticsQueryDTO.class);
        verify(analyticsService).getSummary(captor.capture());
        assertEquals("ORG-A", captor.getValue().getHisOrgId());
        assertEquals(null, captor.getValue().getIdRegion());
        assertEquals(null, captor.getValue().getIdOrg());
    }

    @Test
    void bbpOrganizationUserShouldBeForbiddenFromOtherOrganization() throws Exception {
        AdminContextHolder.set(bbpOrganizationUser());

        mockMvc.perform(get("/admin/api/analytics/summary")
                .param("hisOrgId", "ORG-B")
                .header("X-Request-Id", "RID-cross-org"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH-403"))
            .andExpect(jsonPath("$.requestId").value("RID-cross-org"));
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
