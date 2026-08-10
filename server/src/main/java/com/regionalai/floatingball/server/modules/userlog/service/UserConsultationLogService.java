package com.regionalai.floatingball.server.modules.userlog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.ExcelColumnWidthUtils;
import com.regionalai.floatingball.server.modules.audit.service.AuditLogDisplayCatalog;
import com.regionalai.floatingball.server.modules.audit.service.AudioLogStorageService;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.userlog.dto.ConsultationTimelineItem;
import com.regionalai.floatingball.server.modules.userlog.dto.UserConsultationLogListItem;
import com.regionalai.floatingball.server.modules.userlog.dto.UserConsultationLogRequest;
import com.regionalai.floatingball.server.modules.userlog.entity.AiUserConsultationLog;
import com.regionalai.floatingball.server.modules.userlog.mapper.AiUserConsultationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class UserConsultationLogService {

    private static final Logger log = LoggerFactory.getLogger(UserConsultationLogService.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String STATUS_GENERATED = "generated";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_ABANDONED = "abandoned";

    private final AiUserConsultationLogMapper userConsultationLogMapper;
    private final AiOpLogMapper aiOpLogMapper;
    private final ObjectMapper objectMapper;
    private final AudioLogStorageService audioLogStorageService;
    private final DatabaseDialect databaseDialect;
    private final AuditLogDisplayCatalog displayCatalog = new AuditLogDisplayCatalog();

    public UserConsultationLogService(AiUserConsultationLogMapper userConsultationLogMapper,
                                      AiOpLogMapper aiOpLogMapper,
                                      ObjectMapper objectMapper,
                                      AudioLogStorageService audioLogStorageService,
                                      DatabaseDialect databaseDialect) {
        this.userConsultationLogMapper = userConsultationLogMapper;
        this.aiOpLogMapper = aiOpLogMapper;
        this.objectMapper = objectMapper;
        this.audioLogStorageService = audioLogStorageService;
        this.databaseDialect = databaseDialect;
    }

    @Transactional
    public AiUserConsultationLog save(AiDevice device, UserConsultationLogRequest request) {
        if (request == null) {
            throw new BusinessException("用户日志请求不能为空");
        }
        String consultationId = trimToNull(request.getConsultationId());
        if (consultationId == null) {
            throw new BusinessException("问诊ID不能为空");
        }
        String consultationRoundId = trimToNull(request.getConsultationRoundId());
        if (consultationRoundId == null) {
            throw new BusinessException("问诊轮次ID不能为空");
        }
        String consultationType = normalizeConsultationType(request.getConsultationType());
        return saveResolved(device, request, consultationId, consultationRoundId, consultationType, false);
    }

    private AiUserConsultationLog saveResolved(AiDevice device,
                                               UserConsultationLogRequest request,
                                               String consultationId,
                                               String consultationRoundId,
                                               String consultationType,
                                               boolean retryingAfterDuplicate) {

        AiUserConsultationLog entity = findExisting(consultationRoundId);
        boolean create = entity == null;
        if (create) {
            entity = new AiUserConsultationLog();
            entity.setConsultationId(consultationId);
            entity.setConsultationRoundId(consultationRoundId);
            entity.setConsultationType(consultationType);
            entity.setIdDevice(device == null ? null : device.getIdDevice());
            entity.setFgActive("1");
            entity.setStatus(STATUS_GENERATED);
            entity.setIdLog(UUID.randomUUID().toString().replace("-", ""));
        }

        fillCommonFields(entity, device, request);
        fillSpeechText(entity, request);

        if (request.getFirstSnapshot() != null && !STATUS_COMPLETED.equals(entity.getStatus())) {
            entity.setFirstSnapshotJson(writeJson(request.getFirstSnapshot()));
        }
        if (request.getFinalSnapshot() != null) {
            entity.setFinalSnapshotJson(writeJson(request.getFinalSnapshot()));
            if (Boolean.TRUE.equals(request.getAbandoned())) {
                entity.setStatus(STATUS_ABANDONED);
            } else {
                entity.setStatus(STATUS_COMPLETED);
            }
        } else if (Boolean.TRUE.equals(request.getAbandoned())) {
            entity.setStatus(STATUS_ABANDONED);
        } else if (!StringUtils.hasText(entity.getStatus())) {
            entity.setStatus(STATUS_GENERATED);
        }
        if (request.getSelectionSnapshot() != null) {
            entity.setSelectionJson(writeJson(request.getSelectionSnapshot()));
        }
        if (request.getChangeSummary() != null) {
            entity.setChangeSummaryJson(writeJson(request.getChangeSummary()));
            Integer total = extractTotalChanges(request.getChangeSummary());
            if (total != null) {
                entity.setTotalChanges(total);
            }
        }

        String previousAudioPath = entity.getAudioFilePath();
        String storedAudioPath = storeAudioIfPresent(entity, request);
        try {
            if (create) {
                userConsultationLogMapper.insert(entity);
            } else {
                userConsultationLogMapper.updateById(entity);
            }
            if (storedAudioPath != null && StringUtils.hasText(previousAudioPath)
                && !storedAudioPath.equals(previousAudioPath)) {
                audioLogStorageService.deleteQuietly(previousAudioPath);
            }
        } catch (RuntimeException ex) {
            audioLogStorageService.deleteQuietly(storedAudioPath);
            if (create && !retryingAfterDuplicate && ex instanceof DuplicateKeyException) {
                return saveResolved(device, request, consultationId, consultationRoundId, consultationType, true);
            }
            log.error("user consultation log save failed. consultationId={}, error={}", consultationId, ex.getMessage());
            throw ex;
        }
        log.info("user consultation log saved. consultationId={}, type={}, status={}, create={}", consultationId, consultationType, entity.getStatus(), create);
        return entity;
    }

    public PageResponse<UserConsultationLogListItem> list(long current,
                                                          long size,
                                                          String keyword,
                                                          String consultationType,
                                                          String status,
                                                          Integer minChanges,
                                                          Integer maxChanges,
                                                          String dateFrom,
                                                          String dateTo) {
        Page<AiUserConsultationLog> page = new Page<AiUserConsultationLog>(current, size);
        LambdaQueryWrapper<AiUserConsultationLog> wrapper = buildListWrapper(keyword, consultationType, status, minChanges, maxChanges, dateFrom, dateTo);
        Page<AiUserConsultationLog> result = userConsultationLogMapper.selectPage(page, wrapper);
        return new PageResponse<UserConsultationLogListItem>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            toListItems(result.getRecords())
        );
    }

    private static final int EXPORT_MAX_ROWS = 10000;

    public byte[] exportExcel(String keyword,
                              String consultationType,
                              String status,
                              Integer minChanges,
                              Integer maxChanges,
                              String dateFrom,
                              String dateTo) {
        LambdaQueryWrapper<AiUserConsultationLog> wrapper = buildListWrapper(keyword, consultationType, status, minChanges, maxChanges, dateFrom, dateTo);
        wrapper.last(databaseDialect.firstRows(EXPORT_MAX_ROWS));
        List<AiUserConsultationLog> records = userConsultationLogMapper.selectList(wrapper);

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("用户日志");
            String[] headers = {"机构", "后台机构ID", "HIS机构ID", "医生", "业务时间", "患者", "性别", "年龄", "场景类型", "处理结果", "修改数", "业务ID"};
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            for (int i = 0; i < records.size(); i++) {
                AiUserConsultationLog record = records.get(i);
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(safe(record.getNaOrg(), record.getIdOrg()));
                row.createCell(1).setCellValue(safe(record.getIdOrg()));
                row.createCell(2).setCellValue(safe(record.getHisOrgId()));
                row.createCell(3).setCellValue(safe(record.getNaDoctor(), record.getIdDoctor()));
                row.createCell(4).setCellValue(record.getConsultationTime() != null ? record.getConsultationTime().format(DATE_TIME_FORMATTER) : "");
                row.createCell(5).setCellValue(safe(record.getPatientName(), record.getPatientId()));
                row.createCell(6).setCellValue(safe(record.getPatientGender()));
                row.createCell(7).setCellValue(safe(record.getPatientAge()));
                row.createCell(8).setCellValue(resolveConsultationTypeLabel(record.getConsultationType()));
                row.createCell(9).setCellValue(resolveStatusLabel(record.getStatus(), record.getConsultationType()));
                row.createCell(10).setCellValue(record.getTotalChanges() != null ? record.getTotalChanges() : 0);
                row.createCell(11).setCellValue(safe(record.getConsultationId()));
            }

            ExcelColumnWidthUtils.fitColumns(sheet, headers.length);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException("导出Excel失败：" + ex.getMessage());
        }
    }

    private LambdaQueryWrapper<AiUserConsultationLog> buildListWrapper(String keyword,
                                                                        String consultationType,
                                                                        String status,
                                                                        Integer minChanges,
                                                                        Integer maxChanges,
                                                                        String dateFrom,
                                                                        String dateTo) {
        LocalDateTime startTime = parseDateTime(dateFrom, false);
        LocalDateTime endTime = parseDateTime(dateTo, true);
        LambdaQueryWrapper<AiUserConsultationLog> wrapper = new LambdaQueryWrapper<AiUserConsultationLog>()
            .eq(AiUserConsultationLog::getFgActive, "1")
            .orderByDesc(AiUserConsultationLog::getConsultationTime);

        if (StringUtils.hasText(keyword)) {
            String text = keyword.trim();
            wrapper.and(q -> q
                .like(AiUserConsultationLog::getNaOrg, text)
                .or()
                .like(AiUserConsultationLog::getIdOrg, text)
                .or()
                .like(AiUserConsultationLog::getHisOrgId, text)
                .or()
                .like(AiUserConsultationLog::getNaDoctor, text)
                .or()
                .like(AiUserConsultationLog::getIdDoctor, text)
                .or()
                .like(AiUserConsultationLog::getPatientName, text)
                .or()
                .like(AiUserConsultationLog::getPatientId, text)
                .or()
                .like(AiUserConsultationLog::getConsultationId, text));
        }
        if (StringUtils.hasText(consultationType)) {
            wrapper.eq(AiUserConsultationLog::getConsultationType, normalizeConsultationType(consultationType));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AiUserConsultationLog::getStatus, status.trim().toLowerCase());
        }
        if (minChanges != null) {
            wrapper.ge(AiUserConsultationLog::getTotalChanges, minChanges);
        }
        if (maxChanges != null) {
            wrapper.le(AiUserConsultationLog::getTotalChanges, maxChanges);
        }
        if (startTime != null) {
            wrapper.ge(AiUserConsultationLog::getConsultationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(AiUserConsultationLog::getConsultationTime, endTime);
        }
        return wrapper;
    }

    private String safe(String... candidates) {
        for (String c : candidates) {
            if (StringUtils.hasText(c)) return c.trim();
        }
        return "";
    }

    private String resolveConsultationTypeLabel(String type) {
        if ("voice".equals(type)) return "语音问诊";
        if ("smart".equals(type)) return "智能问诊";
        if ("chronic_refill".equals(type)) return "慢病配药";
        if ("report_consultation".equals(type)) return "报告回诊";
        if ("report_interpretation".equals(type)) return "报告解读";
        return type != null ? type : "";
    }

    private String resolveStatusLabel(String status, String consultationType) {
        if (STATUS_COMPLETED.equals(status)) {
            return "report_interpretation".equals(consultationType) ? "已完成" : "一键回写";
        }
        if (STATUS_ABANDONED.equals(status)) return "放弃";
        if (STATUS_GENERATED.equals(status)) return "已生成";
        return status != null ? status : "";
    }

    public AiUserConsultationLog detail(String idLog) {
        if (!StringUtils.hasText(idLog)) {
            throw new BusinessException("用户日志ID不能为空");
        }
        AiUserConsultationLog log = userConsultationLogMapper.selectById(idLog);
        if (log == null || !"1".equals(log.getFgActive())) {
            throw new BusinessException("用户日志不存在");
        }
        return log;
    }

    public AudioFile resolveAudioFile(String idLog) {
        AiUserConsultationLog log = detail(idLog);
        if (!StringUtils.hasText(log.getAudioFilePath())) {
            throw new BusinessException("该用户日志没有录音文件");
        }
        try {
            return new AudioFile(
                audioLogStorageService.resolveExistingPath(log.getAudioFilePath()),
                log.getAudioMimeType(),
                log.getAudioFileName()
            );
        } catch (IOException ex) {
            throw new BusinessException("录音文件不可用：" + ex.getMessage());
        }
    }

    public List<ConsultationTimelineItem> getTimeline(String idLog) {
        AiUserConsultationLog log = detail(idLog);
        String consultationId = log.getConsultationId();
        if (!StringUtils.hasText(consultationId)) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<AiOpLog> wrapper = new LambdaQueryWrapper<AiOpLog>()
            .eq(AiOpLog::getFgActive, "1")
            .eq(AiOpLog::getConsultationId, consultationId)
            .orderByAsc(AiOpLog::getOperationTime);
        List<AiOpLog> opLogs = aiOpLogMapper.selectList(wrapper);
        List<ConsultationTimelineItem> items = new ArrayList<>();
        for (AiOpLog opLog : opLogs) {
            displayCatalog.enrich(opLog);
            ConsultationTimelineItem item = new ConsultationTimelineItem();
            item.setEventType(opLog.getSdLogType());
            item.setModule(opLog.getNaModule());
            item.setDisplayModule(opLog.getDisplayModule());
            item.setAction(opLog.getDesOp());
            item.setDisplayAction(firstNonBlank(opLog.getDisplayTitle(), opLog.getDisplayAction(), opLog.getDesOp()));
            item.setResult(opLog.getOpResult());
            item.setOperationTime(opLog.getOperationTime());
            item.setDetails(parseJsonQuietly(opLog.getPayloadJson()));
            items.add(item);
        }
        return items;
    }

    private Object parseJsonQuietly(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return json;
        }
    }

    private AiUserConsultationLog findExisting(String consultationRoundId) {
        // 按 consultationRoundId 合并同一轮问诊的多次提交（speech → firstSnapshot → finalSnapshot）。
        // consultationRoundId 是客户端生成的 UUID，每轮问诊唯一，贯穿该轮所有提交。
        // 已 completed/abandoned 的记录不会被命中，确保同一就诊再次发起问诊时生成新记录。
        LambdaQueryWrapper<AiUserConsultationLog> wrapper = new LambdaQueryWrapper<AiUserConsultationLog>()
            .eq(AiUserConsultationLog::getFgActive, "1")
            .eq(AiUserConsultationLog::getConsultationRoundId, consultationRoundId)
            .eq(AiUserConsultationLog::getStatus, STATUS_GENERATED);
        return userConsultationLogMapper.selectOne(wrapper.last(databaseDialect.firstRows(1)));
    }

    private void fillCommonFields(AiUserConsultationLog entity, AiDevice device, UserConsultationLogRequest request) {
        entity.setIdOrg(firstNonBlank(device == null ? null : device.getIdOrg(), entity.getIdOrg()));
        entity.setHisOrgId(firstNonBlank(request.getHisOrgId(), entity.getHisOrgId()));
        entity.setNaOrg(firstNonBlank(request.getOrgName(), entity.getNaOrg()));
        entity.setIdDoctor(firstNonBlank(request.getDoctorId(), entity.getIdDoctor()));
        entity.setDoctorWorkNo(firstNonBlank(request.getDoctorWorkNo(), entity.getDoctorWorkNo()));
        entity.setNaDoctor(firstNonBlank(request.getDoctorName(), entity.getNaDoctor()));
        entity.setIdDept(firstNonBlank(request.getDeptId(), entity.getIdDept()));
        entity.setNaDept(firstNonBlank(request.getDeptName(), entity.getNaDept()));
        entity.setPatientId(firstNonBlank(request.getPatientId(), entity.getPatientId()));
        entity.setPatientName(firstNonBlank(request.getPatientName(), entity.getPatientName()));
        entity.setPatientGender(firstNonBlank(request.getPatientGender(), entity.getPatientGender()));
        entity.setPatientAge(firstNonBlank(request.getPatientAge(), entity.getPatientAge()));
        LocalDateTime requestTime = parseEpochMillis(request.getConsultationTime());
        if (requestTime != null) {
            entity.setConsultationTime(requestTime);
        } else if (entity.getConsultationTime() == null) {
            entity.setConsultationTime(LocalDateTime.now());
        }
    }

    private void fillSpeechText(AiUserConsultationLog entity, UserConsultationLogRequest request) {
        String speechText = trimToNull(request.getSpeechText());
        if (speechText != null) {
            entity.setSpeechText(speechText);
        }
    }

    private String storeAudioIfPresent(AiUserConsultationLog entity, UserConsultationLogRequest request) {
        String audio = trimToNull(request.getAudio());
        if (audio == null) {
            return null;
        }
        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(stripDataUrlPrefix(audio));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("用户日志录音内容不是合法 Base64");
        }
        if (audioBytes.length == 0) {
            return null;
        }
        String fileName = resolveAudioFileName(request);
        try {
            String storedPath = audioLogStorageService.store(
                audioBytes,
                fileName,
                entity.getIdLog() + "-" + System.currentTimeMillis()
            );
            entity.setAudioFilePath(storedPath);
            entity.setAudioFileName(fileName);
            entity.setAudioMimeType(firstNonBlank(request.getAudioMimeType(), resolveAudioMimeType(fileName)));
            entity.setAudioSize((long) audioBytes.length);
            return storedPath;
        } catch (IOException ex) {
            throw new BusinessException("用户日志录音保存失败：" + ex.getMessage());
        }
    }

    private String stripDataUrlPrefix(String value) {
        int commaIndex = value.indexOf(',');
        if (value.startsWith("data:") && commaIndex >= 0) {
            return value.substring(commaIndex + 1);
        }
        return value;
    }

    private String resolveAudioFileName(UserConsultationLogRequest request) {
        String fileName = trimToNull(request.getAudioFileName());
        if (fileName != null) {
            return fileName;
        }
        String extension = resolveAudioExtension(request.getAudioMimeType(), request.getAudioFormat());
        return "voice-consultation-" + System.currentTimeMillis() + extension;
    }

    private String resolveAudioExtension(String mimeType, String format) {
        String normalizedMimeType = trimToNull(mimeType);
        String normalizedFormat = trimToNull(format);
        if ("audio/wav".equalsIgnoreCase(normalizedMimeType) || "audio/wave".equalsIgnoreCase(normalizedMimeType)) {
            return ".wav";
        }
        if ("audio/webm".equalsIgnoreCase(normalizedMimeType)) {
            return ".webm";
        }
        if ("audio/mpeg".equalsIgnoreCase(normalizedMimeType)) {
            return ".mp3";
        }
        if ("audio/mp4".equalsIgnoreCase(normalizedMimeType)) {
            return ".m4a";
        }
        if ("audio/ogg".equalsIgnoreCase(normalizedMimeType)) {
            return ".ogg";
        }
        if ("audio/pcm".equalsIgnoreCase(normalizedMimeType) || "pcm".equalsIgnoreCase(normalizedFormat)) {
            return ".pcm";
        }
        return ".bin";
    }

    private String resolveAudioMimeType(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase();
        if (normalized.endsWith(".wav")) {
            return "audio/wav";
        }
        if (normalized.endsWith(".webm")) {
            return "audio/webm";
        }
        if (normalized.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (normalized.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (normalized.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (normalized.endsWith(".pcm")) {
            return "audio/pcm";
        }
        return null;
    }

    private String normalizeConsultationType(String value) {
        String text = trimToNull(value);
        if (text == null) {
            throw new BusinessException("问诊类型不能为空");
        }
        String normalized = text.toLowerCase();
        if (!"voice".equals(normalized)
            && !"smart".equals(normalized)
            && !"chronic_refill".equals(normalized)
            && !"report_consultation".equals(normalized)
            && !"report_interpretation".equals(normalized)) {
            throw new BusinessException("问诊类型非法");
        }
        return normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("用户日志快照序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Integer extractTotalChanges(Object changeSummary) {
        if (changeSummary instanceof java.util.Map) {
            Object total = ((java.util.Map<String, Object>) changeSummary).get("totalChanges");
            if (total instanceof Number) {
                return ((Number) total).intValue();
            }
        }
        return null;
    }

    private LocalDateTime parseEpochMillis(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault());
    }

    private List<UserConsultationLogListItem> toListItems(List<AiUserConsultationLog> records) {
        List<UserConsultationLogListItem> items = new ArrayList<UserConsultationLogListItem>();
        if (records == null) {
            return items;
        }
        for (AiUserConsultationLog record : records) {
            UserConsultationLogListItem item = new UserConsultationLogListItem();
            item.setIdLog(record.getIdLog());
            item.setConsultationId(record.getConsultationId());
            item.setConsultationRoundId(record.getConsultationRoundId());
            item.setIdOrg(record.getIdOrg());
            item.setHisOrgId(record.getHisOrgId());
            item.setNaOrg(record.getNaOrg());
            item.setIdDoctor(record.getIdDoctor());
            item.setDoctorWorkNo(record.getDoctorWorkNo());
            item.setNaDoctor(record.getNaDoctor());
            item.setConsultationType(record.getConsultationType());
            item.setConsultationTime(record.getConsultationTime());
            item.setPatientId(record.getPatientId());
            item.setPatientName(record.getPatientName());
            item.setPatientGender(record.getPatientGender());
            item.setPatientAge(record.getPatientAge());
            item.setHasAudio(record.getHasAudio());
            item.setHasSpeechText(record.getHasSpeechText());
            item.setTotalChanges(record.getTotalChanges());
            item.setStatus(record.getStatus());
            items.add(item);
        }
        return items;
    }

    private LocalDateTime parseDateTime(String value, boolean endOfDay) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String candidate = value.trim();
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
            throw new BusinessException("用户日志查询时间格式非法");
        }
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            String text = trimToNull(candidate);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    public static class AudioFile {

        private final Path path;
        private final String mimeType;
        private final String fileName;

        public AudioFile(Path path, String mimeType, String fileName) {
            this.path = path;
            this.mimeType = mimeType;
            this.fileName = fileName;
        }

        public Path getPath() {
            return path;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getFileName() {
            return fileName;
        }
    }
}
