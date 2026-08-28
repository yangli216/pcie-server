package com.regionalai.floatingball.server.modules.useractivity.service;

import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivityQueryDTO;
import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivitySummaryVO;
import com.regionalai.floatingball.server.modules.useractivity.mapper.UserActivityMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserActivityServiceTest {

    @Mock
    private UserActivityMapper userActivityMapper;

    private UserActivityService userActivityService;

    @BeforeEach
    void setUp() {
        userActivityService = new UserActivityService(userActivityMapper);
    }

    @Test
    void getSummaryShouldKeepOrgFilterForCurrentAndPreviousPeriod() {
        when(userActivityMapper.countActiveDoctors(any(UserActivityQueryDTO.class))).thenReturn(2L, 1L);
        when(userActivityMapper.countTotalDoctors(any(UserActivityQueryDTO.class))).thenReturn(4L, 4L);
        when(userActivityMapper.countConsultations(any(UserActivityQueryDTO.class))).thenReturn(10L, 8L);
        when(userActivityMapper.countEffectiveConsultations(any(UserActivityQueryDTO.class))).thenReturn(6L, 4L);

        UserActivityQueryDTO query = new UserActivityQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-21");
        query.setTimeRange("month");
        query.setIdRegion("REG001");
        query.setIdOrg("ORG001");
        query.setHisOrgId("HIS-ORG-001");
        query.setActiveStatus("active");

        UserActivitySummaryVO summary = userActivityService.getSummary(query);

        assertEquals(2L, summary.getActiveUsers());
        assertEquals(2L, summary.getInactiveUsers());
        assertEquals("50", summary.getActivityRate());
        assertEquals("60", summary.getEffectiveConsultationRate());
        assertEquals("1", summary.getActiveUsersGrowth());
        assertEquals("10", summary.getEffectiveConsultationRateGrowth());

        ArgumentCaptor<UserActivityQueryDTO> activeQueryCaptor = ArgumentCaptor.forClass(UserActivityQueryDTO.class);
        verify(userActivityMapper, times(2)).countActiveDoctors(activeQueryCaptor.capture());
        List<UserActivityQueryDTO> activeQueries = activeQueryCaptor.getAllValues();

        assertEquals("ORG001", activeQueries.get(0).getIdOrg());
        assertEquals("REG001", activeQueries.get(0).getIdRegion());
        assertEquals("active", activeQueries.get(0).getActiveStatus());
        assertEquals("HIS-ORG-001", activeQueries.get(0).getHisOrgId());
        assertEquals("2026-05-01", activeQueries.get(0).getDateFrom());
        assertEquals("2026-05-21", activeQueries.get(0).getDateTo());

        assertEquals("ORG001", activeQueries.get(1).getIdOrg());
        assertEquals("REG001", activeQueries.get(1).getIdRegion());
        assertEquals("active", activeQueries.get(1).getActiveStatus());
        assertEquals("HIS-ORG-001", activeQueries.get(1).getHisOrgId());
        assertEquals("2026-04-01", activeQueries.get(1).getDateFrom());
        assertEquals("2026-04-30", activeQueries.get(1).getDateTo());
    }

    @Test
    void getUserListShouldNormalizeWeekRangeLikeAnalyticsPages() {
        when(userActivityMapper.queryDoctorActivityList(any(UserActivityQueryDTO.class)))
            .thenReturn(Collections.emptyList());

        UserActivityQueryDTO query = new UserActivityQueryDTO();
        query.setTimeRange("week");

        userActivityService.getUserList(query, 1, 10);

        ArgumentCaptor<UserActivityQueryDTO> queryCaptor = ArgumentCaptor.forClass(UserActivityQueryDTO.class);
        verify(userActivityMapper).queryDoctorActivityList(queryCaptor.capture());

        LocalDate now = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        assertEquals(now.minusDays(now.getDayOfWeek().getValue() - 1L).format(fmt), queryCaptor.getValue().getDateFrom());
        assertEquals(now.format(fmt), queryCaptor.getValue().getDateTo());
    }

    @Test
    void exportExcelShouldIncludeSummaryAndUserSheets() throws Exception {
        when(userActivityMapper.countActiveDoctors(any(UserActivityQueryDTO.class))).thenReturn(1L, 0L);
        when(userActivityMapper.countTotalDoctors(any(UserActivityQueryDTO.class))).thenReturn(2L, 2L);
        when(userActivityMapper.countConsultations(any(UserActivityQueryDTO.class))).thenReturn(4L, 0L);
        when(userActivityMapper.countEffectiveConsultations(any(UserActivityQueryDTO.class))).thenReturn(2L, 0L);

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("IDDOCTOR", "DOCTOR001");
        row.put("DEVICECOUNT", 2L);
        row.put("NAORG", "默认机构");
        row.put("HISORGID", "HIS-ORG-001");
        row.put("HISORGNAME", "市第一医院");
        row.put("NAREGION", "默认区域");
        row.put("NADOCTOR", "范医生");
        row.put("CONSULTATIONCOUNT", 2L);
        row.put("EFFECTIVECONSULTATIONCOUNT", 1L);
        row.put("LASTACTIVETIME", "2026-05-21 10:00:00");
        when(userActivityMapper.queryDoctorActivityList(any(UserActivityQueryDTO.class)))
            .thenReturn(Collections.singletonList(row));

        UserActivityQueryDTO query = new UserActivityQueryDTO();
        query.setDateFrom("2026-05-01");
        query.setDateTo("2026-05-21");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(userActivityService.exportExcel(query)))) {
            assertEquals("活跃度汇总", workbook.getSheetAt(0).getSheetName());
            assertEquals("医生明细", workbook.getSheetAt(1).getSheetName());
            assertEquals("活跃医生数", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("1", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("有效问诊率", workbook.getSheetAt(0).getRow(4).getCell(0).getStringCellValue());
            assertEquals("DOCTOR001", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
            assertEquals("范医生", workbook.getSheetAt(1).getRow(1).getCell(1).getStringCellValue());
            assertEquals(2D, workbook.getSheetAt(1).getRow(1).getCell(2).getNumericCellValue());
            assertEquals("HIS机构", workbook.getSheetAt(1).getRow(0).getCell(4).getStringCellValue());
            assertEquals("市第一医院", workbook.getSheetAt(1).getRow(1).getCell(4).getStringCellValue());
            assertEquals("有效问诊数", workbook.getSheetAt(1).getRow(0).getCell(8).getStringCellValue());
        }
    }
}
