package com.regionalai.floatingball.server.modules.inpatientlog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.*;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.*;
import com.regionalai.floatingball.server.modules.inpatientlog.entity.*;
import com.regionalai.floatingball.server.modules.inpatientlog.mapper.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import javax.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class InpatientLogService {
    private final InpatientLogWriter writer;
    private final InpatientEmrLogMapper logs;
    private final InpatientEmrLogEventMapper events;
    private final ObjectMapper json;
    private final DatabaseDialect dialect;
    private final BbpStatisticsScope scope;
    private final Validator validator;
    public InpatientLogService(InpatientLogWriter writer, InpatientEmrLogMapper logs, InpatientEmrLogEventMapper events,
        ObjectMapper json, DatabaseDialect dialect, BbpStatisticsScope scope, Validator validator) {
        this.writer = writer; this.logs = logs; this.events = events; this.json = json; this.dialect = dialect; this.scope = scope; this.validator = validator;
    }
    public Map<String, Object> ingest(AiDevice device, InpatientLogSnapshot snapshot) {
        if (device == null || !StringUtils.hasText(device.getIdDevice()) || !StringUtils.hasText(device.getIdOrg()))
            throw new BusinessException("设备机构上下文缺失");
        if (snapshot == null || !validator.validate(snapshot).isEmpty()) throw new BusinessException("住院日志字段缺失、过长或格式非法");
        InpatientLogRules.validate(snapshot);
        String body;
        String hash;
        try {
            body = json.writeValueAsString(snapshot);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1048576) throw new BusinessException("住院日志内容超过1MiB，请联系管理员处理");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format("%02x", value & 255));
            hash = hex.toString();
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException("住院日志快照无法解析"); }
        // Each attempt has its own transaction. Never query inside a failed GaussDB transaction.
        try { return writer.append(device, snapshot, body, hash); }
        catch (DuplicateKeyException first) {
            try { return writer.append(device, snapshot, body, hash); }
            catch (DuplicateKeyException second) { throw new BusinessException("住院日志事件序号冲突，请联系管理员处理"); }
        }
    }
    public PageResponse<Map<String, Object>> list(InpatientLogQuery query, long current, long size) {
        PageRequest page = PageRequest.of(current, size);
        Page<InpatientEmrLog> result = logs.selectPage(new Page<>(page.getCurrent(), page.getSize()), wrapper(query));
        List<Map<String, Object>> records = new ArrayList<>();
        for (InpatientEmrLog log : result.getRecords()) records.add(summary(log));
        return new PageResponse<>(page.getCurrent(), page.getSize(), result.getTotal(), records);
    }
    public Map<String, Object> options() {
        String hisOrgId = scope.resolve(AdminContextHolder.get(), null).getHisOrgId();
        QueryWrapper<InpatientEmrLog> orgs = optionScope(hisOrgId)
            .select("id_org", "his_org_id", "MAX(org_name) AS org_name")
            .groupBy("id_org", "his_org_id").orderByAsc("org_name");
        List<Map<String, Object>> organizations = new ArrayList<>();
        for (InpatientEmrLog row : logs.selectList(orgs)) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("idOrg", row.getIdOrg()); item.put("hisOrgId", row.getHisOrgId());
            item.put("orgName", StringUtils.hasText(row.getOrgName()) ? row.getOrgName() : row.getIdOrg()); organizations.add(item);
        }
        QueryWrapper<InpatientEmrLog> templatesQuery = optionScope(hisOrgId)
            .select("template_id", "MAX(template_name) AS template_name")
            .groupBy("template_id").orderByAsc("template_name");
        List<Map<String, Object>> templates = new ArrayList<>();
        for (InpatientEmrLog row : logs.selectList(templatesQuery)) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("templateId", row.getTemplateId()); item.put("templateName", row.getTemplateName()); templates.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("organizations", organizations); result.put("templates", templates); return result;
    }
    public Map<String, Object> detail(String id) {
        String hisOrgId = scope.resolve(AdminContextHolder.get(), null).getHisOrgId();
        InpatientEmrLog log = logs.selectOne(baseScope(hisOrgId).eq(InpatientEmrLog::getIdLog, id));
        if (log == null) throw new BusinessException("住院病历日志不存在或无权查看");
        Map<String, Object> detail = new LinkedHashMap<>(); detail.put("record", summary(log));
        try { detail.put("snapshot", json.readValue(log.getSnapshotJson(), InpatientLogSnapshot.class)); }
        catch (Exception ex) { throw new BusinessException("住院病历日志快照不可用"); }
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (InpatientEmrLogEvent event : events.selectList(new LambdaQueryWrapper<InpatientEmrLogEvent>()
            .select(InpatientEmrLogEvent::getEventId, InpatientEmrLogEvent::getEventSequence, InpatientEmrLogEvent::getEventType,
                InpatientEmrLogEvent::getStatus, InpatientEmrLogEvent::getOccurredAt, InpatientEmrLogEvent::getMessage)
            .eq(InpatientEmrLogEvent::getIdLog, id).orderByAsc(InpatientEmrLogEvent::getEventSequence))) {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("eventId", event.getEventId()); item.put("sequence", event.getEventSequence());
            item.put("eventType", event.getEventType()); item.put("status", event.getStatus()); item.put("occurredAt", event.getOccurredAt()); item.put("message", event.getMessage()); timeline.add(item);
        }
        detail.put("events", timeline); return detail;
    }
    public byte[] exportCsv(InpatientLogQuery query) {
        List<InpatientEmrLog> records = logs.selectList(wrapper(query).last(dialect.firstRows(1001)));
        if (records.size() > 1000) throw new BusinessException("最多导出1000条，请缩小筛选范围");
        StringBuilder csv = new StringBuilder("\ufeff机构,医生,生成时间,病历书写时间,患者,性别,年龄,住院ID,模板,处理结果\r\n");
        for (InpatientEmrLog row : records) {
            String[] values = {row.getOrgName(), row.getDoctorName(), row.getGenerationTime() == null ? "" : row.getGenerationTime().toString().replace('T', ' '),
                row.getRecordTime(), row.getPatientName(), row.getPatientGender(), row.getPatientAge(), row.getAdmissionId(), row.getTemplateName(), InpatientLogRules.label(row.getStatus())};
            for (int i = 0; i < values.length; i++) { if (i > 0) csv.append(','); csv.append(cell(values[i])); }
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private QueryWrapper<InpatientEmrLog> optionScope(String hisOrgId) {
        QueryWrapper<InpatientEmrLog> query = new QueryWrapper<InpatientEmrLog>().eq("fg_active", "1");
        if (StringUtils.hasText(hisOrgId)) query.eq("his_org_id", hisOrgId);
        return query;
    }
    private LambdaQueryWrapper<InpatientEmrLog> baseScope(String hisOrgId) {
        LambdaQueryWrapper<InpatientEmrLog> q = new LambdaQueryWrapper<InpatientEmrLog>().eq(InpatientEmrLog::getFgActive, "1");
        if (StringUtils.hasText(hisOrgId)) q.eq(InpatientEmrLog::getHisOrgId, hisOrgId); return q;
    }
    private LambdaQueryWrapper<InpatientEmrLog> wrapper(InpatientLogQuery query) {
        String hisOrgId = scope.resolve(AdminContextHolder.get(), clean(query.getHisOrgId())).getHisOrgId();
        LambdaQueryWrapper<InpatientEmrLog> q = baseScope(hisOrgId);
        // No clinical payload in the paginated projection or CSV.
        q.select(InpatientEmrLog::getIdLog, InpatientEmrLog::getIdOrg, InpatientEmrLog::getHisOrgId, InpatientEmrLog::getOrgName,
            InpatientEmrLog::getDoctorName, InpatientEmrLog::getPatientName, InpatientEmrLog::getPatientGender, InpatientEmrLog::getPatientAge,
            InpatientEmrLog::getAdmissionId, InpatientEmrLog::getTemplateId, InpatientEmrLog::getTemplateName, InpatientEmrLog::getRecordTime,
            InpatientEmrLog::getGenerationTime, InpatientEmrLog::getStatus, InpatientEmrLog::getLatestSequence);
        String from = clean(query.getDateFrom()), to = clean(query.getDateTo());
        if ((from == null) != (to == null)) throw new BusinessException("请同时选择开始和结束日期");
        try {
            LocalDate start = from == null ? LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : LocalDate.parse(from);
            LocalDate end = to == null ? start.plusDays(6) : LocalDate.parse(to);
            if (end.isBefore(start)) throw new BusinessException("开始日期不能晚于结束日期");
            q.ge(InpatientEmrLog::getGenerationTime, start.atStartOfDay()).lt(InpatientEmrLog::getGenerationTime, end.plusDays(1).atStartOfDay());
        } catch (java.time.DateTimeException ex) { throw new BusinessException("日期格式必须为yyyy-MM-dd"); }
        if (clean(query.getIdOrg()) != null) q.eq(InpatientEmrLog::getIdOrg, clean(query.getIdOrg()));
        if (clean(query.getTemplateId()) != null) q.eq(InpatientEmrLog::getTemplateId, clean(query.getTemplateId()));
        if (clean(query.getStatus()) != null) {
            if (!InpatientLogRules.statusAllowed(query.getStatus())) throw new BusinessException("日志状态非法");
            q.eq(InpatientEmrLog::getStatus, query.getStatus());
        }
        if (clean(query.getDoctorName()) != null) q.apply(contains("doctor_name"), clean(query.getDoctorName()));
        if (clean(query.getPatientName()) != null) q.apply(contains("patient_name"), clean(query.getPatientName()));
        return q.orderByDesc(InpatientEmrLog::getGenerationTime, InpatientEmrLog::getIdLog);
    }
    private String contains(String column) { return dialect.isPgCompatible() ? "POSITION({0} IN " + column + ") > 0" : "INSTR(" + column + ", {0}) > 0"; }
    private String clean(String value) {
        if (!StringUtils.hasText(value)) return null;
        if (value.trim().length() > 128) throw new BusinessException("筛选条件过长"); return value.trim();
    }
    private String cell(String value) {
        String s = value == null ? "" : value, trimmed = s.trim();
        if ((!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) || s.startsWith("\t") || s.startsWith("\r") || s.startsWith("\n")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
    private Map<String, Object> summary(InpatientEmrLog log) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idLog", log.getIdLog()); r.put("idOrg", log.getIdOrg()); r.put("hisOrgId", log.getHisOrgId()); r.put("orgName", log.getOrgName());
        r.put("doctorName", log.getDoctorName()); r.put("patientName", log.getPatientName()); r.put("patientGender", log.getPatientGender()); r.put("patientAge", log.getPatientAge());
        r.put("admissionId", log.getAdmissionId()); r.put("templateId", log.getTemplateId()); r.put("templateName", log.getTemplateName()); r.put("recordTime", log.getRecordTime());
        r.put("generationTime", log.getGenerationTime()); r.put("status", log.getStatus()); r.put("latestSequence", log.getLatestSequence()); return r;
    }
}
