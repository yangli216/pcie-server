package com.regionalai.floatingball.server.modules.patientmemory.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.ObjectIdUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.patientmemory.dto.AdminPatientMemoryDetailVO;
import com.regionalai.floatingball.server.modules.patientmemory.dto.AdminPatientMemoryFactActionRequest;
import com.regionalai.floatingball.server.modules.patientmemory.dto.AdminPatientMemoryFactUpdateRequest;
import com.regionalai.floatingball.server.modules.patientmemory.dto.AdminPatientMemoryListItem;
import com.regionalai.floatingball.server.modules.patientmemory.dto.AdminPatientMemoryQuery;
import com.regionalai.floatingball.server.modules.patientmemory.entity.AiPatientMemory;
import com.regionalai.floatingball.server.modules.patientmemory.entity.AiPatientMemoryAudit;
import com.regionalai.floatingball.server.modules.patientmemory.entity.AiPatientMemoryFact;
import com.regionalai.floatingball.server.modules.patientmemory.entity.AiPatientMemoryObservation;
import com.regionalai.floatingball.server.modules.patientmemory.mapper.AiPatientMemoryAuditMapper;
import com.regionalai.floatingball.server.modules.patientmemory.mapper.AiPatientMemoryFactMapper;
import com.regionalai.floatingball.server.modules.patientmemory.mapper.AiPatientMemoryMapper;
import com.regionalai.floatingball.server.modules.patientmemory.mapper.AiPatientMemoryObservationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminPatientMemoryService {

    private static final Set<String> EDITABLE_STATUSES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "active", "historical", "unknown", "disputed"
    )));
    private static final Set<String> EDITABLE_CONFIDENCE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
        "confirmed", "structured", "extracted", "low"
    )));

    private final AiPatientMemoryMapper memoryMapper;
    private final AiPatientMemoryFactMapper factMapper;
    private final AiPatientMemoryObservationMapper observationMapper;
    private final AiPatientMemoryAuditMapper auditMapper;
    private final PatientMemoryService patientMemoryService;
    private final ObjectMapper objectMapper;

    public AdminPatientMemoryService(AiPatientMemoryMapper memoryMapper,
                                     AiPatientMemoryFactMapper factMapper,
                                     AiPatientMemoryObservationMapper observationMapper,
                                     AiPatientMemoryAuditMapper auditMapper,
                                     PatientMemoryService patientMemoryService,
                                     ObjectMapper objectMapper) {
        this.memoryMapper = memoryMapper;
        this.factMapper = factMapper;
        this.observationMapper = observationMapper;
        this.auditMapper = auditMapper;
        this.patientMemoryService = patientMemoryService;
        this.objectMapper = objectMapper;
    }

    public PageResponse<AdminPatientMemoryListItem> list(AdminCurrentUser user, AdminPatientMemoryQuery query) {
        PageRequest pageRequest = PageRequest.of(
            query == null ? null : Long.valueOf(query.getCurrent()),
            query == null ? null : Long.valueOf(query.getSize())
        );
        QueryWrapper<AiPatientMemory> wrapper = new QueryWrapper<AiPatientMemory>()
            .eq("fg_active", "1")
            .orderByDesc("last_sync_time");
        applyOrgScope(wrapper, user, query == null ? null : query.getIdOrg());
        if (query != null && StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(item -> item
                .like("patient_name", keyword)
                .or().like("patient_id", keyword)
                .or().like("id_his_org", keyword));
        }
        if (query != null && StringUtils.hasText(query.getQualityStatus())) {
            wrapper.eq("quality_status", query.getQualityStatus().trim());
        }

        Page<AiPatientMemory> result = memoryMapper.selectPage(
            new Page<AiPatientMemory>(pageRequest.getCurrent(), pageRequest.getSize()),
            wrapper
        );
        List<AdminPatientMemoryListItem> records = result.getRecords().stream()
            .map(this::toListItem)
            .collect(Collectors.toList());
        return new PageResponse<AdminPatientMemoryListItem>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    public AdminPatientMemoryDetailVO detail(AdminCurrentUser user, String memoryId) {
        AiPatientMemory memory = requireMemory(user, memoryId);
        AdminPatientMemoryDetailVO detail = new AdminPatientMemoryDetailVO();
        detail.setMemory(toListItem(memory));
        detail.setFacts(loadFacts(memoryId).stream().map(this::toFactVO).collect(Collectors.toList()));
        detail.setObservations(loadObservations(memoryId).stream().map(this::toObservationVO).collect(Collectors.toList()));
        detail.setAudits(loadAudits(memoryId).stream().map(this::toAuditVO).collect(Collectors.toList()));
        return detail;
    }

    @Transactional
    public AdminPatientMemoryDetailVO updateFact(AdminCurrentUser user,
                                                 String memoryId,
                                                 String factId,
                                                 AdminPatientMemoryFactUpdateRequest request) {
        AiPatientMemory memory = requireMemory(user, memoryId);
        AiPatientMemoryFact fact = requireFact(memoryId, factId);
        String note = requiredNote(request == null ? null : request.getCorrectionNote());
        String beforeJson = writeJson(fact);
        String previousStatus = fact.getFactStatus();

        if (StringUtils.hasText(request.getName())) {
            fact.setFactName(truncate(request.getName(), 256));
        }
        if (request.getValueText() != null) {
            fact.setValueText(truncate(request.getValueText(), 1000));
        }
        if (StringUtils.hasText(request.getStatus())) {
            String status = request.getStatus().trim().toLowerCase();
            if (!EDITABLE_STATUSES.contains(status)) {
                throw new BusinessException("事实状态不支持");
            }
            fact.setFactStatus(status);
        }
        if (StringUtils.hasText(request.getConfidence())) {
            String confidence = request.getConfidence().trim().toLowerCase();
            if (!EDITABLE_CONFIDENCE.contains(confidence)) {
                throw new BusinessException("事实可信度不支持");
            }
            fact.setConfidenceLevel(confidence);
        } else {
            fact.setConfidenceLevel("confirmed");
        }
        fact.setOriginCode("admin");
        fact.setRevisionNo(valueOrZero(fact.getRevisionNo()) + 1);
        factMapper.updateById(fact);

        adjustConflictCount(memory, previousStatus, fact.getFactStatus(), "1".equals(fact.getFgSuppressed()));
        recordAudit(user, memoryId, factId, "correct", beforeJson, writeJson(fact), note);
        bumpMemoryVersion(memory);
        return detail(user, memoryId);
    }

    @Transactional
    public AdminPatientMemoryDetailVO suppressFact(AdminCurrentUser user,
                                                   String memoryId,
                                                   String factId,
                                                   AdminPatientMemoryFactActionRequest request) {
        AiPatientMemory memory = requireMemory(user, memoryId);
        AiPatientMemoryFact fact = requireFact(memoryId, factId);
        String note = requiredNote(request == null ? null : request.getNote());
        if ("1".equals(fact.getFgSuppressed())) {
            return detail(user, memoryId);
        }
        String beforeJson = writeJson(fact);
        fact.setFgSuppressed("1");
        fact.setRevisionNo(valueOrZero(fact.getRevisionNo()) + 1);
        factMapper.updateById(fact);
        if ("disputed".equals(fact.getFactStatus())) {
            memory.setConflictCount(Math.max(0, valueOrZero(memory.getConflictCount()) - 1));
        }
        recordAudit(user, memoryId, factId, "suppress", beforeJson, writeJson(fact), note);
        bumpMemoryVersion(memory);
        return detail(user, memoryId);
    }

    @Transactional
    public AdminPatientMemoryDetailVO restoreFact(AdminCurrentUser user,
                                                  String memoryId,
                                                  String factId,
                                                  AdminPatientMemoryFactActionRequest request) {
        AiPatientMemory memory = requireMemory(user, memoryId);
        AiPatientMemoryFact fact = requireFact(memoryId, factId);
        String note = requiredNote(request == null ? null : request.getNote());
        if (!"1".equals(fact.getFgSuppressed())) {
            return detail(user, memoryId);
        }
        String beforeJson = writeJson(fact);
        fact.setFgSuppressed("0");
        fact.setRevisionNo(valueOrZero(fact.getRevisionNo()) + 1);
        factMapper.updateById(fact);
        if ("disputed".equals(fact.getFactStatus())) {
            memory.setConflictCount(valueOrZero(memory.getConflictCount()) + 1);
        }
        recordAudit(user, memoryId, factId, "restore", beforeJson, writeJson(fact), note);
        bumpMemoryVersion(memory);
        return detail(user, memoryId);
    }

    private void bumpMemoryVersion(AiPatientMemory memory) {
        memory.setMemoryVersion(valueOrZero(memory.getMemoryVersion()) + 1L);
        patientMemoryService.refreshSummary(memory);
    }

    private void adjustConflictCount(AiPatientMemory memory,
                                     String previousStatus,
                                     String nextStatus,
                                     boolean suppressed) {
        if (suppressed || String.valueOf(previousStatus).equals(nextStatus)) {
            return;
        }
        if ("disputed".equals(previousStatus)) {
            memory.setConflictCount(Math.max(0, valueOrZero(memory.getConflictCount()) - 1));
        }
        if ("disputed".equals(nextStatus)) {
            memory.setConflictCount(valueOrZero(memory.getConflictCount()) + 1);
        }
    }

    private AiPatientMemory requireMemory(AdminCurrentUser user, String memoryId) {
        if (!StringUtils.hasText(memoryId)) {
            throw new BusinessException("患者记忆ID不能为空");
        }
        AiPatientMemory memory = memoryMapper.selectOne(new QueryWrapper<AiPatientMemory>()
            .eq("id_memory", memoryId.trim())
            .eq("fg_active", "1"));
        if (memory == null) {
            throw new BusinessException("患者记忆不存在");
        }
        if (!isSystemAdmin(user) && (user == null || !StringUtils.hasText(user.getIdOrg()) || !user.getIdOrg().equals(memory.getIdOrg()))) {
            throw new BusinessException("当前账号无权访问该机构的患者记忆");
        }
        return memory;
    }

    private AiPatientMemoryFact requireFact(String memoryId, String factId) {
        AiPatientMemoryFact fact = factMapper.selectOne(new QueryWrapper<AiPatientMemoryFact>()
            .eq("id_fact", factId)
            .eq("id_memory", memoryId)
            .eq("fg_active", "1"));
        if (fact == null) {
            throw new BusinessException("患者记忆事实不存在");
        }
        return fact;
    }

    private void applyOrgScope(QueryWrapper<AiPatientMemory> wrapper, AdminCurrentUser user, String requestedOrgId) {
        if (isSystemAdmin(user)) {
            if (StringUtils.hasText(requestedOrgId)) {
                wrapper.eq("id_org", requestedOrgId.trim());
            }
            return;
        }
        if (user == null || !StringUtils.hasText(user.getIdOrg())) {
            throw new BusinessException("当前账号缺少机构范围");
        }
        wrapper.eq("id_org", user.getIdOrg());
    }

    private boolean isSystemAdmin(AdminCurrentUser user) {
        return user != null && user.getRoles() != null && user.getRoles().contains("SYSTEM_ADMIN");
    }

    private List<AiPatientMemoryFact> loadFacts(String memoryId) {
        List<AiPatientMemoryFact> facts = factMapper.selectList(new QueryWrapper<AiPatientMemoryFact>()
            .eq("id_memory", memoryId)
            .eq("fg_active", "1")
            .orderByAsc("fg_suppressed")
            .orderByAsc("fact_type")
            .orderByDesc("last_observed_time"));
        return facts == null ? Collections.<AiPatientMemoryFact>emptyList() : facts;
    }

    private List<AiPatientMemoryObservation> loadObservations(String memoryId) {
        Page<AiPatientMemoryObservation> result = observationMapper.selectPage(
            new Page<AiPatientMemoryObservation>(1, 100),
            new QueryWrapper<AiPatientMemoryObservation>()
                .eq("id_memory", memoryId)
                .eq("fg_active", "1")
                .orderByDesc("occurred_time")
                .orderByDesc("insert_time")
        );
        return result == null || result.getRecords() == null
            ? Collections.<AiPatientMemoryObservation>emptyList()
            : result.getRecords();
    }

    private List<AiPatientMemoryAudit> loadAudits(String memoryId) {
        Page<AiPatientMemoryAudit> result = auditMapper.selectPage(
            new Page<AiPatientMemoryAudit>(1, 100),
            new QueryWrapper<AiPatientMemoryAudit>()
                .eq("id_memory", memoryId)
                .eq("fg_active", "1")
                .orderByDesc("operation_time")
        );
        return result == null || result.getRecords() == null
            ? Collections.<AiPatientMemoryAudit>emptyList()
            : result.getRecords();
    }

    private AdminPatientMemoryListItem toListItem(AiPatientMemory memory) {
        AdminPatientMemoryListItem item = new AdminPatientMemoryListItem();
        item.setMemoryId(memory.getIdMemory());
        item.setIdOrg(memory.getIdOrg());
        item.setIdHisOrg(memory.getIdHisOrg());
        item.setPatientId(memory.getPatientId());
        item.setPatientName(memory.getPatientName());
        item.setPatientGender(memory.getPatientGender());
        item.setPatientAge(memory.getPatientAge());
        item.setMemoryVersion(memory.getMemoryVersion());
        item.setConflictCount(valueOrZero(memory.getConflictCount()));
        item.setLastSyncTime(memory.getLastSyncTime());
        item.setLastSourceTime(memory.getLastSourceTime());
        item.setQualityStatus(StringUtils.hasText(memory.getQualityStatus())
            ? memory.getQualityStatus()
            : (valueOrZero(memory.getConflictCount()) > 0 ? "conflicted" : "partial"));
        item.setFactCount(countFacts(memory.getIdMemory(), null));
        item.setAllergyCount(countFacts(memory.getIdMemory(), "allergy"));
        item.setDiagnosisCount(countFacts(memory.getIdMemory(), "diagnosis") + countFacts(memory.getIdMemory(), "chronic_condition"));
        item.setMedicationCount(countFacts(memory.getIdMemory(), "medication"));
        return item;
    }

    private int countFacts(String memoryId, String factType) {
        QueryWrapper<AiPatientMemoryFact> wrapper = new QueryWrapper<AiPatientMemoryFact>()
            .eq("id_memory", memoryId)
            .eq("fg_active", "1")
            .eq("fg_suppressed", "0")
            .ne("fact_status", "inactive");
        if (factType != null) {
            wrapper.eq("fact_type", factType);
        }
        Long count = factMapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    private AdminPatientMemoryDetailVO.FactVO toFactVO(AiPatientMemoryFact fact) {
        AdminPatientMemoryDetailVO.FactVO item = new AdminPatientMemoryDetailVO.FactVO();
        item.setFactId(fact.getIdFact());
        item.setFactKey(fact.getFactKey());
        item.setFactType(fact.getFactType());
        item.setCode(fact.getFactCode());
        item.setName(fact.getFactName());
        item.setValueText(fact.getValueText());
        item.setStatus(fact.getFactStatus());
        item.setConfidence(fact.getConfidenceLevel());
        item.setEvidenceText(fact.getEvidenceText());
        item.setSourceType(fact.getSourceType());
        item.setSourceKey(fact.getSourceKey());
        item.setOrigin(fact.getOriginCode());
        item.setSuppressed("1".equals(fact.getFgSuppressed()));
        item.setRevisionNo(fact.getRevisionNo());
        item.setFirstObservedTime(fact.getFirstObservedTime());
        item.setLastObservedTime(fact.getLastObservedTime());
        return item;
    }

    private AdminPatientMemoryDetailVO.ObservationVO toObservationVO(AiPatientMemoryObservation observation) {
        AdminPatientMemoryDetailVO.ObservationVO item = new AdminPatientMemoryDetailVO.ObservationVO();
        item.setObservationId(observation.getIdObservation());
        item.setSourceKey(observation.getSourceKey());
        item.setSourceType(observation.getSourceType());
        item.setSourceVersion(observation.getSourceVersion());
        item.setOperation(observation.getOperationCode());
        item.setVisitId(observation.getVisitId());
        item.setPayloadHash(observation.getPayloadHash());
        item.setLatest("1".equals(observation.getFgLatest()));
        item.setOccurredTime(observation.getOccurredTime());
        item.setReceivedTime(observation.getInsertTime());
        item.setFactCount(parseFactCount(observation.getFactsJson()));
        return item;
    }

    private int parseFactCount(String factsJson) {
        if (!StringUtils.hasText(factsJson)) {
            return 0;
        }
        try {
            List<Object> facts = objectMapper.readValue(factsJson, new TypeReference<List<Object>>() { });
            return facts == null ? 0 : facts.size();
        } catch (Exception ex) {
            return 0;
        }
    }

    private AdminPatientMemoryDetailVO.AuditVO toAuditVO(AiPatientMemoryAudit audit) {
        AdminPatientMemoryDetailVO.AuditVO item = new AdminPatientMemoryDetailVO.AuditVO();
        item.setAuditId(audit.getIdAudit());
        item.setFactId(audit.getIdFact());
        item.setAction(audit.getActionCode());
        item.setNote(audit.getNoteText());
        item.setOperatorId(audit.getIdOperator());
        item.setOperatorName(audit.getNaOperator());
        item.setOperationTime(audit.getOperationTime());
        return item;
    }

    private void recordAudit(AdminCurrentUser user,
                             String memoryId,
                             String factId,
                             String action,
                             String beforeJson,
                             String afterJson,
                             String note) {
        AiPatientMemoryAudit audit = new AiPatientMemoryAudit();
        audit.setIdAudit(ObjectIdUtils.next());
        audit.setIdMemory(memoryId);
        audit.setIdFact(factId);
        audit.setActionCode(action);
        audit.setBeforeJson(beforeJson);
        audit.setAfterJson(afterJson);
        audit.setNoteText(note);
        audit.setIdOperator(user == null ? null : user.getIdUser());
        audit.setNaOperator(user == null ? null : user.getNaUser());
        audit.setOperationTime(LocalDateTime.now());
        audit.setFgActive("1");
        auditMapper.insert(audit);
    }

    private String requiredNote(String note) {
        if (!StringUtils.hasText(note)) {
            throw new BusinessException("请填写治理原因，便于后续审计追溯");
        }
        return truncate(note, 1000);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("患者记忆治理记录序列化失败");
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
