package com.regionalai.floatingball.server.modules.clientusage.service;

import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.ExcelColumnWidthUtils;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageItemVO;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageQueryDTO;
import com.regionalai.floatingball.server.modules.clientusage.mapper.ClientUsageMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ClientUsageService {

    static final int EXPORT_ROW_LIMIT = 50_000;
    private static final int EXPORT_QUERY_END_ROW = EXPORT_ROW_LIMIT + 1;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] EXPORT_HEADERS = {
        "机构名称", "医生名字", "工号", "安装客户端时间", "当前使用的客户端版本", "最近活跃时间"
    };

    private final ClientUsageMapper clientUsageMapper;

    public ClientUsageService(ClientUsageMapper clientUsageMapper) {
        this.clientUsageMapper = clientUsageMapper;
    }

    public PageResponse<ClientUsageItemVO> list(ClientUsageQueryDTO query, long current, long size) {
        ClientUsageQueryDTO normalizedQuery = normalizeQuery(query);
        long normalizedCurrent = Math.max(1L, current);
        long normalizedSize = Math.min(200L, Math.max(1L, size));
        long total = Math.max(0L, clientUsageMapper.countClientUsage(normalizedQuery));
        if (total == 0L || normalizedCurrent > ((total - 1L) / normalizedSize) + 1L) {
            return new PageResponse<ClientUsageItemVO>(
                normalizedCurrent,
                normalizedSize,
                total,
                Collections.<ClientUsageItemVO>emptyList()
            );
        }

        long offset = (normalizedCurrent - 1L) * normalizedSize;
        long endRow = offset + Math.min(normalizedSize, total - offset);
        List<ClientUsageItemVO> page = loadItems(
            clientUsageMapper.queryClientUsage(normalizedQuery, offset, endRow)
        );
        return new PageResponse<ClientUsageItemVO>(normalizedCurrent, normalizedSize, total, page);
    }

    public byte[] exportExcel(ClientUsageQueryDTO query) {
        ClientUsageQueryDTO normalizedQuery = normalizeQuery(query);
        long total = Math.max(0L, clientUsageMapper.countClientUsage(normalizedQuery));
        if (total > EXPORT_ROW_LIMIT) {
            throw exportLimitExceeded();
        }
        List<ClientUsageItemVO> items = loadItems(
            clientUsageMapper.queryClientUsage(normalizedQuery, 0L, EXPORT_QUERY_END_ROW)
        );
        if (items.size() > EXPORT_ROW_LIMIT) {
            throw exportLimitExceeded();
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("医生使用情况");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(EXPORT_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            for (int i = 0; i < items.size(); i++) {
                ClientUsageItemVO item = items.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(safe(item.getOrgName()));
                row.createCell(1).setCellValue(safe(item.getDoctorName()));
                row.createCell(2).setCellValue(safe(item.getDoctorWorkNo()));
                row.createCell(3).setCellValue(safe(item.getFirstInteractionTime()));
                row.createCell(4).setCellValue(safe(item.getClientVersion()));
                row.createCell(5).setCellValue(safe(item.getLastActiveTime()));
            }
            ExcelColumnWidthUtils.fitColumns(sheet, EXPORT_HEADERS.length);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException("导出客户端使用情况失败：" + ex.getMessage());
        }
    }

    private List<ClientUsageItemVO> loadItems(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<ClientUsageItemVO> items = new ArrayList<ClientUsageItemVO>(rows.size());
        for (Map<String, Object> row : rows) {
            ClientUsageItemVO item = new ClientUsageItemVO();
            item.setOrgName(stringValue(mapValue(row, "ORGNAME", "orgname")));
            item.setDoctorName(stringValue(mapValue(row, "DOCTORNAME", "doctorname")));
            item.setDoctorWorkNo(stringValue(mapValue(row, "DOCTORWORKNO", "doctorworkno")));
            item.setFirstInteractionTime(formatDateTime(mapValue(row, "FIRSTINTERACTIONTIME", "firstinteractiontime")));
            item.setClientVersion(stringValue(mapValue(row, "CLIENTVERSION", "clientversion")));
            item.setLastActiveTime(formatDateTime(mapValue(row, "LASTACTIVETIME", "lastactivetime")));
            items.add(item);
        }
        return items;
    }

    private BusinessException exportLimitExceeded() {
        return new BusinessException(
            "CLIENT-USAGE-EXPORT-LIMIT",
            "客户端使用情况匹配结果超过 50000 条，请增加筛选条件后重试"
        );
    }

    private ClientUsageQueryDTO normalizeQuery(ClientUsageQueryDTO query) {
        ClientUsageQueryDTO normalized = query == null ? new ClientUsageQueryDTO() : query;
        normalized.setKeyword(StringUtils.hasText(normalized.getKeyword()) ? normalized.getKeyword().trim() : null);
        return normalized;
    }

    private Object mapValue(Map<String, Object> row, String upperKey, String lowerKey) {
        Object value = row.get(upperKey);
        return value != null ? value : row.get(lowerKey);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String formatDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DATE_TIME_FORMATTER);
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().format(DATE_TIME_FORMATTER);
        }
        return String.valueOf(value).replace('T', ' ');
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
