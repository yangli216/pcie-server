package com.regionalai.floatingball.server.modules.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.security.dto.SecurityDistributionVO;
import com.regionalai.floatingball.server.modules.security.dto.SecurityDistributionVO.DistributionItem;
import com.regionalai.floatingball.server.modules.security.dto.SecurityQueryDTO;
import com.regionalai.floatingball.server.modules.security.dto.SecuritySummaryVO;
import com.regionalai.floatingball.server.modules.security.dto.SecurityTrendVO;
import com.regionalai.floatingball.server.modules.security.entity.SecurityRejectionLog;
import com.regionalai.floatingball.server.modules.security.mapper.SecurityRejectionLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class SecurityRejectionLogService {

    private static final Logger log = LoggerFactory.getLogger(SecurityRejectionLogService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SecurityRejectionLogMapper rejectionLogMapper;
    private final DatabaseDialect databaseDialect;

    public SecurityRejectionLogService(SecurityRejectionLogMapper rejectionLogMapper,
                                       DatabaseDialect databaseDialect) {
        this.rejectionLogMapper = rejectionLogMapper;
        this.databaseDialect = databaseDialect;
    }

    @Async
    public void logRejection(RejectionRecord record) {
        try {
            SecurityRejectionLog entity = new SecurityRejectionLog();
            entity.setRejectionType(record.rejectionType);
            entity.setRequestMethod(record.requestMethod);
            entity.setRequestPath(record.requestPath);
            entity.setClientIp(record.clientIp);
            entity.setIdDevice(record.idDevice);
            entity.setCdDevice(record.cdDevice);
            entity.setIdOrg(record.idOrg);
            entity.setRequestId(record.requestId);
            entity.setRejectReason(record.rejectReason);
            entity.setRejectDetail(truncate(record.rejectDetail, 500));
            entity.setHasSignature(record.hasSignature ? "1" : "0");
            entity.setTimestampHeader(truncate(record.timestampHeader, 32));
            entity.setNonceHeader(truncate(record.nonceHeader, 64));
            entity.setClientVersion(truncate(record.clientVersion, 32));
            entity.setUpdateChannel(truncate(record.updateChannel, 32));
            entity.setFgActive("1");
            rejectionLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("failed to persist security rejection log. type={}, path={}, deviceId={}, requestId={}",
                record == null ? null : record.rejectionType,
                record == null ? null : record.requestPath,
                record == null ? null : record.idDevice,
                record == null ? null : record.requestId,
                e);
        }
    }

    public PageResponse<SecurityRejectionLog> list(long current, long size,
                                                     String rejectionType,
                                                     String requestPath,
                                                     String clientIp,
                                                     String idDevice,
                                                     String rejectReason,
                                                     String dateFrom,
                                                     String dateTo) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<SecurityRejectionLog> page = new Page<>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<SecurityRejectionLog> wrapper = new LambdaQueryWrapper<SecurityRejectionLog>()
            .eq(SecurityRejectionLog::getFgActive, "1")
            .orderByDesc(SecurityRejectionLog::getInsertTime);

        if (StringUtils.hasText(rejectionType)) {
            wrapper.eq(SecurityRejectionLog::getRejectionType, rejectionType);
        }
        if (StringUtils.hasText(requestPath)) {
            wrapper.like(SecurityRejectionLog::getRequestPath, requestPath);
        }
        if (StringUtils.hasText(clientIp)) {
            wrapper.eq(SecurityRejectionLog::getClientIp, clientIp);
        }
        if (StringUtils.hasText(idDevice)) {
            wrapper.eq(SecurityRejectionLog::getIdDevice, idDevice);
        }
        if (StringUtils.hasText(rejectReason)) {
            wrapper.like(SecurityRejectionLog::getRejectReason, rejectReason);
        }
        if (StringUtils.hasText(dateFrom)) {
            try {
                LocalDateTime from = LocalDate.parse(dateFrom, DATE_FORMATTER).atStartOfDay();
                wrapper.ge(SecurityRejectionLog::getInsertTime, from);
            } catch (DateTimeParseException ignored) {
            }
        }
        if (StringUtils.hasText(dateTo)) {
            try {
                LocalDateTime to = LocalDate.parse(dateTo, DATE_FORMATTER).atTime(LocalTime.MAX);
                wrapper.le(SecurityRejectionLog::getInsertTime, to);
            } catch (DateTimeParseException ignored) {
            }
        }

        Page<SecurityRejectionLog> result = rejectionLogMapper.selectPage(page, wrapper);
        return new PageResponse<>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    public long countRecent(int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return rejectionLogMapper.selectCount(new LambdaQueryWrapper<SecurityRejectionLog>()
            .eq(SecurityRejectionLog::getFgActive, "1")
            .ge(SecurityRejectionLog::getInsertTime, since));
    }

    public Map<String, Object> getStatistics(String dateFrom, String dateTo) {
        SecurityQueryDTO query = new SecurityQueryDTO();
        query.setDateFrom(dateFrom);
        query.setDateTo(dateTo);
        return Collections.singletonMap("placeholder", "replaced");
    }

    public SecuritySummaryVO getSummary(SecurityQueryDTO query) {
        LocalDateTime from = resolveFrom(query);
        LocalDateTime to = resolveTo(query);
        LocalDateTime prevFrom = resolvePrevFrom(query);
        LocalDateTime prevTo = from;

        long total = countByTimeRange(from, to);
        long prevTotal = countByTimeRange(prevFrom, prevTo);
        long recent24h = countRecent(24);
        long recent1h = countRecent(1);

        long authCount = countByTypePrefix(from, to, "AUTH_");
        long sigCount = countByTypePrefix(from, to, "SIG_");
        long wsCount = countByTypePrefix(from, to, "WS_");
        long versionCount = countByTypePrefix(from, to, "VERSION_");

        long prevAuthCount = countByTypePrefix(prevFrom, prevTo, "AUTH_");
        long prevSigCount = countByTypePrefix(prevFrom, prevTo, "SIG_");

        SecuritySummaryVO vo = new SecuritySummaryVO();
        vo.setTotalRejections(total);
        vo.setRecent24h(recent24h);
        vo.setRecent1h(recent1h);
        vo.setAuthRejections(authCount);
        vo.setSigRejections(sigCount);
        vo.setWsRejections(wsCount);
        vo.setVersionRejections(versionCount);
        vo.setTotalGrowth(calcGrowth(total, prevTotal));
        vo.setAuthGrowth(calcGrowth(authCount, prevAuthCount));
        vo.setSigGrowth(calcGrowth(sigCount, prevSigCount));
        return vo;
    }

    public SecurityTrendVO getTrend(SecurityQueryDTO query) {
        LocalDateTime from = resolveFrom(query);
        LocalDateTime to = resolveTo(query);

        List<Map<String, Object>> dailyAll = groupByDaily(from, to);
        List<Map<String, Object>> dailyAuth = groupByDailyWithType(from, to, "AUTH_");
        List<Map<String, Object>> dailySig = groupByDailyWithType(from, to, "SIG_");

        List<String> days = new ArrayList<>();
        List<Long> totalValues = new ArrayList<>();
        List<Long> authValues = new ArrayList<>();
        List<Long> sigValues = new ArrayList<>();

        for (Map<String, Object> d : dailyAll) {
            days.add(String.valueOf(d.get("date")));
            totalValues.add(((Number) d.get("count")).longValue());
        }
        for (Map<String, Object> d : dailyAuth) {
            authValues.add(((Number) d.get("count")).longValue());
        }
        for (Map<String, Object> d : dailySig) {
            sigValues.add(((Number) d.get("count")).longValue());
        }

        SecurityTrendVO vo = new SecurityTrendVO();
        vo.setDays(days);
        vo.setTotalValues(totalValues);
        vo.setAuthValues(authValues);
        vo.setSigValues(sigValues);
        return vo;
    }

    public SecurityDistributionVO getDistribution(SecurityQueryDTO query) {
        LocalDateTime from = resolveFrom(query);
        LocalDateTime to = resolveTo(query);

        long total = countByTimeRange(from, to);

        List<DistributionItem> byType = toDistributionItems(groupByField("rejection_type", from, to), total);
        List<DistributionItem> byIp = toDistributionItems(groupByField("client_ip", from, to, 10), total);
        List<DistributionItem> byPath = toDistributionItems(groupByField("request_path", from, to, 10), total);
        List<DistributionItem> byDevice = toDistributionItems(groupByField("cd_device", from, to, 10), total);

        SecurityDistributionVO vo = new SecurityDistributionVO();
        vo.setByType(byType);
        vo.setByIp(byIp);
        vo.setByPath(byPath);
        vo.setByDevice(byDevice);
        vo.setTotalRejections(total);
        return vo;
    }

    private List<DistributionItem> toDistributionItems(List<Map<String, Object>> grouped, long total) {
        List<DistributionItem> items = new ArrayList<>();
        for (Map<String, Object> m : grouped) {
            String name = String.valueOf(m.get("name") != null ? m.get("name") : "unknown");
            long count = m.get("count") != null ? ((Number) m.get("count")).longValue() : 0L;
            String pct = total > 0 ? String.format("%.1f", count * 100.0 / total) : "0.0";
            items.add(new DistributionItem(name, count, pct));
        }
        return items;
    }

    private long countByTypePrefix(LocalDateTime from, LocalDateTime to, String prefix) {
        LambdaQueryWrapper<SecurityRejectionLog> wrapper = new LambdaQueryWrapper<SecurityRejectionLog>()
            .eq(SecurityRejectionLog::getFgActive, "1")
            .likeRight(SecurityRejectionLog::getRejectionType, prefix);
        if (from != null) wrapper.ge(SecurityRejectionLog::getInsertTime, from);
        if (to != null) wrapper.le(SecurityRejectionLog::getInsertTime, to);
        return rejectionLogMapper.selectCount(wrapper);
    }

    private List<Map<String, Object>> groupByDailyWithType(LocalDateTime from, LocalDateTime to, String typePrefix) {
        String dayExpr = databaseDialect.dayText("insert_time");
        QueryWrapper<SecurityRejectionLog> qw = new QueryWrapper<SecurityRejectionLog>()
            .select(dayExpr + " as day_label", "count(*) as cnt")
            .eq("fg_active", "1")
            .likeRight("rejection_type", typePrefix)
            .groupBy(dayExpr)
            .orderByAsc(dayExpr);
        if (from != null) qw.ge("insert_time", from);
        if (to != null) qw.le("insert_time", to);

        List<Map<String, Object>> maps = rejectionLogMapper.selectMaps(qw);
        if (maps == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("date", String.valueOf(resolveMapValue(m, "DAY_LABEL", "day_label") != null ? resolveMapValue(m, "DAY_LABEL", "day_label") : ""));
            item.put("count", resolveMapCount(m));
            result.add(item);
        }
        return result;
    }

    private String calcGrowth(long current, long previous) {
        if (previous == 0) {
            return current > 0 ? "+100.0%" : "0.0%";
        }
        double growth = (current - previous) * 100.0 / previous;
        return String.format("%+.1f%%", growth);
    }

    private LocalDateTime resolveFrom(SecurityQueryDTO query) {
        if (query != null && StringUtils.hasText(query.getDateFrom())) {
            try {
                return LocalDate.parse(query.getDateFrom(), DATE_FORMATTER).atStartOfDay();
            } catch (DateTimeParseException ignored) {
            }
        }
        return LocalDateTime.now().minusDays(30);
    }

    private LocalDateTime resolveTo(SecurityQueryDTO query) {
        if (query != null && StringUtils.hasText(query.getDateTo())) {
            try {
                return LocalDate.parse(query.getDateTo(), DATE_FORMATTER).atTime(LocalTime.MAX);
            } catch (DateTimeParseException ignored) {
            }
        }
        return LocalDateTime.now();
    }

    private LocalDateTime resolvePrevFrom(SecurityQueryDTO query) {
        LocalDateTime from = resolveFrom(query);
        LocalDateTime to = resolveTo(query);
        long days = java.time.Duration.between(from, to).toDays();
        if (days <= 0) days = 30;
        return from.minusDays(days);
    }

    private long countByTimeRange(LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<SecurityRejectionLog> wrapper = new LambdaQueryWrapper<SecurityRejectionLog>()
            .eq(SecurityRejectionLog::getFgActive, "1");
        if (from != null) wrapper.ge(SecurityRejectionLog::getInsertTime, from);
        if (to != null) wrapper.le(SecurityRejectionLog::getInsertTime, to);
        return rejectionLogMapper.selectCount(wrapper);
    }

    private List<Map<String, Object>> groupByField(String field, LocalDateTime from, LocalDateTime to, int limit) {
        QueryWrapper<SecurityRejectionLog> qw = new QueryWrapper<SecurityRejectionLog>()
            .select(field + " as item_name", "count(*) as cnt")
            .eq("fg_active", "1")
            .groupBy(field)
            .orderByDesc("count(*)");
        if (from != null) qw.ge("insert_time", from);
        if (to != null) qw.le("insert_time", to);
        if (limit > 0) qw.last(databaseDialect.firstRows(limit));

        List<Map<String, Object>> maps = rejectionLogMapper.selectMaps(qw);
        if (maps == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("name", String.valueOf(resolveMapValue(m, "ITEM_NAME", "item_name") != null ? resolveMapValue(m, "ITEM_NAME", "item_name") : "unknown"));
            item.put("count", resolveMapCount(m));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> groupByField(String field, LocalDateTime from, LocalDateTime to) {
        return groupByField(field, from, to, 0);
    }

    private List<Map<String, Object>> groupByDaily(LocalDateTime from, LocalDateTime to) {
        String dayExpr = databaseDialect.dayText("insert_time");
        QueryWrapper<SecurityRejectionLog> qw = new QueryWrapper<SecurityRejectionLog>()
            .select(dayExpr + " as day_label", "count(*) as cnt")
            .eq("fg_active", "1")
            .groupBy(dayExpr)
            .orderByAsc(dayExpr);
        if (from != null) qw.ge("insert_time", from);
        if (to != null) qw.le("insert_time", to);

        List<Map<String, Object>> maps = rejectionLogMapper.selectMaps(qw);
        if (maps == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("date", String.valueOf(resolveMapValue(m, "DAY_LABEL", "day_label") != null ? resolveMapValue(m, "DAY_LABEL", "day_label") : ""));
            item.put("count", resolveMapCount(m));
            result.add(item);
        }
        return result;
    }

    private Object resolveMapValue(Map<String, Object> m, String upperKey, String lowerKey) {
        Object v = m.get(upperKey);
        if (v == null) v = m.get(lowerKey);
        return v;
    }

    private long resolveMapCount(Map<String, Object> m) {
        Object v = resolveMapValue(m, "CNT", "cnt");
        return v != null ? ((Number) v).longValue() : 0L;
    }

    private LocalDateTime parseDateFrom(String dateFrom) {
        if (!StringUtils.hasText(dateFrom)) return LocalDateTime.now().minusDays(30);
        try {
            return LocalDate.parse(dateFrom, DATE_FORMATTER).atStartOfDay();
        } catch (DateTimeParseException e) {
            return LocalDateTime.now().minusDays(30);
        }
    }

    private LocalDateTime parseDateTo(String dateTo) {
        if (!StringUtils.hasText(dateTo)) return LocalDateTime.now();
        try {
            return LocalDate.parse(dateTo, DATE_FORMATTER).atTime(LocalTime.MAX);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() > maxLen ? value.substring(0, maxLen) : value;
    }

    public static class RejectionRecord {
        public String rejectionType;
        public String requestMethod;
        public String requestPath;
        public String clientIp;
        public String idDevice;
        public String cdDevice;
        public String idOrg;
        public String requestId;
        public String rejectReason;
        public String rejectDetail;
        public boolean hasSignature;
        public String timestampHeader;
        public String nonceHeader;
        public String clientVersion;
        public String updateChannel;

        public static RejectionRecord of(String rejectionType, String requestMethod, String requestPath, String clientIp) {
            RejectionRecord r = new RejectionRecord();
            r.rejectionType = rejectionType;
            r.requestMethod = requestMethod;
            r.requestPath = requestPath;
            r.clientIp = clientIp;
            return r;
        }

        public RejectionRecord device(AiDevice device) {
            if (device != null) {
                this.idDevice = device.getIdDevice();
                this.cdDevice = device.getCdDevice();
                this.idOrg = device.getIdOrg();
            }
            return this;
        }

        public RejectionRecord requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public RejectionRecord reason(String reason) {
            this.rejectReason = reason;
            return this;
        }

        public RejectionRecord detail(String detail) {
            this.rejectDetail = detail;
            return this;
        }

        public RejectionRecord signature(boolean hasSignature, String timestamp, String nonce) {
            this.hasSignature = hasSignature;
            this.timestampHeader = timestamp;
            this.nonceHeader = nonce;
            return this;
        }

        public RejectionRecord clientInfo(String version, String channel) {
            this.clientVersion = version;
            this.updateChannel = channel;
            return this;
        }
    }
}
