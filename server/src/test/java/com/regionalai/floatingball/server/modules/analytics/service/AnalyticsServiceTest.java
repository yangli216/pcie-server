package com.regionalai.floatingball.server.modules.analytics.service;

import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsSummaryVO;
import com.regionalai.floatingball.server.modules.analytics.dto.DistributionDataVO;
import com.regionalai.floatingball.server.modules.analytics.dto.DistributionItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageTrendVO;
import com.regionalai.floatingball.server.modules.analytics.dto.TrendDataVO;
import com.regionalai.floatingball.server.modules.analytics.mapper.AnalyticsMapper;
import com.regionalai.floatingball.server.modules.audit.service.AuditLogDisplayCatalog;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private AnalyticsMapper analyticsMapper;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(analyticsMapper, new AuditLogDisplayCatalog(),
            new DatabaseDialect(DatabaseDialect.Kind.ORACLE));
    }

    @Test
    void getFunctionUsageShouldUseFeatureDimensionAndAcceptLegacyModuleFilter() {
        FunctionUsageItemVO rankingItem = new FunctionUsageItemVO();
        rankingItem.setModuleName("语音问诊");
        rankingItem.setCallCount(12L);
        rankingItem.setDoctorCount(3L);
        rankingItem.setAvgPerDoctor(4L);

        FunctionUsageItemVO previousItem = new FunctionUsageItemVO();
        previousItem.setModuleName("语音问诊");
        previousItem.setCallCount(6L);

        Map<String, Object> trendRow = new LinkedHashMap<String, Object>();
        trendRow.put("MODULENAME", "语音问诊");
        trendRow.put("DAYSTR", "2026-05-01");
        trendRow.put("CNT", 12L);

        when(analyticsMapper.queryFunctionUsageRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(rankingItem));
        when(analyticsMapper.queryFunctionUsagePreviousRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(previousItem));
        when(analyticsMapper.queryFunctionUsageTrend(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(trendRow));
        when(analyticsMapper.queryDistinctModules())
            .thenReturn(Arrays.asList("consultation_reference", "voice_capsule"));

        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-01");
        query.setHisOrgId("HIS-ORG-001");
        query.setFunctionModules(Collections.singletonList("语音问诊 AI"));

        FunctionUsageResponseVO response = analyticsService.getFunctionUsage(query);

        assertEquals("语音问诊", response.getRanking().get(0).getModuleName());
        assertEquals("100", response.getRanking().get(0).getGrowthRate());
        assertIterableEquals(Collections.singletonList("语音问诊"), response.getTrend().getModules());
        assertIterableEquals(Collections.singletonList("2026-05-01"), response.getTrend().getDays());
        verify(analyticsMapper).queryFunctionUsageRanking(argThat(arg -> arg.getFunctionModules() != null
            && arg.getFunctionModules().contains("语音问诊")
            && "HIS-ORG-001".equals(arg.getHisOrgId())));
    }

    @Test
    void getFunctionModuleOptionsShouldReturnFeatureNames() {
        when(analyticsMapper.queryDistinctModules())
            .thenReturn(Arrays.asList("语音问诊", "智能问诊", "语音问诊"));

        List<String> options = analyticsService.getFunctionModuleOptions();

        assertIterableEquals(Arrays.asList("语音问诊", "智能问诊"), options);
    }

    @Test
    void getFunctionUsageShouldResolveTreatmentPlanLegacyModuleFilter() {
        FunctionUsageItemVO rankingItem = new FunctionUsageItemVO();
        rankingItem.setModuleName("AI推荐治疗方案");
        rankingItem.setCallCount(5L);
        rankingItem.setDoctorCount(1L);
        rankingItem.setAvgPerDoctor(5L);

        when(analyticsMapper.queryFunctionUsageRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(rankingItem));
        when(analyticsMapper.queryFunctionUsagePreviousRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.<FunctionUsageItemVO>emptyList());
        when(analyticsMapper.queryFunctionUsageTrend(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.<Map<String, Object>>emptyList());
        when(analyticsMapper.queryDistinctModules())
            .thenReturn(Arrays.asList("语音问诊", "AI推荐治疗方案"));

        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-01");
        query.setFunctionModules(Collections.singletonList("treatment_plan"));

        FunctionUsageResponseVO response = analyticsService.getFunctionUsage(query);

        assertEquals("AI推荐治疗方案", response.getRanking().get(0).getModuleName());
        verify(analyticsMapper).queryFunctionUsageRanking(argThat(arg -> arg.getFunctionModules() != null
            && arg.getFunctionModules().contains("AI推荐治疗方案")));
    }

    @Test
    void getFunctionUsageShouldResolveTreatmentPlanOldChineseModuleFilter() {
        when(analyticsMapper.queryFunctionUsageRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.<FunctionUsageItemVO>emptyList());
        when(analyticsMapper.queryFunctionUsagePreviousRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.<FunctionUsageItemVO>emptyList());
        when(analyticsMapper.queryFunctionUsageTrend(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.<Map<String, Object>>emptyList());
        when(analyticsMapper.queryDistinctModules())
            .thenReturn(Arrays.asList("语音问诊", "AI推荐治疗方案"));

        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setFunctionModules(Collections.singletonList("AI诊疗方案推荐"));

        analyticsService.getFunctionUsage(query);

        verify(analyticsMapper).queryFunctionUsageRanking(argThat(arg -> arg.getFunctionModules() != null
            && arg.getFunctionModules().contains("AI推荐治疗方案")));
    }

    @Test
    void getSummaryShouldCalculateCurrentRatesAndGrowthAgainstPreviousPeriod() {
        AnalyticsQueryDTO query = new AnalyticsQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-02");
        query.setTimeRange("custom");
        query.setHisOrgId("HIS-ORG-001");

        when(analyticsMapper.countAiService(any(AnalyticsQueryDTO.class))).thenReturn(20L, 10L);
        when(analyticsMapper.countConsultation(any(AnalyticsQueryDTO.class))).thenReturn(8L, 4L);
        when(analyticsMapper.countActiveDoctors(any(AnalyticsQueryDTO.class))).thenReturn(5L, 3L);
        when(analyticsMapper.countAdoptedConsultations(any(AnalyticsQueryDTO.class))).thenReturn(6L, 1L);
        when(analyticsMapper.countFinalizedConsultations(any(AnalyticsQueryDTO.class))).thenReturn(4L, 2L);
        when(analyticsMapper.countDiagnosisMatchedConsultations(any(AnalyticsQueryDTO.class))).thenReturn(3L, 1L);

        AnalyticsSummaryVO summary = analyticsService.getSummary(query);

        assertEquals(20L, summary.getAiServiceTotal());
        assertEquals(10L, summary.getAvgDailyAiService());
        assertEquals("75", summary.getAiAdoptionRate());
        assertEquals("75", summary.getDiagnosisMatchRate());
        assertEquals("100", summary.getAiServiceGrowth());
        assertEquals("100", summary.getAvgDailyGrowth());
        assertEquals("50", summary.getAdoptionRateGrowth());
        assertEquals("25", summary.getMatchRateGrowth());
        assertEquals("2", summary.getActiveDoctorGrowth());
        assertEquals("100", summary.getConsultationGrowth());
        verify(analyticsMapper, times(2)).countAiService(argThat(arg -> "HIS-ORG-001".equals(arg.getHisOrgId())));
    }

    @Test
    void getTrendShouldFillMissingDaysBetweenDateRange() {
        Map<String, Object> aiRow = new LinkedHashMap<String, Object>();
        aiRow.put("DAY_STR", "2026-05-01");
        aiRow.put("CNT", 3L);
        Map<String, Object> consultationRow = new LinkedHashMap<String, Object>();
        consultationRow.put("DAY_STR", "2026-05-03");
        consultationRow.put("CNT", 2L);

        when(analyticsMapper.queryAiServiceTrend(any(AnalyticsQueryDTO.class)))
            .thenReturn(Collections.singletonList(aiRow));
        when(analyticsMapper.queryConsultationTrend(any(AnalyticsQueryDTO.class)))
            .thenReturn(Collections.singletonList(consultationRow));

        AnalyticsQueryDTO query = new AnalyticsQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-03");

        TrendDataVO trend = analyticsService.getTrend(query);

        assertIterableEquals(Arrays.asList("2026-05-01", "2026-05-02", "2026-05-03"), trend.getDays());
        assertIterableEquals(Arrays.asList(3L, 0L, 0L), trend.getAiServiceValues());
        assertIterableEquals(Arrays.asList(0L, 0L, 2L), trend.getConsultationValues());
    }

    @Test
    void getDistributionShouldComputeServiceAndConsultationPercentages() {
        DistributionItemVO org = new DistributionItemVO();
        org.setName("默认机构");
        org.setValue(7L);

        DistributionItemVO regionA = new DistributionItemVO();
        regionA.setName("区域A");
        regionA.setValue(3L);
        DistributionItemVO regionB = new DistributionItemVO();
        regionB.setName("区域B");
        regionB.setValue(1L);

        DistributionItemVO consultationOrg = new DistributionItemVO();
        consultationOrg.setName("市第一医院");
        consultationOrg.setValue(5L);
        DistributionItemVO consultationRegionA = new DistributionItemVO();
        consultationRegionA.setName("区域A");
        consultationRegionA.setValue(4L);
        DistributionItemVO consultationRegionB = new DistributionItemVO();
        consultationRegionB.setName("区域B");
        consultationRegionB.setValue(1L);

        when(analyticsMapper.queryOrgDistribution(any(AnalyticsQueryDTO.class)))
            .thenReturn(Collections.singletonList(org));
        when(analyticsMapper.queryRegionDistributionRaw(any(AnalyticsQueryDTO.class)))
            .thenReturn(Arrays.asList(regionA, regionB));
        when(analyticsMapper.queryConsultationOrgDistribution(any(AnalyticsQueryDTO.class)))
            .thenReturn(Collections.singletonList(consultationOrg));
        when(analyticsMapper.queryConsultationRegionDistributionRaw(any(AnalyticsQueryDTO.class)))
            .thenReturn(Arrays.asList(consultationRegionA, consultationRegionB));

        DistributionDataVO distribution = analyticsService.getDistribution(new AnalyticsQueryDTO());

        assertEquals(Long.valueOf(7L), distribution.getTotalService());
        assertEquals("75", distribution.getRegionDistribution().get(0).getPercentage());
        assertEquals("25", distribution.getRegionDistribution().get(1).getPercentage());
        assertEquals(Long.valueOf(5L), distribution.getTotalConsultation());
        assertEquals("市第一医院", distribution.getConsultationOrgDistribution().get(0).getName());
        assertEquals("80", distribution.getConsultationRegionDistribution().get(0).getPercentage());
        assertEquals("20", distribution.getConsultationRegionDistribution().get(1).getPercentage());
    }

    @Test
    void exportAnalyticsExcelShouldIncludeSummaryTrendAndDistributionSheets() throws Exception {
        Map<String, Object> aiRow = new LinkedHashMap<String, Object>();
        aiRow.put("DAY_STR", "2026-05-01");
        aiRow.put("CNT", 3L);
        Map<String, Object> consultationRow = new LinkedHashMap<String, Object>();
        consultationRow.put("DAY_STR", "2026-05-01");
        consultationRow.put("CNT", 2L);

        DistributionItemVO org = new DistributionItemVO();
        org.setName("默认机构");
        org.setValue(7L);
        DistributionItemVO region = new DistributionItemVO();
        region.setName("默认区域");
        region.setValue(7L);

        when(analyticsMapper.countAiService(any(AnalyticsQueryDTO.class))).thenReturn(3L, 1L);
        when(analyticsMapper.countConsultation(any(AnalyticsQueryDTO.class))).thenReturn(2L, 1L);
        when(analyticsMapper.countActiveDoctors(any(AnalyticsQueryDTO.class))).thenReturn(1L, 1L);
        when(analyticsMapper.countAdoptedConsultations(any(AnalyticsQueryDTO.class))).thenReturn(1L, 1L);
        when(analyticsMapper.countFinalizedConsultations(any(AnalyticsQueryDTO.class))).thenReturn(1L, 1L);
        when(analyticsMapper.countDiagnosisMatchedConsultations(any(AnalyticsQueryDTO.class))).thenReturn(1L, 1L);
        when(analyticsMapper.queryAiServiceTrend(any(AnalyticsQueryDTO.class))).thenReturn(Collections.singletonList(aiRow));
        when(analyticsMapper.queryConsultationTrend(any(AnalyticsQueryDTO.class))).thenReturn(Collections.singletonList(consultationRow));
        when(analyticsMapper.queryOrgDistribution(any(AnalyticsQueryDTO.class))).thenReturn(Collections.singletonList(org));
        when(analyticsMapper.queryRegionDistributionRaw(any(AnalyticsQueryDTO.class))).thenReturn(Collections.singletonList(region));
        when(analyticsMapper.queryConsultationOrgDistribution(any(AnalyticsQueryDTO.class))).thenReturn(Collections.singletonList(org));
        when(analyticsMapper.queryConsultationRegionDistributionRaw(any(AnalyticsQueryDTO.class))).thenReturn(Collections.singletonList(region));

        AnalyticsQueryDTO query = new AnalyticsQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-01");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(analyticsService.exportAnalyticsExcel(query)))) {
            assertEquals("核心指标", workbook.getSheetAt(0).getSheetName());
            assertEquals("趋势明细", workbook.getSheetAt(1).getSheetName());
            assertEquals("机构分布", workbook.getSheetAt(2).getSheetName());
            assertEquals("区域分布", workbook.getSheetAt(3).getSheetName());
            assertEquals("问诊HIS机构分布", workbook.getSheetAt(4).getSheetName());
            assertEquals("问诊区域分布", workbook.getSheetAt(5).getSheetName());
            assertEquals("问诊量", workbook.getSheetAt(4).getRow(0).getCell(1).getStringCellValue());
            assertEquals("功能调用总量", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void exportFunctionUsageExcelShouldIncludeRankingAndTrendSheets() throws Exception {
        FunctionUsageItemVO rankingItem = new FunctionUsageItemVO();
        rankingItem.setModuleName("语音问诊");
        rankingItem.setCallCount(12L);
        rankingItem.setDoctorCount(3L);
        rankingItem.setAvgPerDoctor(4L);

        FunctionUsageItemVO previousItem = new FunctionUsageItemVO();
        previousItem.setModuleName("语音问诊");
        previousItem.setCallCount(6L);

        Map<String, Object> trendRow = new LinkedHashMap<String, Object>();
        trendRow.put("MODULENAME", "语音问诊");
        trendRow.put("DAYSTR", "2026-05-01");
        trendRow.put("CNT", 12L);

        when(analyticsMapper.queryFunctionUsageRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(rankingItem));
        when(analyticsMapper.queryFunctionUsagePreviousRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(previousItem));
        when(analyticsMapper.queryFunctionUsageTrend(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(trendRow));
        when(analyticsMapper.queryDistinctModules())
            .thenReturn(Collections.singletonList("语音问诊"));

        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-01");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(analyticsService.exportFunctionUsageExcel(query)))) {
            assertEquals("汇总指标", workbook.getSheetAt(0).getSheetName());
            assertEquals("功能排行", workbook.getSheetAt(1).getSheetName());
            assertEquals("趋势明细", workbook.getSheetAt(2).getSheetName());
            assertEquals("语音问诊", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
        }
    }
}
