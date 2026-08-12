package com.regionalai.floatingball.server.modules.featureevent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.featureevent.dto.FeatureEventBatchRequest;
import com.regionalai.floatingball.server.modules.featureevent.dto.FeatureEventBatchResponse;
import com.regionalai.floatingball.server.modules.featureevent.entity.AiFeatureEvent;
import com.regionalai.floatingball.server.modules.featureevent.mapper.AiFeatureEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class FeatureEventService {

    private static final Logger log = LoggerFactory.getLogger(FeatureEventService.class);
    private static final String EVENT_ID_REJECTION = "eventId 必须为 UUID";
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile(
        "(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    );
    private static final Pattern TELEMETRY_CODE_PATTERN = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:-]*"
    );

    private final AiFeatureEventMapper featureEventMapper;

    public FeatureEventService(AiFeatureEventMapper featureEventMapper) {
        this.featureEventMapper = featureEventMapper;
    }

    public FeatureEventBatchResponse saveBatch(AiDevice device, FeatureEventBatchRequest request) {
        if (request == null || request.getEvents() == null || request.getEvents().isEmpty()) {
            return new FeatureEventBatchResponse(0, 0);
        }

        FeatureEventBatchResponse response = new FeatureEventBatchResponse(0, 0);
        int index = 0;
        for (FeatureEventBatchRequest.FeatureEventRequest event : request.getEvents()) {
            SaveResult result = saveOne(device, event);
            if (result.state == State.ACCEPTED) {
                response.setAccepted(response.getAccepted() + 1);
            } else if (result.state == State.SKIPPED) {
                response.setSkipped(response.getSkipped() + 1);
            } else if (result.state == State.REJECTED) {
                response.addRejection(index, resolveRequestEventId(event), resolveRequestFeatureCode(event), result.reason);
            }
            index++;
        }
        log.info("feature event batch saved. deviceId={}, accepted={}, skipped={}, rejected={}",
            device == null ? null : device.getIdDevice(), response.getAccepted(), response.getSkipped(), response.getRejected());
        return response;
    }

    private SaveResult saveOne(AiDevice device, FeatureEventBatchRequest.FeatureEventRequest request) {
        if (request == null) {
            return SaveResult.rejected("事件不能为空");
        }
        String featureCode = trimToNull(request.getFeatureCode());
        if (featureCode == null) {
            return SaveResult.rejected("featureCode 不能为空");
        }
        String featureName = FeatureEventCatalog.resolveName(featureCode);
        if (featureName == null) {
            return SaveResult.rejected("featureCode 不支持");
        }

        String idDevice = device == null ? null : trimToNull(device.getIdDevice());
        String eventId = normalizeEventId(request.getEventId());
        if (eventId == null) {
            return SaveResult.rejected(EVENT_ID_REJECTION);
        }
        String idempotencyKey = safeIdempotencyKey(featureCode, eventId);
        if (idDevice != null && exists(idDevice, idempotencyKey)) {
            return SaveResult.skipped();
        }

        AiFeatureEvent entity = new AiFeatureEvent();
        entity.setIdEvent(eventId);
        entity.setIdDevice(idDevice);
        entity.setIdOrg(device == null ? null : trimToNull(device.getIdOrg()));
        entity.setIdRegion(device == null ? null : trimToNull(device.getIdRegion()));
        entity.setHisOrgId(trimToNull(request.getHisOrgId()));
        entity.setHisOrgName(trimToNull(request.getHisOrgName()));
        entity.setFeatureCode(featureCode);
        entity.setFeatureName(featureName);
        entity.setEventAction(normalizeTelemetryCode(request.getEventAction(), 128));
        entity.setIdempotencyKey(idempotencyKey);
        entity.setTraceId(null);
        entity.setConsultationId(null);
        entity.setSessionId(null);
        entity.setSourceModule(normalizeTelemetryCode(request.getSourceModule(), 128));
        entity.setSceneCode(normalizeTelemetryCode(request.getScene(), 256));
        entity.setIdDoctor(trimToNull(request.getDoctorId()));
        entity.setDoctorWorkNo(truncate(trimToNull(request.getDoctorWorkNo()), 64));
        entity.setNaDoctor(trimToNull(request.getDoctorName()));
        entity.setIdDept(trimToNull(request.getDeptId()));
        entity.setNaDept(trimToNull(request.getDeptName()));
        entity.setEventStatus(resolveStatus(request.getStatus()));
        entity.setClientVersion(resolveClientVersion(device, request.getClientVersion()));
        entity.setPayloadJson("{}");
        entity.setEventTime(resolveEventTime(request.getTimestamp()));
        entity.setFgActive("1");

        try {
            featureEventMapper.insert(entity);
            return SaveResult.accepted();
        } catch (DuplicateKeyException ex) {
            return SaveResult.skipped();
        }
    }

    private boolean exists(String idDevice, String idempotencyKey) {
        if (!StringUtils.hasText(idDevice) || !StringUtils.hasText(idempotencyKey)) {
            return false;
        }
        Long count = featureEventMapper.selectCount(new LambdaQueryWrapper<AiFeatureEvent>()
            .eq(AiFeatureEvent::getFgActive, "1")
            .eq(AiFeatureEvent::getIdDevice, idDevice)
            .eq(AiFeatureEvent::getIdempotencyKey, idempotencyKey));
        return count != null && count > 0;
    }

    private String resolveStatus(String status) {
        String text = trimToNull(status);
        return "failure".equals(text) ? "failure" : "success";
    }

    private String resolveClientVersion(AiDevice device, String eventClientVersion) {
        String version = trimToNull(eventClientVersion);
        if (version == null && device != null) {
            version = trimToNull(device.getClientVersion());
        }
        return truncate(version, 64);
    }

    private LocalDateTime resolveEventTime(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
    }

    private String normalizeEventId(String value) {
        String eventId = trimToNull(value);
        if (eventId == null || !EVENT_ID_PATTERN.matcher(eventId).matches()) {
            return null;
        }
        return eventId.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String safeIdempotencyKey(String featureCode, String eventId) {
        return featureCode.toLowerCase(Locale.ROOT) + ":minimized:v1:event:" + eventId;
    }

    private String normalizeTelemetryCode(String value, int maxLength) {
        String code = trimToNull(value);
        if (code == null || code.length() > maxLength || !TELEMETRY_CODE_PATTERN.matcher(code).matches()) {
            return null;
        }
        return code;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String resolveRequestEventId(FeatureEventBatchRequest.FeatureEventRequest request) {
        return request == null ? null : trimToNull(request.getEventId());
    }

    private String resolveRequestFeatureCode(FeatureEventBatchRequest.FeatureEventRequest request) {
        return request == null ? null : trimToNull(request.getFeatureCode());
    }

    private static final class SaveResult {
        private static final SaveResult ACCEPTED = new SaveResult(State.ACCEPTED, null);
        private static final SaveResult SKIPPED = new SaveResult(State.SKIPPED, null);

        private final State state;
        private final String reason;

        private SaveResult(State state, String reason) {
            this.state = state;
            this.reason = reason;
        }

        private static SaveResult accepted() {
            return ACCEPTED;
        }

        private static SaveResult skipped() {
            return SKIPPED;
        }

        private static SaveResult rejected(String reason) {
            return new SaveResult(State.REJECTED, reason);
        }
    }

    private enum State {
        ACCEPTED,
        SKIPPED,
        REJECTED
    }
}
