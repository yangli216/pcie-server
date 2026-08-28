package com.regionalai.floatingball.server.modules.xiaoshananalytics.service;

import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.ExcelColumnWidthUtils;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageTrendVO;
import com.regionalai.floatingball.server.modules.xiaoshananalytics.mapper.XiaoshanFunctionUsageMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class XiaoshanFunctionUsageService {

    private static final Logger log = LoggerFactory.getLogger(XiaoshanFunctionUsageService.class);
    private static final List<String> FUNCTION_MODULES = Collections.unmodifiableList(Arrays.asList(
        "语音问诊",
        "慢病配药",
        "报告回诊",
        "报告解读",
        "医学助手"
    ));

    private final XiaoshanFunctionUsageMapper functionUsageMapper;

    public XiaoshanFunctionUsageService(XiaoshanFunctionUsageMapper functionUsageMapper) {
        this.functionUsageMapper = functionUsageMapper;
    }

    public List<String> getFunctionModuleOptions() {
        return new ArrayList<String>(FUNCTION_MODULES);
    }

    public FunctionUsageResponseVO getFunctionUsage(FunctionUsageQueryDTO query) {
        FunctionUsageQueryDTO normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.getFunctionModules().isEmpty()) {
            return emptyResponse(normalizedQuery);
        }

        List<FunctionUsageItemVO> ranking = safeItems(functionUsageMapper.queryRanking(normalizedQuery));
        long totalCallCount = 0L;
        for (FunctionUsageItemVO item : ranking) {
            totalCallCount += item.getCallCount();
        }

        FunctionUsageResponseVO response = new FunctionUsageResponseVO();
        response.setTotalCallCount(totalCallCount);
        long days = computeDays(normalizedQuery);
        response.setAvgDailyCalls(days > 0 ? totalCallCount / days : 0L);
        response.setUsageRate(formatUsageRate(ranking.size()));

        FunctionUsageQueryDTO previousQuery = buildPreviousPeriodQuery(normalizedQuery);
        List<FunctionUsageItemVO> previousRanking = safeItems(functionUsageMapper.queryPreviousRanking(previousQuery));
        Map<String, Long> previousCounts = new LinkedHashMap<String, Long>();
        for (FunctionUsageItemVO item : previousRanking) {
            previousCounts.put(item.getModuleName(), item.getCallCount());
        }
        for (FunctionUsageItemVO item : ranking) {
            Long previous = previousCounts.get(item.getModuleName());
            item.setGrowthRate(formatGrowth(item.getCallCount(), previous != null ? previous : 0L));
        }

        response.setRanking(ranking);
        response.setTotal(ranking.size());
        PageRequest pageRequest = PageRequest.of(normalizedQuery.getCurrent(), normalizedQuery.getSize());
        response.setCurrent(pageRequest.getCurrent());
        response.setSize(pageRequest.getSize());
        int from = pageRequest.fromIndex(ranking.size());
        int to = pageRequest.toIndex(ranking.size());
        response.setRecords(new ArrayList<FunctionUsageItemVO>(ranking.subList(from, to)));
        response.setTrend(buildTrend(normalizedQuery, ranking));
        return response;
    }

    public byte[] exportFunctionUsageExcel(FunctionUsageQueryDTO query) {
        FunctionUsageResponseVO usage = getFunctionUsage(query);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            XSSFSheet summarySheet = workbook.createSheet("汇总指标");
            writeHeader(summarySheet, headerStyle, "指标", "数值");
            writeRow(summarySheet, 1, "总调用次数", usage.getTotalCallCount());
            writeRow(summarySheet, 2, "平均每日调用", usage.getAvgDailyCalls());
            writeRow(summarySheet, 3, "功能使用率", usage.getUsageRate());
            ExcelColumnWidthUtils.fitColumns(summarySheet, 2);

            XSSFSheet rankingSheet = workbook.createSheet("功能排行");
            writeHeader(rankingSheet, headerStyle, "功能模块", "调用次数", "使用医生数", "人均调用", "增长率(%)");
            List<FunctionUsageItemVO> ranking = usage.getRanking() != null
                ? usage.getRanking() : Collections.<FunctionUsageItemVO>emptyList();
            for (int i = 0; i < ranking.size(); i++) {
                FunctionUsageItemVO item = ranking.get(i);
                writeRow(rankingSheet, i + 1, item.getModuleName(), item.getCallCount(), item.getDoctorCount(),
                    item.getAvgPerDoctor(), item.getGrowthRate());
            }
            ExcelColumnWidthUtils.fitColumns(rankingSheet, 5);

            XSSFSheet trendSheet = workbook.createSheet("趋势明细");
            writeTrendSheet(trendSheet, headerStyle, usage.getTrend());
            return writeWorkbook(workbook);
        } catch (IOException ex) {
            throw new BusinessException("导出Excel失败：" + ex.getMessage());
        }
    }

    private FunctionUsageTrendVO buildTrend(FunctionUsageQueryDTO query, List<FunctionUsageItemVO> ranking) {
        List<Map<String, Object>> trendRows = safeRows(functionUsageMapper.queryTrend(query));
        Map<String, Map<String, Long>> trendMap = new LinkedHashMap<String, Map<String, Long>>();
        for (Map<String, Object> row : trendRows) {
            String module = stringValue(row, "MODULENAME", "moduleName", "modulename");
            String day = stringValue(row, "DAYSTR", "dayStr", "daystr");
            long count = longValue(row, "CNT", "cnt");
            if (module == null || day == null) {
                continue;
            }
            Map<String, Long> moduleValues = trendMap.get(module);
            if (moduleValues == null) {
                moduleValues = new LinkedHashMap<String, Long>();
                trendMap.put(module, moduleValues);
            }
            moduleValues.put(day, count);
        }

        List<String> modules = new ArrayList<String>();
        for (int i = 0; i < Math.min(FUNCTION_MODULES.size(), ranking.size()); i++) {
            modules.add(ranking.get(i).getModuleName());
        }
        List<String> days = collectDays(query);
        List<List<Long>> values = new ArrayList<List<Long>>();
        for (String module : modules) {
            Map<String, Long> moduleData = trendMap.get(module);
            List<Long> moduleValues = new ArrayList<Long>();
            for (String day : days) {
                moduleValues.add(moduleData != null && moduleData.get(day) != null ? moduleData.get(day) : 0L);
            }
            values.add(moduleValues);
        }

        FunctionUsageTrendVO trend = new FunctionUsageTrendVO();
        trend.setModules(modules);
        trend.setDays(days);
        trend.setValues(values);
        return trend;
    }

    private FunctionUsageResponseVO emptyResponse(FunctionUsageQueryDTO query) {
        PageRequest pageRequest = PageRequest.of(query.getCurrent(), query.getSize());
        FunctionUsageTrendVO trend = new FunctionUsageTrendVO();
        trend.setModules(Collections.<String>emptyList());
        trend.setDays(collectDays(query));
        trend.setValues(Collections.<List<Long>>emptyList());

        FunctionUsageResponseVO response = new FunctionUsageResponseVO();
        response.setUsageRate("0%");
        response.setRanking(Collections.<FunctionUsageItemVO>emptyList());
        response.setRecords(Collections.<FunctionUsageItemVO>emptyList());
        response.setCurrent(pageRequest.getCurrent());
        response.setSize(pageRequest.getSize());
        response.setTrend(trend);
        return response;
    }

    private FunctionUsageQueryDTO normalizeQuery(FunctionUsageQueryDTO query) {
        FunctionUsageQueryDTO source = query != null ? query : new FunctionUsageQueryDTO();
        FunctionUsageQueryDTO normalized = new FunctionUsageQueryDTO();
        normalized.setDateFrom(source.getDateFrom());
        normalized.setDateTo(source.getDateTo());
        normalized.setIdRegion(trimToNull(source.getIdRegion()));
        normalized.setIdOrg(trimToNull(source.getIdOrg()));
        normalized.setHisOrgId(trimToNull(source.getHisOrgId()));
        normalized.setCurrent(source.getCurrent());
        normalized.setSize(source.getSize());
        normalized.setFunctionModules(resolveModules(source.getFunctionModules()));
        normalized.setDateFromTime(parseDateStart(source.getDateFrom()));
        normalized.setDateToExclusiveTime(parseDateEndExclusive(source.getDateTo()));
        return normalized;
    }

    private List<String> resolveModules(List<String> selectedModules) {
        if (selectedModules == null || selectedModules.isEmpty()) {
            return new ArrayList<String>(FUNCTION_MODULES);
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<String>();
        for (String selected : selectedModules) {
            String module = trimToNull(selected);
            if (module != null && FUNCTION_MODULES.contains(module)) {
                resolved.add(module);
            }
        }
        return new ArrayList<String>(resolved);
    }

    private FunctionUsageQueryDTO buildPreviousPeriodQuery(FunctionUsageQueryDTO current) {
        FunctionUsageQueryDTO previous = new FunctionUsageQueryDTO();
        previous.setIdRegion(current.getIdRegion());
        previous.setIdOrg(current.getIdOrg());
        previous.setHisOrgId(current.getHisOrgId());
        previous.setFunctionModules(new ArrayList<String>(current.getFunctionModules()));
        String fromValue = current.getDateFrom();
        String toValue = current.getDateTo();
        if (fromValue == null || toValue == null) {
            return previous;
        }
        try {
            LocalDate from = LocalDate.parse(fromValue, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate to = LocalDate.parse(toValue, DateTimeFormatter.ISO_LOCAL_DATE);
            long span = ChronoUnit.DAYS.between(from, to);
            LocalDate previousFrom = from.minusDays(span + 1L);
            LocalDate previousTo = from.minusDays(1L);
            previous.setDateFrom(previousFrom.format(DateTimeFormatter.ISO_LOCAL_DATE));
            previous.setDateTo(previousTo.format(DateTimeFormatter.ISO_LOCAL_DATE));
            previous.setDateFromTime(previousFrom.atStartOfDay());
            previous.setDateToExclusiveTime(previousTo.plusDays(1L).atStartOfDay());
        } catch (Exception ex) {
            log.debug("xiaoshan function usage previous period calculation failed. error={}", ex.getMessage());
        }
        return previous;
    }

    private long computeDays(FunctionUsageQueryDTO query) {
        String fromValue = query.getDateFrom();
        String toValue = query.getDateTo();
        if (fromValue == null || toValue == null) {
            return 0L;
        }
        try {
            LocalDate from = LocalDate.parse(fromValue, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate to = LocalDate.parse(toValue, DateTimeFormatter.ISO_LOCAL_DATE);
            return Math.max(0L, ChronoUnit.DAYS.between(from, to) + 1L);
        } catch (Exception ex) {
            return 0L;
        }
    }

    private List<String> collectDays(FunctionUsageQueryDTO query) {
        List<String> days = new ArrayList<String>();
        String fromValue = query.getDateFrom();
        String toValue = query.getDateTo();
        if (fromValue == null || toValue == null) {
            return days;
        }
        try {
            LocalDate cursor = LocalDate.parse(fromValue, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate end = LocalDate.parse(toValue, DateTimeFormatter.ISO_LOCAL_DATE);
            while (!cursor.isAfter(end)) {
                days.add(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE));
                cursor = cursor.plusDays(1L);
            }
        } catch (Exception ex) {
            log.debug("xiaoshan function usage day collection failed. error={}", ex.getMessage());
        }
        return days;
    }

    private LocalDateTime parseDateStart(String value) {
        try {
            return value != null ? LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDateTime parseDateEndExclusive(String value) {
        try {
            return value != null ? LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).plusDays(1L).atStartOfDay() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String formatUsageRate(int activeModules) {
        return String.valueOf(Math.round((double) activeModules / FUNCTION_MODULES.size() * 100D)) + "%";
    }

    private String formatGrowth(long current, long previous) {
        if (previous == 0L) {
            return current > 0L ? "100" : "0";
        }
        double growth = (double) (current - previous) / previous * 100D;
        return new BigDecimal(growth).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private List<FunctionUsageItemVO> safeItems(List<FunctionUsageItemVO> items) {
        return items != null ? items : new ArrayList<FunctionUsageItemVO>();
    }

    private List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
        return rows != null ? rows : new ArrayList<Map<String, Object>>();
    }

    private String stringValue(Map<String, Object> row, String... keys) {
        Object value = mapValue(row, keys);
        return value != null ? String.valueOf(value) : null;
    }

    private long longValue(Map<String, Object> row, String... keys) {
        Object value = mapValue(row, keys);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private Object mapValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void writeTrendSheet(XSSFSheet sheet, CellStyle headerStyle, FunctionUsageTrendVO trend) {
        List<String> modules = trend != null && trend.getModules() != null
            ? trend.getModules() : Collections.<String>emptyList();
        List<String> days = trend != null && trend.getDays() != null
            ? trend.getDays() : Collections.<String>emptyList();
        List<List<Long>> values = trend != null && trend.getValues() != null
            ? trend.getValues() : Collections.<List<Long>>emptyList();
        Row header = sheet.createRow(0);
        createCell(header, 0, "日期", headerStyle);
        for (int i = 0; i < modules.size(); i++) {
            createCell(header, i + 1, modules.get(i), headerStyle);
        }
        for (int dayIndex = 0; dayIndex < days.size(); dayIndex++) {
            Row row = sheet.createRow(dayIndex + 1);
            createCell(row, 0, days.get(dayIndex), null);
            for (int moduleIndex = 0; moduleIndex < modules.size(); moduleIndex++) {
                List<Long> moduleValues = moduleIndex < values.size() ? values.get(moduleIndex) : null;
                long value = moduleValues != null && dayIndex < moduleValues.size() && moduleValues.get(dayIndex) != null
                    ? moduleValues.get(dayIndex) : 0L;
                createCell(row, moduleIndex + 1, value, null);
            }
        }
        ExcelColumnWidthUtils.fitColumns(sheet, modules.size() + 1);
    }

    private static CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        return headerStyle;
    }

    private static void writeHeader(XSSFSheet sheet, CellStyle headerStyle, String... headers) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            createCell(header, i, headers[i], headerStyle);
        }
    }

    private static void writeRow(XSSFSheet sheet, int rowIndex, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            createCell(row, i, values[i], null);
        }
    }

    private static void createCell(Row row, int columnIndex, Object value, CellStyle style) {
        Cell cell = row.createCell(columnIndex);
        if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else {
            cell.setCellValue(value != null ? String.valueOf(value) : "");
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static byte[] writeWorkbook(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
