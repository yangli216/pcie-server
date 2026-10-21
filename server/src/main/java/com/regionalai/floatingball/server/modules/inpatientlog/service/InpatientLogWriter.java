package com.regionalai.floatingball.server.modules.inpatientlog.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.InpatientLogSnapshot;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.*;
import com.regionalai.floatingball.server.modules.inpatientlog.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

@Service
public class InpatientLogWriter {
    private final InpatientEmrLogMapper logs;
    private final InpatientEmrLogEventMapper events;
    public InpatientLogWriter(InpatientEmrLogMapper logs, InpatientEmrLogEventMapper events) { this.logs = logs; this.events = events; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> append(AiDevice device, InpatientLogSnapshot snapshot, String json, String hash) {
        InpatientEmrLog log = logs.lockRound(device.getIdDevice(), snapshot.getGenerationId());
        InpatientEmrLogEvent existing = events.selectOne(new LambdaQueryWrapper<InpatientEmrLogEvent>()
            .eq(InpatientEmrLogEvent::getIdDevice, device.getIdDevice()).eq(InpatientEmrLogEvent::getEventId, snapshot.getEventId()));
        if (existing != null) {
            if (!hash.equals(existing.getBodyHash())) throw new BusinessException("日志事件标识已被其他内容使用");
            return response(existing.getIdLog(), true);
        }
        if (log == null) {
            log = new InpatientEmrLog();
            log.setIdLog(UUID.randomUUID().toString().replace("-", "")); log.setIdDevice(device.getIdDevice()); log.setIdOrg(device.getIdOrg());
            log.setGenerationId(snapshot.getGenerationId()); log.setAdmissionId(snapshot.getAdmissionId());
            log.setTemplateId(snapshot.getTemplateId()); log.setTemplateHash(snapshot.getTemplateHash());
            log.setGenerationTime(time(snapshot.getGenerationStartedAt())); log.setLatestSequence(0); log.setFgActive("1");
            fill(log, snapshot, json); logs.insert(log);
        } else {
            if (!Objects.equals(log.getIdOrg(), device.getIdOrg()) || !Objects.equals(log.getAdmissionId(), snapshot.getAdmissionId()) || !Objects.equals(log.getTemplateId(), snapshot.getTemplateId())
                || !Objects.equals(log.getTemplateHash(), snapshot.getTemplateHash()) || !Objects.equals(log.getGenerationTime(), time(snapshot.getGenerationStartedAt()))
                || (has(log.getPatientId()) && has(snapshot.getPatientId()) && !Objects.equals(log.getPatientId(), snapshot.getPatientId())))
                throw new BusinessException("同一生成轮次的患者、住院或模板锚点不一致");
            if (snapshot.getSequence() > log.getLatestSequence()) {
                InpatientLogRules.checkTransition(log.getStatus(), snapshot.getStatus());
                fill(log, snapshot, json); logs.updateById(log);
            }
        }
        InpatientEmrLogEvent event = new InpatientEmrLogEvent();
        event.setIdEvent(UUID.randomUUID().toString().replace("-", "")); event.setIdLog(log.getIdLog()); event.setIdDevice(device.getIdDevice());
        event.setEventId(snapshot.getEventId()); event.setEventSequence(snapshot.getSequence()); event.setEventType(snapshot.getEventType());
        event.setStatus(snapshot.getStatus()); event.setOccurredAt(time(snapshot.getOccurredAt())); event.setMessage(snapshot.getMessage());
        event.setBodyHash(hash); event.setSnapshotJson(json); events.insert(event);
        return response(log.getIdLog(), false);
    }
    private void fill(InpatientEmrLog log, InpatientLogSnapshot snapshot, String json) {
        log.setTemplateName(snapshot.getTemplateName()); log.setHisOrgId(snapshot.getHisOrgId()); log.setOrgName(snapshot.getOrgName());
        log.setDoctorId(snapshot.getDoctorId()); log.setDoctorName(snapshot.getDoctorName()); log.setPatientId(snapshot.getPatientId());
        log.setPatientName(snapshot.getPatientName()); log.setPatientGender(snapshot.getPatientGender()); log.setPatientAge(snapshot.getPatientAge());
        log.setRecordTime(snapshot.getRecordTime()); log.setLatestSequence(snapshot.getSequence()); log.setStatus(snapshot.getStatus()); log.setSnapshotJson(json);
    }
    private boolean has(String text) { return text != null && !text.trim().isEmpty(); }
    private LocalDateTime time(Long millis) { return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()); }
    private Map<String, Object> response(String idLog, boolean replayed) {
        Map<String, Object> response = new LinkedHashMap<>(); response.put("idLog", idLog); response.put("replayed", replayed); return response;
    }
}
