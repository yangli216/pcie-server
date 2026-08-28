package com.regionalai.floatingball.server.modules.xiaoshananalytics.service;

import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.xiaoshananalytics.mapper.XiaoshanFunctionUsageMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XiaoshanFunctionUsageServiceTest {

    @Mock
    private XiaoshanFunctionUsageMapper mapper;

    private XiaoshanFunctionUsageService service;

    @BeforeEach
    void setUp() {
        service = new XiaoshanFunctionUsageService(mapper);
    }

    @Test
    void moduleOptionsShouldExposeOnlyFiveXiaoshanFunctionsInProductOrder() {
        assertIterableEquals(
            Arrays.asList("语音问诊", "慢病配药", "报告回诊", "报告解读", "医学助手"),
            service.getFunctionModuleOptions()
        );
    }

    @Test
    void functionUsageShouldApplyFiveModuleScopeAndBuildMetricsTrendAndPreviousPeriod() {
        FunctionUsageItemVO voice = item("语音问诊", 12L, 3L, 4L);
        FunctionUsageItemVO refill = item("慢病配药", 6L, 2L, 3L);
        FunctionUsageItemVO previousVoice = item("语音问诊", 6L, 2L, 3L);

        Map<String, Object> voiceTrend = trend("语音问诊", "2026-08-01", 5L);
        Map<String, Object> refillTrend = trend("慢病配药", "2026-08-02", 6L);
        when(mapper.queryRanking(any(FunctionUsageQueryDTO.class))).thenReturn(Arrays.asList(voice, refill));
        when(mapper.queryPreviousRanking(any(FunctionUsageQueryDTO.class)))
            .thenReturn(Collections.singletonList(previousVoice));
        when(mapper.queryTrend(any(FunctionUsageQueryDTO.class))).thenReturn(Arrays.asList(voiceTrend, refillTrend));

        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setDateFrom("2026-08-01");
        query.setDateTo("2026-08-02");
        query.setIdRegion("REG-XIAOSHAN");
        query.setHisOrgId("HIS-XIAOSHAN");

        FunctionUsageResponseVO response = service.getFunctionUsage(query);

        assertEquals(18L, response.getTotalCallCount());
        assertEquals(9L, response.getAvgDailyCalls());
        assertEquals("40%", response.getUsageRate());
        assertEquals("100", response.getRanking().get(0).getGrowthRate());
        assertEquals("100", response.getRanking().get(1).getGrowthRate());
        assertIterableEquals(Arrays.asList("语音问诊", "慢病配药"), response.getTrend().getModules());
        assertIterableEquals(Arrays.asList("2026-08-01", "2026-08-02"), response.getTrend().getDays());
        assertIterableEquals(Arrays.asList(5L, 0L), response.getTrend().getValues().get(0));
        assertIterableEquals(Arrays.asList(0L, 6L), response.getTrend().getValues().get(1));

        ArgumentCaptor<FunctionUsageQueryDTO> currentCaptor = ArgumentCaptor.forClass(FunctionUsageQueryDTO.class);
        verify(mapper).queryRanking(currentCaptor.capture());
        assertIterableEquals(
            Arrays.asList("语音问诊", "慢病配药", "报告回诊", "报告解读", "医学助手"),
            currentCaptor.getValue().getFunctionModules()
        );
        assertEquals("REG-XIAOSHAN", currentCaptor.getValue().getIdRegion());
        assertEquals("HIS-XIAOSHAN", currentCaptor.getValue().getHisOrgId());

        ArgumentCaptor<FunctionUsageQueryDTO> previousCaptor = ArgumentCaptor.forClass(FunctionUsageQueryDTO.class);
        verify(mapper).queryPreviousRanking(previousCaptor.capture());
        assertEquals("2026-07-30", previousCaptor.getValue().getDateFrom());
        assertEquals("2026-07-31", previousCaptor.getValue().getDateTo());
    }

    @Test
    void unsupportedModuleSelectionShouldReturnEmptyWithoutQueryingDatabase() {
        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setDateFrom("2026-08-01");
        query.setDateTo("2026-08-02");
        query.setFunctionModules(Arrays.asList("智能问诊", "AI推荐诊断"));

        FunctionUsageResponseVO response = service.getFunctionUsage(query);

        assertEquals(0L, response.getTotalCallCount());
        assertEquals("0%", response.getUsageRate());
        assertIterableEquals(Arrays.asList("2026-08-01", "2026-08-02"), response.getTrend().getDays());
        verify(mapper, never()).queryRanking(any(FunctionUsageQueryDTO.class));
        verify(mapper, never()).queryTrend(any(FunctionUsageQueryDTO.class));
    }

    @Test
    void selectedModulesShouldDiscardUnsupportedValuesBeforeQuerying() {
        when(mapper.queryRanking(any(FunctionUsageQueryDTO.class))).thenReturn(Collections.<FunctionUsageItemVO>emptyList());
        when(mapper.queryPreviousRanking(any(FunctionUsageQueryDTO.class))).thenReturn(Collections.<FunctionUsageItemVO>emptyList());
        when(mapper.queryTrend(any(FunctionUsageQueryDTO.class))).thenReturn(Collections.<Map<String, Object>>emptyList());

        FunctionUsageQueryDTO query = new FunctionUsageQueryDTO();
        query.setFunctionModules(Arrays.asList(" 医学助手 ", "聊天", "慢病配药"));
        service.getFunctionUsage(query);

        verify(mapper).queryRanking(org.mockito.ArgumentMatchers.argThat(normalized ->
            normalized.getFunctionModules().equals(Arrays.asList("医学助手", "慢病配药"))
        ));
    }

    @Test
    void exportShouldContainDedicatedSummaryRankingAndTrendSheets() throws Exception {
        FunctionUsageItemVO item = item("报告解读", 3L, 1L, 3L);
        when(mapper.queryRanking(any(FunctionUsageQueryDTO.class))).thenReturn(Collections.singletonList(item));
        when(mapper.queryPreviousRanking(any(FunctionUsageQueryDTO.class))).thenReturn(Collections.<FunctionUsageItemVO>emptyList());
        when(mapper.queryTrend(any(FunctionUsageQueryDTO.class))).thenReturn(Collections.<Map<String, Object>>emptyList());

        byte[] bytes = service.exportFunctionUsageExcel(new FunctionUsageQueryDTO());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("汇总指标", workbook.getSheetName(0));
            assertEquals("功能排行", workbook.getSheetName(1));
            assertEquals("趋势明细", workbook.getSheetName(2));
            assertEquals("报告解读", workbook.getSheet("功能排行").getRow(1).getCell(0).getStringCellValue());
        }
    }

    private FunctionUsageItemVO item(String name, long calls, long doctors, long average) {
        FunctionUsageItemVO item = new FunctionUsageItemVO();
        item.setModuleName(name);
        item.setCallCount(calls);
        item.setDoctorCount(doctors);
        item.setAvgPerDoctor(average);
        return item;
    }

    private Map<String, Object> trend(String module, String day, long count) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("MODULENAME", module);
        row.put("DAYSTR", day);
        row.put("CNT", count);
        return row;
    }
}
