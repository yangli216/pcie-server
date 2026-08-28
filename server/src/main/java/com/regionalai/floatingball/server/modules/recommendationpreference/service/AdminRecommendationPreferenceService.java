package com.regionalai.floatingball.server.modules.recommendationpreference.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.recommendationpreference.dto.AdminRecommendationPreferenceAggregateVO;
import com.regionalai.floatingball.server.modules.recommendationpreference.dto.AdminRecommendationPreferenceEventVO;
import com.regionalai.floatingball.server.modules.recommendationpreference.dto.AdminRecommendationPreferenceQuery;
import com.regionalai.floatingball.server.modules.recommendationpreference.dto.AdminRecommendationPreferenceSummaryVO;
import com.regionalai.floatingball.server.modules.recommendationpreference.entity.AiRecommendationPreferenceAggregate;
import com.regionalai.floatingball.server.modules.recommendationpreference.entity.AiRecommendationPreferenceEvent;
import com.regionalai.floatingball.server.modules.recommendationpreference.mapper.AiRecommendationPreferenceAggregateMapper;
import com.regionalai.floatingball.server.modules.recommendationpreference.mapper.AiRecommendationPreferenceEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminRecommendationPreferenceService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AiRecommendationPreferenceAggregateMapper aggregateMapper;
    private final AiRecommendationPreferenceEventMapper eventMapper;

    public AdminRecommendationPreferenceService(AiRecommendationPreferenceAggregateMapper aggregateMapper,
                                                AiRecommendationPreferenceEventMapper eventMapper) {
        this.aggregateMapper = aggregateMapper;
        this.eventMapper = eventMapper;
    }

    public AdminRecommendationPreferenceSummaryVO summary(AdminRecommendationPreferenceQuery query) {
        AdminRecommendationPreferenceQuery normalized = normalizeQuery(query);
        QueryWrapper<AiRecommendationPreferenceAggregate> aggregateWrapper = buildAggregateWrapper(normalized);
        QueryWrapper<AiRecommendationPreferenceEvent> eventWrapper = buildEventWrapper(normalized);

        List<AiRecommendationPreferenceAggregate> aggregates = aggregateMapper.selectList(aggregateWrapper);
        Long eventCount = eventMapper.selectCount(eventWrapper);

        AdminRecommendationPreferenceSummaryVO summary = new AdminRecommendationPreferenceSummaryVO();
        summary.setAggregateCount(aggregates.size());
        summary.setEventCount(eventCount == null ? 0L : eventCount);

        BigDecimal totalScore = BigDecimal.ZERO;
        for (AiRecommendationPreferenceAggregate aggregate : aggregates) {
            String scope = resolveScope(aggregate.getIdDept(), aggregate.getIdDoctor());
            if ("doctor".equals(scope)) {
                summary.setDoctorScopeCount(summary.getDoctorScopeCount() + 1);
            } else if ("dept".equals(scope)) {
                summary.setDeptScopeCount(summary.getDeptScopeCount() + 1);
            } else {
                summary.setOrgScopeCount(summary.getOrgScopeCount() + 1);
            }
            if (aggregate.getPreferenceScore() != null) {
                totalScore = totalScore.add(aggregate.getPreferenceScore());
            }
        }
        if (!aggregates.isEmpty()) {
            summary.setAveragePreferenceScore(totalScore.divide(BigDecimal.valueOf(aggregates.size()), 4, RoundingMode.HALF_UP));
        } else {
            summary.setAveragePreferenceScore(BigDecimal.ZERO);
        }
        return summary;
    }

    public PageResponse<AdminRecommendationPreferenceAggregateVO> aggregates(AdminRecommendationPreferenceQuery query) {
        AdminRecommendationPreferenceQuery normalized = normalizeQuery(query);
        Page<AiRecommendationPreferenceAggregate> page = new Page<AiRecommendationPreferenceAggregate>(
            normalized.getCurrent(),
            normalized.getSize()
        );
        QueryWrapper<AiRecommendationPreferenceAggregate> wrapper = buildAggregateWrapper(normalized)
            .orderByDesc("last_event_time")
            .orderByDesc("preference_score");
        Page<AiRecommendationPreferenceAggregate> result = aggregateMapper.selectPage(page, wrapper);
        List<AdminRecommendationPreferenceAggregateVO> records = new ArrayList<AdminRecommendationPreferenceAggregateVO>();
        for (AiRecommendationPreferenceAggregate aggregate : result.getRecords()) {
            records.add(toAggregateVO(aggregate));
        }
        return new PageResponse<AdminRecommendationPreferenceAggregateVO>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            records
        );
    }

    public PageResponse<AdminRecommendationPreferenceEventVO> events(AdminRecommendationPreferenceQuery query) {
        AdminRecommendationPreferenceQuery normalized = normalizeQuery(query);
        Page<AiRecommendationPreferenceEvent> page = new Page<AiRecommendationPreferenceEvent>(
            normalized.getCurrent(),
            normalized.getSize()
        );
        QueryWrapper<AiRecommendationPreferenceEvent> wrapper = buildEventWrapper(normalized)
            .orderByDesc("event_time")
            .orderByDesc("insert_time");
        Page<AiRecommendationPreferenceEvent> result = eventMapper.selectPage(page, wrapper);
        List<AdminRecommendationPreferenceEventVO> records = new ArrayList<AdminRecommendationPreferenceEventVO>();
        for (AiRecommendationPreferenceEvent event : result.getRecords()) {
            records.add(toEventVO(event));
        }
        return new PageResponse<AdminRecommendationPreferenceEventVO>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            records
        );
    }

    private QueryWrapper<AiRecommendationPreferenceAggregate> buildAggregateWrapper(AdminRecommendationPreferenceQuery query) {
        QueryWrapper<AiRecommendationPreferenceAggregate> wrapper = new QueryWrapper<AiRecommendationPreferenceAggregate>()
            .eq("fg_active", "1");
        applyCommonFilters(wrapper, query);
        applyAggregateScopeFilter(wrapper, query.getScope());
        LocalDateTime startTime = parseDateTime(query.getDateFrom(), false);
        LocalDateTime endTime = parseDateTime(query.getDateTo(), true);
        if (startTime != null) {
            wrapper.ge("last_event_time", startTime);
        }
        if (endTime != null) {
            wrapper.le("last_event_time", endTime);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(q -> q
                .like("item_name", keyword)
                .or().like("item_code", keyword)
                .or().like("item_id", keyword)
                .or().like("item_key", keyword)
                .or().like("id_doctor", keyword)
                .or().like("id_dept", keyword));
        }
        return wrapper;
    }

    private QueryWrapper<AiRecommendationPreferenceEvent> buildEventWrapper(AdminRecommendationPreferenceQuery query) {
        QueryWrapper<AiRecommendationPreferenceEvent> wrapper = new QueryWrapper<AiRecommendationPreferenceEvent>()
            .eq("fg_active", "1");
        applyCommonFilters(wrapper, query);
        applyEventScopeFilter(wrapper, query.getScope());
        LocalDateTime startTime = parseDateTime(query.getDateFrom(), false);
        LocalDateTime endTime = parseDateTime(query.getDateTo(), true);
        if (startTime != null) {
            wrapper.ge("event_time", startTime);
        }
        if (endTime != null) {
            wrapper.le("event_time", endTime);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(q -> q
                .like("item_name", keyword)
                .or().like("item_code", keyword)
                .or().like("item_id", keyword)
                .or().like("item_key", keyword)
                .or().like("na_doctor", keyword)
                .or().like("id_doctor", keyword)
                .or().like("na_dept", keyword)
                .or().like("id_dept", keyword)
                .or().like("trace_id", keyword)
                .or().like("consultation_id", keyword)
                .or().like("session_id", keyword));
        }
        return wrapper;
    }

    private <T> void applyCommonFilters(QueryWrapper<T> wrapper, AdminRecommendationPreferenceQuery query) {
        if (StringUtils.hasText(query.getRecommendationType())) {
            wrapper.eq("recommendation_type", query.getRecommendationType().trim());
        }
        if (StringUtils.hasText(query.getIdRegion())) {
            wrapper.eq("id_region", query.getIdRegion().trim());
        }
        if (StringUtils.hasText(query.getIdOrg())) {
            wrapper.eq("id_org", query.getIdOrg().trim());
        }
        if (StringUtils.hasText(query.getIdDept())) {
            wrapper.eq("id_dept", query.getIdDept().trim());
        }
        if (StringUtils.hasText(query.getIdDoctor())) {
            wrapper.eq("id_doctor", query.getIdDoctor().trim());
        }
    }

    private void applyAggregateScopeFilter(QueryWrapper<AiRecommendationPreferenceAggregate> wrapper, String scope) {
        String normalized = trimToNull(scope);
        if ("doctor".equals(normalized)) {
            wrapper.isNotNull("id_doctor");
        } else if ("dept".equals(normalized)) {
            wrapper.isNotNull("id_dept").isNull("id_doctor");
        } else if ("org".equals(normalized)) {
            wrapper.isNull("id_dept").isNull("id_doctor");
        }
    }

    private void applyEventScopeFilter(QueryWrapper<AiRecommendationPreferenceEvent> wrapper, String scope) {
        String normalized = trimToNull(scope);
        if ("doctor".equals(normalized)) {
            wrapper.isNotNull("id_doctor");
        } else if ("dept".equals(normalized)) {
            wrapper.isNotNull("id_dept").isNull("id_doctor");
        } else if ("org".equals(normalized)) {
            wrapper.isNull("id_dept").isNull("id_doctor");
        }
    }

    private AdminRecommendationPreferenceAggregateVO toAggregateVO(AiRecommendationPreferenceAggregate aggregate) {
        AdminRecommendationPreferenceAggregateVO vo = new AdminRecommendationPreferenceAggregateVO();
        vo.setIdAgg(aggregate.getIdAgg());
        vo.setScope(resolveScope(aggregate.getIdDept(), aggregate.getIdDoctor()));
        vo.setIdOrg(aggregate.getIdOrg());
        vo.setIdRegion(aggregate.getIdRegion());
        vo.setIdDept(aggregate.getIdDept());
        vo.setIdDoctor(aggregate.getIdDoctor());
        vo.setRecommendationType(aggregate.getRecommendationType());
        vo.setItemKey(aggregate.getItemKey());
        vo.setItemId(aggregate.getItemId());
        vo.setItemCode(aggregate.getItemCode());
        vo.setItemName(aggregate.getItemName());
        vo.setSelectedCount(safeInt(aggregate.getSelectedCount()));
        vo.setConfirmCount(safeInt(aggregate.getConfirmCount()));
        vo.setManualMatchCount(safeInt(aggregate.getManualMatchCount()));
        vo.setSampleCount(vo.getSelectedCount() + vo.getConfirmCount() + vo.getManualMatchCount());
        vo.setPreferenceScore(aggregate.getPreferenceScore() == null ? BigDecimal.ZERO : aggregate.getPreferenceScore());
        vo.setLastEventTime(aggregate.getLastEventTime());
        vo.setInsertTime(aggregate.getInsertTime());
        vo.setUpdateTime(aggregate.getUpdateTime());
        return vo;
    }

    private AdminRecommendationPreferenceEventVO toEventVO(AiRecommendationPreferenceEvent event) {
        AdminRecommendationPreferenceEventVO vo = new AdminRecommendationPreferenceEventVO();
        vo.setIdEvent(event.getIdEvent());
        vo.setIdDevice(event.getIdDevice());
        vo.setIdOrg(event.getIdOrg());
        vo.setIdRegion(event.getIdRegion());
        vo.setScope(resolveScope(event.getIdDept(), event.getIdDoctor()));
        vo.setRecommendationType(event.getRecommendationType());
        vo.setActionCode(event.getActionCode());
        vo.setIdempotencyKey(event.getIdempotencyKey());
        vo.setItemKey(event.getItemKey());
        vo.setItemId(event.getItemId());
        vo.setItemCode(event.getItemCode());
        vo.setItemName(event.getItemName());
        vo.setSelected("1".equals(event.getFgSelected()));
        vo.setPrimary("1".equals(event.getFgPrimary()));
        vo.setTraceId(event.getTraceId());
        vo.setConsultationId(event.getConsultationId());
        vo.setSessionId(event.getSessionId());
        vo.setSourceModule(event.getSourceModule());
        vo.setSceneCode(event.getSceneCode());
        vo.setIdDoctor(event.getIdDoctor());
        vo.setNaDoctor(event.getNaDoctor());
        vo.setIdDept(event.getIdDept());
        vo.setNaDept(event.getNaDept());
        vo.setPromptVersion(event.getPromptVersion());
        vo.setTemplateVersion(event.getTemplateVersion());
        vo.setModelVersion(event.getModelVersion());
        vo.setEventTime(event.getEventTime());
        vo.setInsertTime(event.getInsertTime());
        return vo;
    }

    private AdminRecommendationPreferenceQuery normalizeQuery(AdminRecommendationPreferenceQuery query) {
        AdminRecommendationPreferenceQuery normalized = query == null ? new AdminRecommendationPreferenceQuery() : query;
        PageRequest pageRequest = PageRequest.of(normalized.getCurrent(), normalized.getSize());
        normalized.setCurrent(pageRequest.getCurrent());
        normalized.setSize(pageRequest.getSize());
        normalized.setRecommendationType(trimToNull(normalized.getRecommendationType()));
        normalized.setScope(trimToNull(normalized.getScope()));
        normalized.setIdRegion(trimToNull(normalized.getIdRegion()));
        normalized.setIdOrg(trimToNull(normalized.getIdOrg()));
        normalized.setIdDept(trimToNull(normalized.getIdDept()));
        normalized.setIdDoctor(trimToNull(normalized.getIdDoctor()));
        normalized.setKeyword(trimToNull(normalized.getKeyword()));
        normalized.setDateFrom(trimToNull(normalized.getDateFrom()));
        normalized.setDateTo(trimToNull(normalized.getDateTo()));
        return normalized;
    }

    private String resolveScope(String deptId, String doctorId) {
        if (StringUtils.hasText(doctorId)) {
            return "doctor";
        }
        if (StringUtils.hasText(deptId)) {
            return "dept";
        }
        return "org";
    }

    private LocalDateTime parseDateTime(String value, boolean endOfDay) {
        String candidate = trimToNull(value);
        if (candidate == null) {
            return null;
        }
        try {
            if (candidate.length() == 10) {
                LocalDate date = LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
                return endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
            }
            return LocalDateTime.parse(candidate, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(candidate, ISO_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(candidate).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            throw new BusinessException("推荐偏好查询时间格式非法");
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
