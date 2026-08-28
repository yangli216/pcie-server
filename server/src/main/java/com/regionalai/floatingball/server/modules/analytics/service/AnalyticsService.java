package com.regionalai.floatingball.server.modules.analytics.service;

import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsSummaryVO;
import com.regionalai.floatingball.server.modules.analytics.dto.DistributionDataVO;
import com.regionalai.floatingball.server.modules.analytics.dto.DistributionItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageTrendVO;
import com.regionalai.floatingball.server.modules.analytics.dto.HisOrgOptionVO;
import com.regionalai.floatingball.server.modules.analytics.dto.RegionDistributionItemVO;
import com.regionalai.floatingball.server.modules.analytics.dto.TrendDataVO;
import com.regionalai.floatingball.server.modules.analytics.mapper.AnalyticsMapper;
import com.regionalai.floatingball.server.modules.audit.service.AuditLogDisplayCatalog;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.ExcelColumnWidthUtils;
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
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final List<String> FUNCTION_USAGE_FEATURES = java.util.Arrays.asList(
        "语音问诊",
        "智能问诊",
        "报告单解读",
        "聊天",
        "AI诊断鉴别",
        "AI推荐诊断",
        "AI推荐用药",
        "AI推荐检查",
        "AI推荐检验",
        "AI推荐处置",
        "AI推荐治疗方案",
        "知识库使用"
    );
    private static final Map<String, String> FUNCTION_USAGE_ALIASES = buildFunctionUsageAliases();

    private final AnalyticsMapper analyticsMapper;

    public AnalyticsService(AnalyticsMapper analyticsMapper,
                            AuditLogDisplayCatalog displayCatalog) {
        this.analyticsMapper = analyticsMapper;
    }

    public AnalyticsSummaryVO getSummary(AnalyticsQueryDTO query) {
        fillDateBounds(query);
        AnalyticsSummaryVO vo = new AnalyticsSummaryVO();

        long aiServiceTotal = analyticsMapper.countAiService(query);
        long consultationTotal = analyticsMapper.countConsultation(query);
        long activeDoctorCount = analyticsMapper.countActiveDoctors(query);
        long adoptedCount = analyticsMapper.countAdoptedConsultations(query);
        long finalizedCount = analyticsMapper.countFinalizedConsultations(query);
        long diagnosisMatchedCount = analyticsMapper.countDiagnosisMatchedConsultations(query);

        long days = computeDays(query);
        long avgDaily = days > 0 ? aiServiceTotal / days : 0;

        String adoptionRate = consultationTotal > 0
            ? formatPercent((double) adoptedCount / consultationTotal * 100) : "0";
        String matchRate = finalizedCount > 0
            ? formatPercent((double) diagnosisMatchedCount / finalizedCount * 100) : "0";

        vo.setAiServiceTotal(aiServiceTotal);
        vo.setAvgDailyAiService(avgDaily);
        vo.setAiAdoptionRate(adoptionRate);
        vo.setDiagnosisMatchRate(matchRate);
        vo.setActiveDoctorCount(activeDoctorCount);
        vo.setConsultationTotal(consultationTotal);

        AnalyticsQueryDTO prevQuery = buildPreviousPeriodQuery(query);
        long prevAiService = analyticsMapper.countAiService(prevQuery);
        long prevConsultation = analyticsMapper.countConsultation(prevQuery);
        long prevActiveDoctors = analyticsMapper.countActiveDoctors(prevQuery);
        long prevAdopted = analyticsMapper.countAdoptedConsultations(prevQuery);
        long prevFinalized = analyticsMapper.countFinalizedConsultations(prevQuery);
        long prevDiagnosisMatched = analyticsMapper.countDiagnosisMatchedConsultations(prevQuery);

        long prevDays = computeDays(prevQuery);
        long prevAvgDaily = prevDays > 0 ? prevAiService / prevDays : 0;

        vo.setAiServiceGrowth(formatGrowth(aiServiceTotal, prevAiService));
        vo.setAvgDailyGrowth(formatGrowth(avgDaily, prevAvgDaily));

        double prevAdoption = prevConsultation > 0 ? (double) prevAdopted / prevConsultation * 100 : 0;
        double curAdoption = consultationTotal > 0 ? (double) adoptedCount / consultationTotal * 100 : 0;
        vo.setAdoptionRateGrowth(formatPercentGrowth(curAdoption, prevAdoption));

        double prevMatch = prevFinalized > 0 ? (double) prevDiagnosisMatched / prevFinalized * 100 : 0;
        double curMatch = finalizedCount > 0 ? (double) diagnosisMatchedCount / finalizedCount * 100 : 0;
        vo.setMatchRateGrowth(formatPercentGrowth(curMatch, prevMatch));
        vo.setActiveDoctorGrowth(formatAbsoluteGrowth(activeDoctorCount, prevActiveDoctors));
        vo.setConsultationGrowth(formatGrowth(consultationTotal, prevConsultation));

        return vo;
    }

    public TrendDataVO getTrend(AnalyticsQueryDTO query) {
        fillDateBounds(query);
        TrendDataVO vo = new TrendDataVO();
        List<Map<String, Object>> aiRows = analyticsMapper.queryAiServiceTrend(query);
        List<Map<String, Object>> consRows = analyticsMapper.queryConsultationTrend(query);

        Map<String, Long> aiMap = toDayMap(aiRows);
        Map<String, Long> consMap = toDayMap(consRows);

        List<String> days = new ArrayList<>();
        List<Long> aiValues = new ArrayList<>();
        List<Long> consValues = new ArrayList<>();

        String fromStr = query.getDateFrom();
        String toStr = query.getDateTo();
        if (fromStr != null && !fromStr.isEmpty() && toStr != null && !toStr.isEmpty()) {
            LocalDate start = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate end = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate cursor = start;
            while (!cursor.isAfter(end)) {
                String day = cursor.format(DateTimeFormatter.ISO_LOCAL_DATE);
                days.add(day);
                aiValues.add(aiMap.getOrDefault(day, 0L));
                consValues.add(consMap.getOrDefault(day, 0L));
                cursor = cursor.plusDays(1);
            }
        } else {
            for (String day : aiMap.keySet()) {
                days.add(day);
                aiValues.add(aiMap.get(day));
                consValues.add(consMap.getOrDefault(day, 0L));
            }
        }

        vo.setDays(days);
        vo.setAiServiceValues(aiValues);
        vo.setConsultationValues(consValues);
        return vo;
    }

    public DistributionDataVO getDistribution(AnalyticsQueryDTO query) {
        fillDateBounds(query);
        DistributionDataVO vo = new DistributionDataVO();

        List<DistributionItemVO> orgDist = analyticsMapper.queryOrgDistribution(query);
        List<DistributionItemVO> rawRegion = analyticsMapper.queryRegionDistributionRaw(query);
        List<DistributionItemVO> consultationOrgDist = analyticsMapper.queryConsultationOrgDistribution(query);
        List<DistributionItemVO> rawConsultationRegion = analyticsMapper.queryConsultationRegionDistributionRaw(query);

        long totalService = sumDistribution(orgDist);
        long serviceRegionTotal = sumDistribution(rawRegion);
        long totalConsultation = sumDistribution(consultationOrgDist);
        long consultationRegionTotal = sumDistribution(rawConsultationRegion);

        vo.setOrgDistribution(orgDist);
        vo.setRegionDistribution(toRegionDistribution(rawRegion, serviceRegionTotal));
        vo.setTotalService(totalService > 0 ? totalService : serviceRegionTotal);
        vo.setConsultationOrgDistribution(consultationOrgDist);
        vo.setConsultationRegionDistribution(toRegionDistribution(rawConsultationRegion, consultationRegionTotal));
        vo.setTotalConsultation(totalConsultation > 0 ? totalConsultation : consultationRegionTotal);
        return vo;
    }

    public byte[] exportAnalyticsExcel(AnalyticsQueryDTO query) {
        AnalyticsSummaryVO summary = getSummary(query);
        TrendDataVO trend = getTrend(query);
        DistributionDataVO distribution = getDistribution(query);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            XSSFSheet summarySheet = workbook.createSheet("核心指标");
            writeHeader(summarySheet, headerStyle, "指标", "数值", "对比变化");
            writeRow(summarySheet, 1, "功能调用总量", summary.getAiServiceTotal(), summary.getAiServiceGrowth() + "%");
            writeRow(summarySheet, 2, "日均功能调用量", summary.getAvgDailyAiService(), summary.getAvgDailyGrowth() + "%");
            writeRow(summarySheet, 3, "AI诊断建议采纳率", summary.getAiAdoptionRate() + "%", summary.getAdoptionRateGrowth() + "%");
            writeRow(summarySheet, 4, "诊断符合率", summary.getDiagnosisMatchRate() + "%", summary.getMatchRateGrowth() + "%");
            writeRow(summarySheet, 5, "活跃医生数", summary.getActiveDoctorCount(), summary.getActiveDoctorGrowth());
            writeRow(summarySheet, 6, "问诊总数", summary.getConsultationTotal(), summary.getConsultationGrowth() + "%");
            autoSize(summarySheet, 3);

            XSSFSheet trendSheet = workbook.createSheet("趋势明细");
            writeHeader(trendSheet, headerStyle, "日期", "功能调用量", "问诊量");
            List<String> days = trend.getDays() != null ? trend.getDays() : new ArrayList<String>();
            List<Long> aiValues = trend.getAiServiceValues() != null ? trend.getAiServiceValues() : new ArrayList<Long>();
            List<Long> consultationValues = trend.getConsultationValues() != null ? trend.getConsultationValues() : new ArrayList<Long>();
            for (int i = 0; i < days.size(); i++) {
                writeRow(trendSheet, i + 1, days.get(i), valueAt(aiValues, i), valueAt(consultationValues, i));
            }
            autoSize(trendSheet, 3);

            XSSFSheet orgSheet = workbook.createSheet("机构分布");
            writeHeader(orgSheet, headerStyle, "HIS机构", "功能调用量");
            List<DistributionItemVO> orgItems = distribution.getOrgDistribution() != null ? distribution.getOrgDistribution() : new ArrayList<DistributionItemVO>();
            for (int i = 0; i < orgItems.size(); i++) {
                DistributionItemVO item = orgItems.get(i);
                writeRow(orgSheet, i + 1, item.getName(), safeLong(item.getValue()));
            }
            autoSize(orgSheet, 2);

            XSSFSheet regionSheet = workbook.createSheet("区域分布");
            writeHeader(regionSheet, headerStyle, "区域", "功能调用量", "占比");
            List<RegionDistributionItemVO> regionItems = distribution.getRegionDistribution() != null ? distribution.getRegionDistribution() : new ArrayList<RegionDistributionItemVO>();
            for (int i = 0; i < regionItems.size(); i++) {
                RegionDistributionItemVO item = regionItems.get(i);
                writeRow(regionSheet, i + 1, item.getName(), safeLong(item.getValue()), item.getPercentage() + "%");
            }
            autoSize(regionSheet, 3);

            XSSFSheet consultationOrgSheet = workbook.createSheet("问诊HIS机构分布");
            writeHeader(consultationOrgSheet, headerStyle, "HIS机构", "问诊量");
            List<DistributionItemVO> consultationOrgItems = distribution.getConsultationOrgDistribution() != null
                ? distribution.getConsultationOrgDistribution() : new ArrayList<DistributionItemVO>();
            for (int i = 0; i < consultationOrgItems.size(); i++) {
                DistributionItemVO item = consultationOrgItems.get(i);
                writeRow(consultationOrgSheet, i + 1, item.getName(), safeLong(item.getValue()));
            }
            autoSize(consultationOrgSheet, 2);

            XSSFSheet consultationRegionSheet = workbook.createSheet("问诊区域分布");
            writeHeader(consultationRegionSheet, headerStyle, "区域", "问诊量", "占比");
            List<RegionDistributionItemVO> consultationRegionItems = distribution.getConsultationRegionDistribution() != null
                ? distribution.getConsultationRegionDistribution() : new ArrayList<RegionDistributionItemVO>();
            for (int i = 0; i < consultationRegionItems.size(); i++) {
                RegionDistributionItemVO item = consultationRegionItems.get(i);
                writeRow(consultationRegionSheet, i + 1, item.getName(), safeLong(item.getValue()), item.getPercentage() + "%");
            }
            autoSize(consultationRegionSheet, 3);

            return writeWorkbook(workbook);
        } catch (IOException ex) {
            throw new BusinessException("导出Excel失败：" + ex.getMessage());
        }
    }

    private long computeDays(AnalyticsQueryDTO query) {
        String fromStr = query.getDateFrom();
        String toStr = query.getDateTo();
        if (fromStr == null || fromStr.isEmpty() || toStr == null || toStr.isEmpty()) {
            return 30;
        }
        try {
            LocalDate from = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate to = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);
        } catch (Exception e) {
            return 30;
        }
    }

    private static long sumDistribution(List<DistributionItemVO> items) {
        long total = 0L;
        if (items == null) {
            return total;
        }
        for (DistributionItemVO item : items) {
            if (item != null && item.getValue() != null) {
                total += item.getValue();
            }
        }
        return total;
    }

    private static List<RegionDistributionItemVO> toRegionDistribution(List<DistributionItemVO> rawItems, long total) {
        List<RegionDistributionItemVO> result = new ArrayList<>();
        if (rawItems == null) {
            return result;
        }
        for (DistributionItemVO raw : rawItems) {
            RegionDistributionItemVO item = new RegionDistributionItemVO();
            item.setName(raw.getName());
            item.setValue(raw.getValue());
            long value = raw.getValue() != null ? raw.getValue() : 0L;
            item.setPercentage(total > 0 ? String.valueOf(Math.round((double) value / total * 100)) : "0");
            result.add(item);
        }
        return result;
    }

    private static final WeekFields WEEK_FIELDS = WeekFields.of(Locale.CHINA);

    private AnalyticsQueryDTO buildPreviousPeriodQuery(AnalyticsQueryDTO current) {
        AnalyticsQueryDTO prev = new AnalyticsQueryDTO();
        prev.setIdOrg(current.getIdOrg());
        prev.setIdRegion(current.getIdRegion());
        prev.setHisOrgId(current.getHisOrgId());

        String fromStr = current.getDateFrom();
        String toStr = current.getDateTo();
        if (fromStr == null || fromStr.isEmpty() || toStr == null || toStr.isEmpty()) {
            return prev;
        }
        try {
            LocalDate from = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate to = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
            DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

            String range = current.getTimeRange();
            if ("today".equals(range)) {
                LocalDate yesterday = from.minusDays(1);
                prev.setDateFrom(yesterday.format(fmt));
                prev.setDateTo(yesterday.format(fmt));
            } else if ("week".equals(range)) {
                LocalDate prevWeekEnd = from.minusDays(1);
                LocalDate prevWeekStart = prevWeekEnd.minusDays(6);
                prev.setDateFrom(prevWeekStart.format(fmt));
                prev.setDateTo(prevWeekEnd.format(fmt));
            } else if ("month".equals(range)) {
                LocalDate prevMonthEnd = from.minusDays(1);
                LocalDate prevMonthStart = prevMonthEnd.withDayOfMonth(1);
                prev.setDateFrom(prevMonthStart.format(fmt));
                prev.setDateTo(prevMonthEnd.format(fmt));
            } else if ("quarter".equals(range)) {
                LocalDate prevQuarterEnd = from.minusDays(1);
                int prevQuarterMonth = ((prevQuarterEnd.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDate prevQuarterStart = LocalDate.of(prevQuarterEnd.getYear(), prevQuarterMonth, 1);
                prev.setDateFrom(prevQuarterStart.format(fmt));
                prev.setDateTo(prevQuarterEnd.format(fmt));
            } else if ("year".equals(range)) {
                LocalDate prevYearEnd = from.minusDays(1);
                LocalDate prevYearStart = LocalDate.of(prevYearEnd.getYear(), 1, 1);
                prev.setDateFrom(prevYearStart.format(fmt));
                prev.setDateTo(prevYearEnd.format(fmt));
            } else {
                long span = ChronoUnit.DAYS.between(from, to);
                LocalDate prevFrom = from.minusDays(span + 1);
                LocalDate prevTo = from.minusDays(1);
                prev.setDateFrom(prevFrom.format(fmt));
                prev.setDateTo(prevTo.format(fmt));
            }
        } catch (Exception ex) {
            log.debug("analytics previous period calculation failed. error={}", ex.getMessage());
        }
        fillDateBounds(prev);
        return prev;
    }

    private Map<String, Long> toDayMap(List<Map<String, Object>> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (rows == null) {
            return map;
        }
        for (Map<String, Object> row : rows) {
            String day = String.valueOf(mapValue(row, "DAY_STR", "day_str"));
            Object cntObj = mapValue(row, "CNT", "cnt");
            long cnt = 0;
            if (cntObj instanceof Number) {
                cnt = ((Number) cntObj).longValue();
            }
            map.put(day, cnt);
        }
        return map;
    }

    private String formatPercent(double value) {
        return new BigDecimal(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String formatGrowth(long current, long previous) {
        if (previous == 0) {
            return current > 0 ? "100" : "0";
        }
        double growth = (double) (current - previous) / previous * 100;
        return new BigDecimal(growth).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String formatPercentGrowth(double current, double previous) {
        if (previous == 0) {
            return current > 0 ? "100" : "0";
        }
        double growth = current - previous;
        return new BigDecimal(growth).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String formatAbsoluteGrowth(long current, long previous) {
        long diff = current - previous;
        return String.valueOf(diff);
    }

    public FunctionUsageResponseVO getFunctionUsage(FunctionUsageQueryDTO query) {
        FunctionUsageQueryDTO normalizedQuery = normalizeFunctionUsageQuery(query);
        FunctionUsageResponseVO vo = new FunctionUsageResponseVO();

        List<FunctionUsageItemVO> ranking = analyticsMapper.queryFunctionUsageRanking(normalizedQuery);

        long totalCallCount = 0;
        for (FunctionUsageItemVO item : ranking) {
            totalCallCount += item.getCallCount();
        }

        long days = computeDaysFromFunctionQuery(normalizedQuery);
        long avgDaily = days > 0 ? totalCallCount / days : 0;

        long activeModuleCount = ranking.size();
        long totalModules = analyticsMapper.queryDistinctModules().size();
        String usageRate = totalModules > 0 ? String.valueOf(Math.round((double) activeModuleCount / totalModules * 100)) + "%" : "0%";

        vo.setTotalCallCount(totalCallCount);
        vo.setAvgDailyCalls(avgDaily);
        vo.setUsageRate(usageRate);

        FunctionUsageQueryDTO prevQuery = buildPreviousFunctionUsageQuery(normalizedQuery);
        List<FunctionUsageItemVO> prevRanking = analyticsMapper.queryFunctionUsagePreviousRanking(prevQuery);
        Map<String, Long> prevCallMap = new LinkedHashMap<>();
        for (FunctionUsageItemVO item : prevRanking) {
            prevCallMap.put(item.getModuleName(), item.getCallCount());
        }
        for (FunctionUsageItemVO item : ranking) {
            Long prev = prevCallMap.get(item.getModuleName());
            if (prev == null) prev = 0L;
            item.setGrowthRate(formatGrowth(item.getCallCount(), prev));
        }

        vo.setRanking(ranking);
        vo.setTotal((long) ranking.size());

        PageRequest pageRequest = PageRequest.of(normalizedQuery.getCurrent(), normalizedQuery.getSize());
        int from = pageRequest.fromIndex(ranking.size());
        int to = pageRequest.toIndex(ranking.size());
        vo.setCurrent(pageRequest.getCurrent());
        vo.setSize(pageRequest.getSize());
        vo.setRecords(ranking.subList(from, to));

        List<Map<String, Object>> trendRows = analyticsMapper.queryFunctionUsageTrend(normalizedQuery);
        Map<String, Map<String, Long>> trendMap = new LinkedHashMap<>();
        for (Map<String, Object> row : trendRows) {
            String module = String.valueOf(mapValue(row, "MODULENAME", "modulename"));
            String day = String.valueOf(mapValue(row, "DAYSTR", "daystr"));
            Object cntObj = mapValue(row, "CNT", "cnt");
            long cnt = cntObj instanceof Number ? ((Number) cntObj).longValue() : 0L;
            trendMap.computeIfAbsent(module, k -> new LinkedHashMap<>()).put(day, cnt);
        }

        List<String> topModules = new ArrayList<>();
        int limit = Math.min(5, ranking.size());
        for (int i = 0; i < limit; i++) {
            topModules.add(ranking.get(i).getModuleName());
        }

        List<String> allDays = collectDays(normalizedQuery);
        List<List<Long>> trendValues = new ArrayList<>();

        for (String module : topModules) {
            Map<String, Long> moduleData = trendMap.getOrDefault(module, new LinkedHashMap<>());
            List<Long> vals = new ArrayList<>();
            for (String day : allDays) {
                vals.add(moduleData.getOrDefault(day, 0L));
            }
            trendValues.add(vals);
        }

        FunctionUsageTrendVO trendVO = new FunctionUsageTrendVO();
        trendVO.setModules(topModules);
        trendVO.setDays(allDays);
        trendVO.setValues(trendValues);
        vo.setTrend(trendVO);

        return vo;
    }

    public byte[] exportFunctionUsageExcel(FunctionUsageQueryDTO query) {
        FunctionUsageQueryDTO exportQuery = normalizeFunctionUsageQuery(query);
        exportQuery.setCurrent(1);
        exportQuery.setSize(Integer.MAX_VALUE);
        FunctionUsageResponseVO usage = getFunctionUsage(exportQuery);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            XSSFSheet summarySheet = workbook.createSheet("汇总指标");
            writeHeader(summarySheet, headerStyle, "指标", "数值");
            writeRow(summarySheet, 1, "总调用次数", usage.getTotalCallCount());
            writeRow(summarySheet, 2, "平均每日调用", usage.getAvgDailyCalls());
            writeRow(summarySheet, 3, "功能使用率", usage.getUsageRate());
            autoSize(summarySheet, 2);

            XSSFSheet rankingSheet = workbook.createSheet("功能排行");
            writeHeader(rankingSheet, headerStyle, "功能模块", "调用次数", "使用医生数", "人均调用", "增长率(%)");
            List<FunctionUsageItemVO> ranking = usage.getRanking() != null ? usage.getRanking() : new ArrayList<FunctionUsageItemVO>();
            for (int i = 0; i < ranking.size(); i++) {
                FunctionUsageItemVO item = ranking.get(i);
                writeRow(
                    rankingSheet,
                    i + 1,
                    item.getModuleName(),
                    item.getCallCount(),
                    item.getDoctorCount(),
                    item.getAvgPerDoctor(),
                    item.getGrowthRate()
                );
            }
            autoSize(rankingSheet, 5);

            XSSFSheet trendSheet = workbook.createSheet("趋势明细");
            FunctionUsageTrendVO trend = usage.getTrend();
            List<String> modules = trend != null && trend.getModules() != null ? trend.getModules() : new ArrayList<String>();
            List<String> days = trend != null && trend.getDays() != null ? trend.getDays() : new ArrayList<String>();
            List<List<Long>> values = trend != null && trend.getValues() != null ? trend.getValues() : new ArrayList<List<Long>>();

            Row header = trendSheet.createRow(0);
            createCell(header, 0, "日期", headerStyle);
            for (int i = 0; i < modules.size(); i++) {
                createCell(header, i + 1, modules.get(i), headerStyle);
            }
            for (int i = 0; i < days.size(); i++) {
                Row row = trendSheet.createRow(i + 1);
                createCell(row, 0, days.get(i), null);
                for (int m = 0; m < modules.size(); m++) {
                    List<Long> moduleValues = m < values.size() ? values.get(m) : new ArrayList<Long>();
                    createCell(row, m + 1, valueAt(moduleValues, i), null);
                }
            }
            autoSize(trendSheet, modules.size() + 1);

            return writeWorkbook(workbook);
        } catch (IOException ex) {
            throw new BusinessException("导出Excel失败：" + ex.getMessage());
        }
    }

    private long computeDaysFromFunctionQuery(FunctionUsageQueryDTO query) {
        String fromStr = query.getDateFrom();
        String toStr = query.getDateTo();
        if (fromStr == null || fromStr.isEmpty() || toStr == null || toStr.isEmpty()) {
            return 30;
        }
        try {
            LocalDate from = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate to = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);
        } catch (Exception e) {
            return 30;
        }
    }

    private FunctionUsageQueryDTO buildPreviousFunctionUsageQuery(FunctionUsageQueryDTO current) {
        FunctionUsageQueryDTO prev = new FunctionUsageQueryDTO();
        prev.setIdOrg(current.getIdOrg());
        prev.setIdRegion(current.getIdRegion());
        prev.setHisOrgId(current.getHisOrgId());
        prev.setFunctionModules(current.getFunctionModules());

        String fromStr = current.getDateFrom();
        String toStr = current.getDateTo();
        if (fromStr == null || fromStr.isEmpty() || toStr == null || toStr.isEmpty()) {
            return prev;
        }
        try {
            LocalDate from = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate to = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
            long span = ChronoUnit.DAYS.between(from, to);
            LocalDate prevFrom = from.minusDays(span + 1);
            LocalDate prevTo = from.minusDays(1);
            prev.setDateFrom(prevFrom.format(DateTimeFormatter.ISO_LOCAL_DATE));
            prev.setDateTo(prevTo.format(DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (Exception ex) {
            log.debug("analytics function usage previous period calculation failed. error={}", ex.getMessage());
        }
        fillDateBounds(prev);
        return prev;
    }

    private List<String> collectDays(FunctionUsageQueryDTO query) {
        List<String> days = new ArrayList<>();
        String fromStr = query.getDateFrom();
        String toStr = query.getDateTo();
        if (fromStr == null || fromStr.isEmpty() || toStr == null || toStr.isEmpty()) {
            return days;
        }
        try {
            LocalDate start = LocalDate.parse(fromStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate end = LocalDate.parse(toStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate cursor = start;
            while (!cursor.isAfter(end)) {
                days.add(cursor.format(DateTimeFormatter.ISO_LOCAL_DATE));
                cursor = cursor.plusDays(1);
            }
        } catch (Exception ex) {
            log.debug("analytics day collection failed. error={}", ex.getMessage());
        }
        return days;
    }

    private FunctionUsageQueryDTO normalizeFunctionUsageQuery(FunctionUsageQueryDTO query) {
        FunctionUsageQueryDTO normalized = new FunctionUsageQueryDTO();
        normalized.setDateFrom(query.getDateFrom());
        normalized.setDateTo(query.getDateTo());
        normalized.setIdOrg(query.getIdOrg());
        normalized.setIdRegion(query.getIdRegion());
        normalized.setHisOrgId(query.getHisOrgId());
        normalized.setCurrent(query.getCurrent());
        normalized.setSize(query.getSize());
        normalized.setFunctionModules(resolveFunctionModules(query.getFunctionModules()));
        fillDateBounds(normalized);
        return normalized;
    }

    private List<String> resolveFunctionModules(List<String> selectedModules) {
        if (selectedModules == null || selectedModules.isEmpty()) {
            return selectedModules;
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<String>();
        for (String selectedModule : selectedModules) {
            String feature = resolveFunctionUsageFeature(selectedModule);
            if (feature != null) {
                resolved.add(feature);
            }
        }
        return new ArrayList<String>(resolved);
    }

    public List<String> getFunctionModuleOptions() {
        List<String> modules = analyticsMapper.queryDistinctModules();
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (String m : modules) {
            result.add(m);
        }
        return new ArrayList<String>(result);
    }

    public List<HisOrgOptionVO> getHisOrgOptions() {
        return analyticsMapper.queryHisOrgOptions();
    }

    private static Map<String, String> buildFunctionUsageAliases() {
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        registerFunctionUsageAliases(aliases, "语音问诊",
            "语音问诊", "语音问诊AI", "语音录入", "语音采集", "语音胶囊", "语音结果页", "voice_consultation_ai",
            "voice_intent", "voice_consultation_result", "voice_capsule", "voice_consultation", "voice_capture",
            "speech", "speech_proxy", "aliyunSpeech", "start_voice_consultation", "start_voice_capture",
            "open_voice_consultation", "extract_voice_record", "speech_transcribe", "speech_realtime", "transcribe", "realtime"
        );
        registerFunctionUsageAliases(aliases, "智能问诊",
            "智能问诊", "问诊", "问诊AI", "智能问诊页", "问诊病历", "问诊引用", "HIS桥接", "consultation",
            "consultation_page", "consultation_record", "consultation_reference", "his_bridge", "open_consultation",
            "start_consultation", "start_consultation_assist", "generate_medical_record", "submit_to_his", "complete_consultation"
        );
        registerFunctionUsageAliases(aliases, "报告单解读",
            "报告单解读", "报告解读", "检验检查报告解读", "report_interpretation", "report-interpretation",
            "build_report_interpretation", "his_start_report_interpretation"
        );
        registerFunctionUsageAliases(aliases, "聊天",
            "聊天", "AI对话", "聊天助手", "聊天面板", "chat", "chat_panel", "llm", "chat_stream", "stream_reply", "send_message"
        );
        registerFunctionUsageAliases(aliases, "AI诊断鉴别",
            "AI诊断鉴别", "诊断鉴别", "鉴别排查", "consultation_checklist", "generate_diagnosis_checklist",
            "confirm_differential_checklist", "consultation-diagnosis-checklist"
        );
        registerFunctionUsageAliases(aliases, "AI推荐诊断",
            "AI推荐诊断", "诊断建议", "诊断推荐", "生成诊断推荐", "generate_diagnosis_recommendation",
            "generate_tcm_diagnosis_recommendation", "consultation-diagnosis", "voice-consultation-diagnosis"
        );
        registerFunctionUsageAliases(aliases, "AI推荐用药",
            "AI推荐用药", "用药推荐", "治疗推荐", "推荐用药", "generate_treatment_recommendation",
            "generate_tcm_treatment_recommendation", "consultation-treatment-medication", "voice-consultation-treatment-medication"
        );
        registerFunctionUsageAliases(aliases, "AI推荐检查",
            "AI推荐检查", "检查推荐", "推荐检查", "generate_examination_recommendation",
            "consultation-treatment-examination", "voice-consultation-treatment-examination"
        );
        registerFunctionUsageAliases(aliases, "AI推荐检验",
            "AI推荐检验", "检验推荐", "推荐检验", "generate_lab_test_recommendation",
            "consultation-treatment-lab-test", "voice-consultation-treatment-lab-test"
        );
        registerFunctionUsageAliases(aliases, "AI推荐处置",
            "AI推荐处置", "处置推荐", "推荐处置", "generate_procedure_recommendation",
            "consultation-treatment-procedure", "voice-consultation-treatment-procedure"
        );
        registerFunctionUsageAliases(aliases, "AI推荐治疗方案",
            "AI推荐治疗方案", "AI诊疗方案推荐", "推荐治疗方案", "治疗方案推荐", "诊疗方案推荐", "诊疗方案", "聚合诊疗方案", "treatment_plan_recommendation",
            "treatment_plan", "consultation-treatment-plan", "consultation-assist-treatment-plan",
            "consultation-assist-treatment_plan", "open_treatment_plan_assist", "generate_treatment_plan_recommendation"
        );
        registerFunctionUsageAliases(aliases, "知识库使用",
            "知识库使用", "知识库查询", "知识库", "知识库页", "knowledge_base", "pmphai", "knowledge_panel", "knowledge-base"
        );
        return aliases;
    }

    private static void registerFunctionUsageAliases(Map<String, String> aliases, String feature, String... values) {
        aliases.put(normalizeFeatureKey(feature), feature);
        if (values == null) {
            return;
        }
        for (String value : values) {
            String key = normalizeFeatureKey(value);
            if (key != null) {
                aliases.put(key, feature);
            }
        }
    }

    private String resolveFunctionUsageFeature(String value) {
        String key = normalizeFeatureKey(value);
        if (key == null) {
            return null;
        }
        String direct = FUNCTION_USAGE_ALIASES.get(key);
        if (direct != null) {
            return direct;
        }
        for (String feature : FUNCTION_USAGE_FEATURES) {
            String featureKey = normalizeFeatureKey(feature);
            if (featureKey != null && (key.contains(featureKey) || featureKey.contains(key))) {
                return feature;
            }
        }
        return value.trim();
    }

    private static String normalizeFeatureKey(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    private static CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        return headerStyle;
    }

    private static void writeHeader(XSSFSheet sheet, CellStyle headerStyle, String... headers) {
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            createCell(headerRow, i, headers[i], headerStyle);
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

    private static void autoSize(XSSFSheet sheet, int columns) {
        ExcelColumnWidthUtils.fitColumns(sheet, columns);
    }

    private static long valueAt(List<Long> values, int index) {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            return 0L;
        }
        return values.get(index);
    }

    private static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private void fillDateBounds(AnalyticsQueryDTO query) {
        if (query == null) {
            return;
        }
        query.setDateFromTime(parseDateStart(query.getDateFrom()));
        query.setDateToExclusiveTime(parseDateEndExclusive(query.getDateTo()));
    }

    private void fillDateBounds(FunctionUsageQueryDTO query) {
        if (query == null) {
            return;
        }
        query.setDateFromTime(parseDateStart(query.getDateFrom()));
        query.setDateToExclusiveTime(parseDateEndExclusive(query.getDateTo()));
    }

    private LocalDateTime parseDateStart(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDateTime parseDateEndExclusive(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).plusDays(1).atStartOfDay();
        } catch (Exception ex) {
            return null;
        }
    }

    private Object mapValue(Map<String, Object> row, String upperKey, String lowerKey) {
        Object value = row.get(upperKey);
        return value != null ? value : row.get(lowerKey);
    }

    private static byte[] writeWorkbook(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
