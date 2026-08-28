package com.regionalai.floatingball.server.modules.clientusage.service;

import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageItemVO;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageQueryDTO;
import com.regionalai.floatingball.server.modules.clientusage.mapper.ClientUsageMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientUsageServiceTest {

    @Mock
    private ClientUsageMapper clientUsageMapper;

    private ClientUsageService clientUsageService;

    @BeforeEach
    void setUp() {
        clientUsageService = new ClientUsageService(clientUsageMapper);
    }

    @Test
    void listShouldTrimKeywordMapTimesAndQueryOnlyRequestedDatabasePage() {
        ClientUsageQueryDTO query = new ClientUsageQueryDTO();
        query.setKeyword(" 张医生 ");
        when(clientUsageMapper.countClientUsage(query)).thenReturn(2L);
        when(clientUsageMapper.queryClientUsage(query, 1L, 2L)).thenReturn(Arrays.asList(
            row("蜀山社区卫生服务中心", "李医生", "0456", "1.3.8", 10, 0, 16, 10)
        ));

        PageResponse<ClientUsageItemVO> page = clientUsageService.list(query, 2, 1);

        assertEquals(2, page.getCurrent());
        assertEquals(1, page.getSize());
        assertEquals(2, page.getTotal());
        assertEquals("李医生", page.getRecords().get(0).getDoctorName());
        assertEquals("2026-08-10 10:00:00", page.getRecords().get(0).getFirstInteractionTime());
        assertEquals("2026-08-10 16:10:00", page.getRecords().get(0).getLastActiveTime());

        ArgumentCaptor<ClientUsageQueryDTO> captor = ArgumentCaptor.forClass(ClientUsageQueryDTO.class);
        verify(clientUsageMapper).countClientUsage(captor.capture());
        assertEquals("张医生", captor.getValue().getKeyword());
        verify(clientUsageMapper).queryClientUsage(query, 1L, 2L);
    }

    @Test
    void listShouldReturnEmptyWithoutOffsetOverflowForHugeCurrent() {
        ClientUsageQueryDTO query = new ClientUsageQueryDTO();
        when(clientUsageMapper.countClientUsage(query)).thenReturn(2L);

        PageResponse<ClientUsageItemVO> page = clientUsageService.list(query, Long.MAX_VALUE, 200L);

        assertEquals(Long.MAX_VALUE, page.getCurrent());
        assertEquals(100L, page.getSize());
        assertEquals(2L, page.getTotal());
        assertEquals(Collections.emptyList(), page.getRecords());
        verify(clientUsageMapper, never()).queryClientUsage(any(ClientUsageQueryDTO.class), anyLong(), anyLong());
    }

    @Test
    void exportShouldContainExactlyTheSixRequestedColumns() throws Exception {
        ClientUsageQueryDTO query = new ClientUsageQueryDTO();
        when(clientUsageMapper.countClientUsage(query)).thenReturn(1L);
        when(clientUsageMapper.queryClientUsage(query, 0L, 50_001L)).thenReturn(Arrays.asList(
            row("新塘社区卫生服务中心", "张医生", "0123", "1.3.9", 9, 30, 15, 20)
        ));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(clientUsageService.exportExcel(query)))) {
            assertEquals("医生使用情况", workbook.getSheetAt(0).getSheetName());
            assertEquals("机构名称", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("医生名字", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
            assertEquals("工号", workbook.getSheetAt(0).getRow(0).getCell(2).getStringCellValue());
            assertEquals("安装客户端时间", workbook.getSheetAt(0).getRow(0).getCell(3).getStringCellValue());
            assertEquals("当前使用的客户端版本", workbook.getSheetAt(0).getRow(0).getCell(4).getStringCellValue());
            assertEquals("最近活跃时间", workbook.getSheetAt(0).getRow(0).getCell(5).getStringCellValue());
            assertEquals("新塘社区卫生服务中心", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("0123", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
            assertEquals("1.3.9", workbook.getSheetAt(0).getRow(1).getCell(4).getStringCellValue());
        }
    }

    @Test
    void exportShouldRejectBeforeReadingWhenCountExceedsLimit() {
        ClientUsageQueryDTO query = new ClientUsageQueryDTO();
        when(clientUsageMapper.countClientUsage(query)).thenReturn(50_001L);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> clientUsageService.exportExcel(query)
        );

        assertEquals("CLIENT-USAGE-EXPORT-LIMIT", exception.getCode());
        assertEquals("客户端使用情况匹配结果超过 50000 条，请增加筛选条件后重试", exception.getMessage());
        verify(clientUsageMapper, never()).queryClientUsage(any(ClientUsageQueryDTO.class), anyLong(), anyLong());
    }

    private Map<String, Object> row(String orgName,
                                    String doctorName,
                                    String workNo,
                                    String version,
                                    int firstHour,
                                    int firstMinute,
                                    int lastHour,
                                    int lastMinute) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("ORGNAME", orgName);
        row.put("DOCTORNAME", doctorName);
        row.put("DOCTORWORKNO", workNo);
        row.put("FIRSTINTERACTIONTIME", Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, firstHour, firstMinute)));
        row.put("CLIENTVERSION", version);
        row.put("LASTACTIVETIME", LocalDateTime.of(2026, 8, 10, lastHour, lastMinute));
        return row;
    }
}
