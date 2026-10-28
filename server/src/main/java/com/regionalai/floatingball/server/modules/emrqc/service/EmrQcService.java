package com.regionalai.floatingball.server.modules.emrqc.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.NotFoundException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.common.util.AesUtils;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcDetailVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcDoctorVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcRecordVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcReviewRequest;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcSectionVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcSessionVO;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcFinding;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcRecord;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrSection;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisContentNormalizer;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisEmrClient;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisRecordCache;
import com.regionalai.floatingball.server.modules.phisemr.service.PhisSessionScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class EmrQcService {

    private static final Pattern ID_CARD = Pattern.compile("^\\d{17}[\\dXx]$");
    private static final Set<String> QC_STATUS = new HashSet<String>(
        java.util.Arrays.asList("all", "analyzed"));

    private final EmrQcProperties properties;
    private final EmrQcSchemaService schemaService;
    private final PhisSessionScope sessionScope;
    private final PhisRecordCache recordCache;
    private final PhisEmrClient phisClient;
    private final PhisContentNormalizer normalizer;
    private final SensitiveDataMasker masker;
    private final EmrQcAiClient aiClient;
    private final EmrQcWriter writer;
    private final EmrQcAuditService auditService;
    private final ConfigService configService;
    private final AiEmrQcRecordMapper recordMapper;
    private final com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper orgMapper;
    private final ObjectMapper objectMapper;
    private final BbpDirectoryService directoryService;
    private final AesUtils aesUtils;

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<String, ReentrantLock>();

    public EmrQcService(EmrQcProperties properties,
                        EmrQcSchemaService schemaService,
                        PhisSessionScope sessionScope,
                        PhisRecordCache recordCache,
                        PhisEmrClient phisClient,
                        PhisContentNormalizer normalizer,
                        SensitiveDataMasker masker,
                        EmrQcAiClient aiClient,
                        EmrQcWriter writer,
                        EmrQcAuditService auditService,
                        ConfigService configService,
                        AiEmrQcRecordMapper recordMapper,
                        com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper orgMapper,
                        ObjectMapper objectMapper,
                        BbpDirectoryService directoryService,
                        AesUtils aesUtils) {
        this.properties = properties;
        this.schemaService = schemaService;
        this.sessionScope = sessionScope;
        this.recordCache = recordCache;
        this.phisClient = phisClient;
        this.normalizer = normalizer;
        this.masker = masker;
        this.aiClient = aiClient;
        this.writer = writer;
        this.auditService = auditService;
        this.configService = configService;
        this.recordMapper = recordMapper;
        this.orgMapper = orgMapper;
        this.objectMapper = objectMapper;
        this.directoryService = directoryService;
        this.aesUtils = aesUtils;
    }

    public EmrQcSessionVO session(AdminCurrentUser currentUser) {
        ensureEnabled();
        PhisSessionScope.Scope scope = sessionScope.describe(currentUser);
        EmrQcSessionVO vo = new EmrQcSessionVO();
        vo.setAvailable(scope.isAvailable());
        vo.setHisOrgId(scope.getHisOrgId());
        vo.setHisOrgName(scope.getHisOrgName());
        vo.setDeptId(scope.getDeptId());
        vo.setDeptName(scope.getDeptName());
        vo.setDoctorName(scope.getDoctorName());
        vo.setRoleName(scope.getRoleName());
        vo.setSessionExpiresAt(scope.getSessionExpiresAt());
        vo.setUnavailableCode(scope.getUnavailableCode());
        vo.setUnavailableReason(scope.getUnavailableReason());
        return vo;
    }

    public List<EmrQcDoctorVO> doctors(AdminCurrentUser currentUser) {
        ensureEnabled();
        PhisSessionScope.Scope scope = sessionScope.require(currentUser);
        String bbpOrgId = currentUser.getBbpOrgId();
        if (!StringUtils.hasText(bbpOrgId)) {
            return Collections.emptyList();
        }
        List<EmrQcDoctorVO> result = new ArrayList<EmrQcDoctorVO>();
        for (JsonNode person : phisClient.doctorCandidates(scope.getSession().getCookies())) {
            if (!bbpOrgId.trim().equals(person.path("orgId").asText("").trim())
                    || !StringUtils.hasText(person.path("userId").asText(""))
                    || !StringUtils.hasText(person.path("na").asText(""))) {
                continue;
            }
            EmrQcDoctorVO vo = new EmrQcDoctorVO();
            vo.setUserId(person.path("userId").asText().trim());
            vo.setName(person.path("na").asText().trim());
            vo.setCode(person.path("cd").asText(""));
            vo.setDeptId(person.path("deptId").asText(""));
            vo.setDeptName(person.path("deptIdText").asText(""));
            result.add(vo);
        }
        return result;
    }

    private String requireOrgDoctorInsertUser(AdminCurrentUser currentUser, String doctorUserId) {
        if (doctorUserId == null) {
            return null;
        }
        String bbpOrgId = currentUser.getBbpOrgId();
        if (!StringUtils.hasText(bbpOrgId)) {
            throw new BusinessException("EMR-QC-400", "当前登录账号缺少机构信息，无法按开立医生筛选");
        }
        for (EmrQcDoctorVO doctor : doctors(currentUser)) {
            if (doctorUserId.equals(doctor.getUserId())) {
                return doctorUserId;
            }
        }
        throw new BusinessException("EMR-QC-400", "开立医生不属于当前登录机构");
    }

    private String text(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : "";
    }

    public PageResponse<EmrQcRecordVO> list(AdminCurrentUser currentUser,
                                            String dateFrom,
                                            String dateTo,
                                            String patientName,
                                            String idCard,
                                            String doctorUserId,
                                            String qcStatus,
                                            long current,
                                            long size) {
        return list(currentUser, dateFrom, dateTo, patientName, idCard, doctorUserId, qcStatus, current, size, null);
    }

    public PageResponse<EmrQcRecordVO> list(AdminCurrentUser currentUser,
                                            String dateFrom, String dateTo, String patientName, String idCard,
                                            String doctorUserId, String qcStatus, long current, long size,
                                            String deptName) {
        ensureEnabled();
        schemaService.requireReady();
        PhisSessionScope.Scope scope = sessionScope.require(currentUser);
        DateRange range = resolveRange(dateFrom, dateTo);
        String normalizedQcStatus = normalizeQcStatus(qcStatus);
        String normalizedName = validateText(patientName, 64, "患者姓名");
        String normalizedDoctorId = validateText(doctorUserId, 64, "开立医生");
        if (StringUtils.hasText(deptName)) {
            throw new BusinessException("EMR-QC-400", "科室筛选暂不可用，请清除后重试");
        }
        String normalizedIdCard = validateIdCard(idCard);
        String insertUser = requireOrgDoctorInsertUser(currentUser, normalizedDoctorId);

        PageRequest pageRequest = PageRequest.of(current, size);
        if (pageRequest.getCurrent() > Integer.MAX_VALUE) {
            throw new BusinessException("EMR-QC-400", "页码超出支持范围");
        }
        if ("analyzed".equals(normalizedQcStatus)) {
            return listAnalyzed(currentUser, range, normalizedName, normalizedIdCard, insertUser, pageRequest);
        }
        PhisEmrClient.ListPage upstream = phisClient.list(scope.getSession().getCookies(), range.from, range.to,
            normalizedName, normalizedIdCard, insertUser, scope.getHisOrgId(),
            (int) pageRequest.getCurrent(), (int) pageRequest.getSize());
        List<PhisEmrRecord> records = upstream.getRecords();
        if (records.isEmpty() && pageRequest.getOffset() < upstream.getTotal()) {
            throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历列表不完整，请稍后重试");
        }
        resolveDoctorNames(currentUser, records);
        if (StringUtils.hasText(insertUser)) {
            for (PhisEmrRecord record : records) record.setDoctorUserId(insertUser);
        }
        recordCache.putAll(currentUser.getAuthSessionId(), records);

        Map<String, AiEmrQcRecord> projections = findProjections(firstText(currentUser.getIdOrg(), currentUser.getBbpOrgId()), ids(records));
        List<EmrQcRecordVO> filtered = new ArrayList<EmrQcRecordVO>();
        for (PhisEmrRecord record : records) {
            AiEmrQcRecord projection = projections.get(record.getIdMedrecdoc());
            EmrQcRecordVO vo = toRecordVO(record, projection);
            filtered.add(vo);
        }
        return new PageResponse<EmrQcRecordVO>(
            pageRequest.getCurrent(), pageRequest.getSize(), upstream.getTotal(), filtered);
    }

    private PageResponse<EmrQcRecordVO> listAnalyzed(AdminCurrentUser user, DateRange range,
                                                     String name, String idCard, String doctorId,
                                                     PageRequest request) {
        String orgId = firstText(user.getIdOrg(), user.getBbpOrgId());
        if (!StringUtils.hasText(orgId)) throw new BusinessException("EMR-QC-400", "当前登录账号缺少机构信息");
        QueryWrapper<AiEmrQcRecord> query = new QueryWrapper<AiEmrQcRecord>()
            .eq("id_org", orgId)
            .and(q -> q.ge("phis_create_time", range.from.atStartOfDay())
                .lt("phis_create_time", range.to.plusDays(1).atStartOfDay())
                .or(r -> r.isNull("phis_create_time")
                    .ge("visit_time", range.from.atStartOfDay())
                    .lt("visit_time", range.to.plusDays(1).atStartOfDay())))
            .like(StringUtils.hasText(name), "patient_name", name)
            .eq(StringUtils.hasText(doctorId), "doctor_user_id", doctorId)
            .eq(StringUtils.hasText(idCard), "id_card_encrypted", StringUtils.hasText(idCard) ? aesUtils.encrypt(idCard) : null)
            .orderByDesc("visit_time")
            .orderByDesc("id_medrecdoc");
        Page<AiEmrQcRecord> result = recordMapper.selectPage(new Page<AiEmrQcRecord>(request.getCurrent(), request.getSize()), query);
        List<PhisEmrRecord> permitted = new ArrayList<PhisEmrRecord>();
        List<EmrQcRecordVO> views = new ArrayList<EmrQcRecordVO>();
        for (AiEmrQcRecord entity : result.getRecords()) {
            PhisEmrRecord record = snapshotRecord(entity);
            boolean incomplete = !Integer.valueOf(1).equals(entity.getSnapshotComplete());
            if (!incomplete) permitted.add(record);
            EmrQcRecordVO view = toRecordVO(record, entity);
            view.setSnapshot(true);
            view.setSnapshotIncomplete(incomplete);
            if (incomplete) {
                view.setPatientName(entity.getPatientNameMasked());
                view.setIdCardMasked(entity.getIdCardMasked());
            }
            view.setAge(entity.getPatientAge());
            views.add(view);
        }
        recordCache.putAll(user.getAuthSessionId(), permitted);
        return new PageResponse<EmrQcRecordVO>(request.getCurrent(), request.getSize(), result.getTotal(), views);
    }

    private PhisEmrRecord snapshotRecord(AiEmrQcRecord entity) {
        PhisEmrRecord record = new PhisEmrRecord();
        record.setIdMedrecdoc(entity.getIdMedrecdoc());
        record.setIdTep(entity.getIdTep());
        record.setTemplateName(entity.getTemplateName());
        record.setPatientName(entity.getPatientName());
        record.setGenderCode(entity.getGenderCode());
        record.setIdCard(aesUtils.decrypt(entity.getIdCardEncrypted()));
        record.setIdArchives(aesUtils.decrypt(entity.getArchivesEncrypted()));
        record.setDoctorName(entity.getDoctorName());
        record.setDoctorUserId(entity.getDoctorUserId());
        record.setDeptName(entity.getDeptName());
        record.setHisOrgId(entity.getHisOrgId());
        record.setVisitTime(entity.getVisitTime());
        record.setCreateTime(entity.getPhisCreateTime());
        record.setUpdateTime(entity.getPhisUpdateTime());
        record.setSnapshot(true);
        return record;
    }

    public EmrQcDetailVO detail(AdminCurrentUser currentUser, String idMedrecdoc, String requestId) {
        ensureEnabled();
        schemaService.requireReady();
        EmrQcContext context = context(currentUser, requestId);
        PhisEmrRecord record = requireRecord(context, idMedrecdoc);
        LoadedDetail loaded = loadDetail(context, record, true);
        AiEmrQcRecord projection = writer.findRecord(context.getIdOrg(), idMedrecdoc);

        EmrQcDetailVO vo = new EmrQcDetailVO();
        vo.setRecord(toRecordVO(record, projection));
        vo.setContentHash(loaded.contentHash);
        vo.setHtmlPreview(loaded.htmlPreview);
        vo.setSections(toSectionVOs(loaded.sections));
        if (projection != null) {
            StaleState stale = staleState(projection, record, loaded.contentHash);
            List<AiEmrQcFinding> findings = writer.findFindings(projection.getLatestAnalysisId());
            vo.setQc(toAnalysisVO(projection, findings, stale.stale, stale.reason, null));
        }
        auditService.record(context, "view_detail", idMedrecdoc, null, "success");
        return vo;
    }

    public EmrQcAnalysisVO analyze(AdminCurrentUser currentUser,
                                   String idMedrecdoc,
                                   boolean force,
                                   String requestId) {
        ensureEnabled();
        schemaService.requireReady();
        EmrQcContext context = context(currentUser, requestId);
        return analyzeInternal(context, idMedrecdoc, force, "analyze");
    }

    public EmrQcAnalysisVO analyzeForTask(EmrQcContext context, PhisEmrRecord record, boolean force) {
        ensureEnabled();
        schemaService.requireReady();
        return analyzeInternal(context, record.getIdMedrecdoc(), force, "batch_analyze", record);
    }

    public EmrQcAnalysisVO review(AdminCurrentUser currentUser,
                                  String idMedrecdoc,
                                  EmrQcReviewRequest request,
                                  String requestId) {
        ensureEnabled();
        schemaService.requireReady();
        EmrQcContext context = context(currentUser, requestId);
        PhisEmrRecord record = requireRecord(context, idMedrecdoc);
        String lockKey = context.getIdOrg() + '\u0000' + idMedrecdoc.trim();
        ReentrantLock lock = locks.computeIfAbsent(lockKey, key -> new ReentrantLock());
        lock.lock();
        try {
            AiEmrQcRecord projection = writer.findRecord(context.getIdOrg(), idMedrecdoc.trim());
            if (projection == null) {
                throw new BusinessException("EMR-QC-400", "请先完成 AI 质控，再进行人工调整");
            }
            if (request == null || !StringUtils.hasText(request.getReason())) {
                throw new BusinessException("EMR-QC-400", "调整理由不能为空");
            }
            if (!StringUtils.hasText(request.getBaseAnalysisId())
                || !request.getBaseAnalysisId().equals(projection.getLatestAnalysisId())
                || !EmrQcRubric.RULE_VERSION.equals(projection.getRuleVersion())) {
                throw new ConflictException("EMR-QC-CONFLICT", "质控结果已变化或评分规则已更新，请重新质控或刷新后调整");
            }
            LoadedDetail current = loadDetail(context, record, false);
            if (!StringUtils.hasText(projection.getContentHash()) || staleState(projection, record, current.contentHash).stale
                || (current.updateTime != null && !sameUpdateTime(projection.getPhisUpdateTime(), current.updateTime))) {
                throw new ConflictException("EMR-QC-CONFLICT", "病历已更新，请重新质控后调整");
            }
            String reason = validateText(request.getReason(), 500, "调整理由");
            List<EmrQcAnalysisVO.Dimension> dimensions = validateReviewDimensions(request.getDimensions());
            List<EmrQcAnalysisVO.Finding> findings = validateReviewFindings(request.getFindings());
            List<Integer> suppliedScores = new ArrayList<Integer>();
            for (EmrQcAnalysisVO.Dimension dimension : dimensions) suppliedScores.add(dimension.getScore());
            EmrQcRubric.scoreFromFindings(dimensions, findings);
            int score = 0;
            for (int i = 0; i < dimensions.size(); i++) {
                EmrQcAnalysisVO.Dimension dimension = dimensions.get(i);
                if (!dimension.getScore().equals(suppliedScores.get(i))) {
                    throw new BusinessException("EMR-QC-400", "维度得分与有效问题扣分不一致，请检查后保存");
                }
                score += dimension.getScore();
            }
            String analysisId = UUID.randomUUID().toString().replace("-", "");
            AiEmrQcRecord saved = writer.saveReview(projection, analysisId, score, writeDimensions(dimensions),
                findings, context.getOperatorId(), context.getOperatorName(), reason);
            List<AiEmrQcFinding> savedFindings = writer.findFindings(analysisId);
            auditService.record(context, "manual_review", idMedrecdoc, null, "success");
            return toAnalysisVO(saved, savedFindings, false, null, null);
        } catch (RuntimeException ex) {
            auditService.record(context, "manual_review", idMedrecdoc, null, "failed");
            throw ex;
        } finally {
            lock.unlock();
        }
    }

    private List<EmrQcAnalysisVO.Dimension> validateReviewDimensions(List<EmrQcAnalysisVO.Dimension> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessException("EMR-QC-400", "维度评分不能为空");
        }
        Map<String, EmrQcAnalysisVO.Dimension> dimensions = new LinkedHashMap<String, EmrQcAnalysisVO.Dimension>();
        for (EmrQcAnalysisVO.Dimension input : inputs) {
            String code = input == null ? null : input.getCode();
            EmrQcRubric.Spec spec = code == null ? null : EmrQcRubric.spec(code.trim());
            if (spec == null) {
                throw new BusinessException("EMR-QC-400", "存在未知评分维度，请刷新后重试");
            }
            if (dimensions.containsKey(spec.getCode())) {
                throw new BusinessException("EMR-QC-400", "评分维度「" + spec.getName() + "」重复");
            }
            Integer score = input.getScore();
            if (score == null || score < 0 || score > spec.getWeight()) {
                throw new BusinessException("EMR-QC-400",
                    "「" + spec.getName() + "」得分必须为 0 至 " + spec.getWeight() + " 的整数");
            }
            String summary = input.getSummary();
            if (summary != null && summary.length() > 200) {
                summary = summary.substring(0, 200);
            }
            dimensions.put(spec.getCode(), EmrQcRubric.dimension(spec.getCode(), score, summary));
        }
        List<EmrQcAnalysisVO.Dimension> ordered = new ArrayList<EmrQcAnalysisVO.Dimension>();
        for (EmrQcRubric.Spec spec : EmrQcRubric.dimensions()) {
            EmrQcAnalysisVO.Dimension dimension = dimensions.get(spec.getCode());
            if (dimension == null) {
                throw new BusinessException("EMR-QC-400", "缺少评分维度「" + spec.getName() + "」");
            }
            ordered.add(dimension);
        }
        return ordered;
    }

    private List<EmrQcAnalysisVO.Finding> validateReviewFindings(List<EmrQcAnalysisVO.Finding> inputs) {
        List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
        if (inputs == null) {
            return findings;
        }
        if (inputs.size() > 100) {
            throw new BusinessException("EMR-QC-400", "问题明细最多 100 条");
        }
        for (EmrQcAnalysisVO.Finding input : inputs) {
            if (input == null || !StringUtils.hasText(input.getIssue())) {
                throw new BusinessException("EMR-QC-400", "问题说明不能为空");
            }
            String severity = input.getSeverity();
            if (!"high".equals(severity) && !"medium".equals(severity)
                && !"low".equals(severity) && !"info".equals(severity)) {
                throw new BusinessException("EMR-QC-400", "问题严重程度只支持 high、medium、low、info");
            }
            String category = StringUtils.hasText(input.getCategory())
                ? validateText(input.getCategory(), 64, "问题分类") : EmrQcRubric.name(EmrQcRubric.OTHER);
            EmrQcRubric.Spec categorySpec = EmrQcRubric.specByCategory(category);
            if (categorySpec == null) {
                throw new BusinessException("EMR-QC-400", "问题分类必须对应评分维度");
            }
            Integer deduction = input.getDeduction() == null ? 0 : input.getDeduction();
            if (deduction < 0 || deduction > categorySpec.getWeight()) {
                throw new BusinessException("EMR-QC-400",
                    "「" + categorySpec.getName() + "」问题扣分必须为 0 至 " + categorySpec.getWeight() + " 的整数");
            }
            EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
            finding.setSeverity(severity);
            finding.setCategory(categorySpec.getName());
            finding.setRuleCode(validateText(input.getRuleCode(), 64, "规则编码"));
            finding.setSectionName(validateText(input.getSectionName(), 128, "章节名"));
            finding.setExcerpt(validateText(input.getExcerpt(), 500, "原文摘录"));
            finding.setIssue(validateText(input.getIssue(), 1000, "问题说明"));
            finding.setSuggestion(validateText(input.getSuggestion(), 1000, "修改建议"));
            finding.setDeduction(deduction);
            finding.setIgnored(Boolean.TRUE.equals(input.getIgnored()));
            findings.add(finding);
        }
        return findings;
    }

    public EmrQcContext context(AdminCurrentUser currentUser, String requestId) {
        PhisSessionScope.Scope scope = sessionScope.require(currentUser);
        String idOrg = StringUtils.hasText(currentUser.getIdOrg())
            ? currentUser.getIdOrg() : currentUser.getBbpOrgId();
        if (!StringUtils.hasText(idOrg)) {
            throw new BusinessException("EMR-QC-400", "当前登录账号缺少机构信息");
        }
        return new EmrQcContext(currentUser, scope, idOrg, requestId);
    }

    public PhisEmrRecord requireRecord(EmrQcContext context, String idMedrecdoc) {
        if (!StringUtils.hasText(idMedrecdoc)) {
            throw new BusinessException("EMR-QC-400", "病历 ID 不能为空");
        }
        PhisEmrRecord record = recordCache.get(context.getSessionId(), idMedrecdoc.trim());
        if (record == null) {
            throw new NotFoundException("EMR-RECORD-NOT-FOUND", "病历不存在或不在当前查询范围内");
        }
        return record;
    }

    private EmrQcAnalysisVO analyzeInternal(EmrQcContext context,
                                            String idMedrecdoc,
                                            boolean force,
                                            String action) {
        return analyzeInternal(context, idMedrecdoc, force, action, requireRecord(context, idMedrecdoc));
    }

    private EmrQcAnalysisVO analyzeInternal(EmrQcContext context,
                                            String idMedrecdoc,
                                            boolean force,
                                            String action,
                                            PhisEmrRecord record) {
        String lockKey = context.getIdOrg() + '\u0000' + idMedrecdoc.trim();
        ReentrantLock lock = locks.computeIfAbsent(lockKey, key -> new ReentrantLock());
        lock.lock();
        try {
            LoadedDetail loaded = loadDetail(context, record, false);
            AiEmrQcRecord projection = writer.findRecord(context.getIdOrg(), idMedrecdoc.trim());
            if (!force && projection != null && canReuse(projection, record, loaded.contentHash)) {
                List<AiEmrQcFinding> findings = writer.findFindings(projection.getLatestAnalysisId());
                auditService.record(context, action, idMedrecdoc, null, "reused");
                return toAnalysisVO(projection, findings, false, null, Boolean.TRUE);
            }

            String analysisId = UUID.randomUUID().toString().replace("-", "");
            LocalDateTime analyzedAt = LocalDateTime.now();
            ResolvedAiConfig aiConfig = resolveAiConfig(context.getIdOrg());
            EmrQcAiClient.Result aiResult = aiClient.analyze(aiConfig, aiInput(record, loaded));

            List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (EmrQcAnalysisVO.Finding finding : aiResult.getFindings()) {
                String key = finding.getCategory() + "\u0000" + finding.getIssue().trim();
                if (seen.add(key)) findings.add(finding);
            }
            EmrQcRubric.scoreFromFindings(aiResult.getDimensions(), findings);
            int calculatedScore = 0;
            for (EmrQcAnalysisVO.Dimension dimension : aiResult.getDimensions()) {
                dimension.setSummary(null);
                calculatedScore += dimension.getScore();
            }

            EmrQcDraft draft = new EmrQcDraft();
            draft.setIdOrg(context.getIdOrg());
            draft.setHisOrgId(record.getHisOrgId());
            draft.setIdMedrecdoc(idMedrecdoc.trim());
            draft.setIdTep(record.getIdTep());
            draft.setTemplateName(loaded.templateName);
            draft.setPatientNameMasked(masker.maskName(record.getPatientName()));
            draft.setPatientName(record.getPatientName());
            draft.setGenderCode(record.getGenderCode());
            draft.setPatientAge(age(record.getIdCard(), record.getVisitTime()));
            draft.setIdCardEncrypted(aesUtils.encrypt(StringUtils.hasText(record.getIdCard())
                ? record.getIdCard().trim().toUpperCase(java.util.Locale.ROOT) : null));
            draft.setArchivesEncrypted(aesUtils.encrypt(record.getIdArchives()));
            draft.setDoctorUserId(record.getDoctorUserId());
            draft.setPhisCreateTime(record.getCreateTime());
            draft.setIdCardMasked(masker.maskIdCard(record.getIdCard()));
            draft.setDoctorName(record.getDoctorName());
            draft.setDeptName(record.getDeptName());
            draft.setVisitTime(loaded.visitTime);
            draft.setPhisUpdateTime(record.isSnapshot() ? loaded.updateTime : record.getUpdateTime());
            draft.setContentHash(loaded.contentHash);
            draft.setAnalysisId(analysisId);
            draft.setScore(calculatedScore);
            draft.setDimensionsJson(writeDimensions(aiResult.getDimensions()));
            draft.setFindings(findings);
            draft.setModel(aiConfig.getModel());
            draft.setRuleVersion(EmrQcRubric.RULE_VERSION);
            draft.setAnalyzedAt(analyzedAt);

            AiEmrQcRecord saved = writer.save(draft);
            List<AiEmrQcFinding> savedFindings = writer.findFindings(analysisId);
            auditService.record(context, action, idMedrecdoc, null, "success");
            return toAnalysisVO(saved, savedFindings, false, null, Boolean.FALSE);
        } catch (RuntimeException ex) {
            auditService.record(context, action, idMedrecdoc, null, "failed");
            throw ex;
        } finally {
            lock.unlock();
        }
    }

    private String aiInput(PhisEmrRecord record, LoadedDetail loaded) {
        StringBuilder input = new StringBuilder("【就诊上下文（来源于 PHIS 列表，仅提供可用信息）】\n");
        input.append("姓名：").append(StringUtils.hasText(record.getPatientName()) ? "已提供（已脱敏）" : "未提供，无法判断是否缺失").append('\n');
        input.append("身份证号：").append(StringUtils.hasText(record.getIdCard()) ? "已提供（已脱敏）" : "未提供，无法判断是否缺失").append('\n');
        input.append("病案号：").append(StringUtils.hasText(record.getIdArchives()) ? "已提供（已脱敏）" : "未提供，无法判断是否缺失").append('\n');
        String gender = gender(record.getGenderCode());
        String patientAge = age(record.getIdCard(), loaded.visitTime);
        if ("男".equals(gender) || "女".equals(gender)) input.append("性别：").append(gender).append('\n');
        if (StringUtils.hasText(patientAge)) input.append("年龄：").append(patientAge).append('\n');
        if (loaded.visitTime != null) input.append("就诊时间：").append(loaded.visitTime).append('\n');
        if (StringUtils.hasText(record.getDeptName())) input.append("科室：").append(masker.maskText(record.getDeptName(), record.getPatientName(), record.getIdCard(), record.getIdArchives())).append('\n');
        if (StringUtils.hasText(loaded.templateName)) input.append("病历模板：").append(masker.maskText(loaded.templateName, record.getPatientName(), record.getIdCard(), record.getIdArchives())).append('\n');
        return input.append("【病历章节】\n").append(loaded.maskedText).toString();
    }

    private boolean canReuse(AiEmrQcRecord projection, PhisEmrRecord record, String contentHash) {
        return projection != null
            && !record.isSnapshot()
            && StringUtils.hasText(projection.getContentHash())
            && projection.getContentHash().equals(contentHash)
            && EmrQcRubric.RULE_VERSION.equals(projection.getRuleVersion())
            && sameUpdateTime(projection.getPhisUpdateTime(), record.getUpdateTime());
    }

    private ResolvedAiConfig resolveAiConfig(String idOrg) {
        String regionId = null;
        if (StringUtils.hasText(idOrg)) {
            com.regionalai.floatingball.server.modules.org.entity.AiOrg org = orgMapper.selectById(idOrg);
            if (org != null) {
                regionId = org.getIdRegion();
            }
        }
        ResolvedAiConfig config = configService.resolveByScope(idOrg, regionId);
        if (config == null || !StringUtils.hasText(config.getBaseUrl()) || !StringUtils.hasText(config.getModel())) {
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "未找到可用的 AI 质控配置，请联系管理员");
        }
        return config;
    }

    private LoadedDetail loadDetail(EmrQcContext context, PhisEmrRecord record, boolean includePreview) {
        JsonNode data = phisClient.detail(context.getScope().getSession().getCookies(), record.getIdMedrecdoc());
        String xmlContent = data.path("xmlContent").isTextual()
            ? data.path("xmlContent").asText() : data.path("xmlContent").toString();
        List<PhisEmrSection> sections = normalizer.toSections(xmlContent);
        List<PhisEmrSection> maskedSections = masker.maskSections(
            sections, record.getPatientName(), record.getIdCard(), record.getIdArchives());

        LoadedDetail loaded = new LoadedDetail();
        loaded.updateTime = normalizer.parseDateTime(data.path("updateTime").asText(null));
        loaded.sections = sections;
        String preview = EmrQcHtmlPreview.render(data.path("htmlContent").asText(""), record, sections, xmlContent);
        if (includePreview) loaded.htmlPreview = preview;
        String previewText = EmrQcHtmlPreview.plainTextForAi(preview, record, masker);
        loaded.maskedText = normalizer.canonicalText(maskedSections);
        String hashText = normalizer.canonicalText(sections);
        if (StringUtils.hasText(previewText)) {
            loaded.maskedText += "\n【PHIS 病历预览纯文字】\n" + previewText;
            hashText += "\n【PHIS 病历预览纯文字】\n" + previewText;
        }
        loaded.contentHash = normalizer.sha256(hashText);
        loaded.templateName = firstText(data.path("tepName").asText(null), record.getTemplateName());
        loaded.visitTime = normalizer.parseDateTime(data.path("titleTime").asText(null));
        if (loaded.visitTime == null) {
            loaded.visitTime = record.getVisitTime();
        }
        return loaded;
    }

    private void resolveDoctorNames(AdminCurrentUser user, List<PhisEmrRecord> records) {
        if (records.isEmpty() || !StringUtils.hasText(user.getBbpOrgId())) return;
        Map<String, String> names = new HashMap<String, String>();
        Set<String> ambiguous = new HashSet<String>();
        Map<String, String> userIdsByName = new HashMap<String, String>();
        Set<String> ambiguousNames = new HashSet<String>();
        Map<String, String> userIdsByKey = new HashMap<String, String>();
        Set<String> ambiguousKeys = new HashSet<String>();
        try {
            for (BbpPersonView person : directoryService.persons(user, user.getBbpOrgId())) {
                if (person == null || !StringUtils.hasText(person.getName())) continue;
                if (StringUtils.hasText(person.getUserId())) {
                    String name = person.getName().trim();
                    String id = person.getUserId().trim();
                    String previous = userIdsByName.putIfAbsent(name, id);
                    if (previous != null && !previous.equals(id)) ambiguousNames.add(name);
                }
                for (String key : java.util.Arrays.asList(person.getUserId(), person.getPersonId(), person.getId(),
                        person.getLoginName(), person.getCode())) {
                    if (!StringUtils.hasText(key)) continue;
                    key = key.trim();
                    String name = person.getName().trim();
                    String old = names.putIfAbsent(key, name);
                    if (old != null && !old.equals(name)) ambiguous.add(key);
                    if (StringUtils.hasText(person.getUserId())) {
                        String id = person.getUserId().trim();
                        String previous = userIdsByKey.putIfAbsent(key, id);
                        if (previous != null && !previous.equals(id)) ambiguousKeys.add(key);
                    }
                }
            }
        } catch (RuntimeException ex) {
            return;
        }
        for (PhisEmrRecord record : records) {
            String key = text(record.getDoctorName(), null);
            if (key != null && !ambiguous.contains(key) && names.containsKey(key)) {
                record.setDoctorName(names.get(key));
            }
            if (key != null && !ambiguousKeys.contains(key) && userIdsByKey.containsKey(key)) {
                record.setDoctorUserId(userIdsByKey.get(key));
            }
            String doctorName = text(record.getDoctorName(), null);
            if (!StringUtils.hasText(record.getDoctorUserId()) || record.getDoctorUserId().equals(doctorName)) {
                if (StringUtils.hasText(doctorName) && !ambiguousNames.contains(doctorName) && userIdsByName.containsKey(doctorName)) {
                    record.setDoctorUserId(userIdsByName.get(doctorName));
                }
            }
        }
    }

    private Map<String, AiEmrQcRecord> findProjections(String idOrg, List<String> ids) {
        if (!StringUtils.hasText(idOrg) || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<AiEmrQcRecord> records = recordMapper.selectList(new QueryWrapper<AiEmrQcRecord>()
            .eq("id_org", idOrg)
            .in("id_medrecdoc", ids));
        Map<String, AiEmrQcRecord> result = new LinkedHashMap<String, AiEmrQcRecord>();
        for (AiEmrQcRecord record : records) {
            result.put(record.getIdMedrecdoc(), record);
        }
        return result;
    }

    private List<String> ids(List<PhisEmrRecord> records) {
        List<String> ids = new ArrayList<String>();
        for (PhisEmrRecord record : records) {
            if (StringUtils.hasText(record.getIdMedrecdoc())) {
                ids.add(record.getIdMedrecdoc());
            }
        }
        return ids;
    }

    private EmrQcRecordVO toRecordVO(PhisEmrRecord record, AiEmrQcRecord projection) {
        EmrQcRecordVO vo = new EmrQcRecordVO();
        vo.setIdMedrecdoc(record.getIdMedrecdoc());
        vo.setTemplateName(record.getTemplateName());
        vo.setPatientName(record.getPatientName());
        vo.setGender(gender(record.getGenderCode()));
        vo.setAge(age(record.getIdCard(), record.getVisitTime()));
        vo.setIdCardMasked(masker.maskIdCard(record.getIdCard()));
        vo.setDoctorName(record.getDoctorName());
        vo.setDeptName(record.getDeptName());
        vo.setVisitTime(record.getVisitTime());
        vo.setUpdateTime(record.getUpdateTime());
        vo.setSnapshot(record.isSnapshot());
        if (projection == null) {
            vo.setQcStatus("pending");
            vo.setStale(false);
            return vo;
        }
        boolean sameUpdate = sameUpdateTime(projection.getPhisUpdateTime(), record.getUpdateTime());
        vo.setQcStatus(sameUpdate ? "analyzed" : "stale");
        vo.setStale(!sameUpdate);
        vo.setScore(projection.getScore());
        vo.setFindingCount(projection.getFindingCount());
        vo.setHighSeverityCount(projection.getHighSeverityCount());
        vo.setAnalyzedAt(projection.getAnalyzedAt());
        vo.setResultSource(StringUtils.hasText(projection.getResultSource()) ? projection.getResultSource() : "ai");
        return vo;
    }

    private StaleState staleState(AiEmrQcRecord projection, PhisEmrRecord record, String contentHash) {
        StaleState state = new StaleState();
        if (StringUtils.hasText(projection.getContentHash()) && !projection.getContentHash().equals(contentHash)) {
            state.stale = true;
            state.reason = "content_changed";
        } else if (record.isSnapshot()) {
            state.stale = true;
            state.reason = "snapshot_unverified";
        } else if (!sameUpdateTime(projection.getPhisUpdateTime(), record.getUpdateTime())) {
            state.stale = true;
            state.reason = "record_updated";
        }
        return state;
    }

    private boolean sameUpdateTime(LocalDateTime saved, LocalDateTime current) {
        if (saved == null || current == null) {
            return saved == null && current == null;
        }
        return saved.equals(current);
    }

    private EmrQcAnalysisVO toAnalysisVO(AiEmrQcRecord projection,
                                         List<AiEmrQcFinding> findings,
                                         boolean stale,
                                         String staleReason,
                                         Boolean reused) {
        EmrQcAnalysisVO vo = new EmrQcAnalysisVO();
        vo.setAnalysisId(projection.getLatestAnalysisId());
        vo.setAnalyzedAt(projection.getAnalyzedAt());
        vo.setScore(projection.getScore());
        vo.setDimensions(parseDimensions(projection.getDimensionsJson()));
        for (AiEmrQcFinding entity : findings) {
            EmrQcAnalysisVO.Finding finding = new EmrQcAnalysisVO.Finding();
            finding.setSeverity(entity.getSeverity());
            finding.setCategory(entity.getCategory());
            finding.setRuleCode(entity.getRuleCode());
            finding.setSectionName(entity.getSectionName());
            finding.setExcerpt(entity.getExcerpt());
            finding.setIssue(entity.getIssue());
            finding.setSuggestion(entity.getSuggestion());
            finding.setDeduction(entity.getDeduction() == null ? 0 : entity.getDeduction());
            finding.setIgnored(Boolean.TRUE.equals(entity.getIgnored()));
            vo.getFindings().add(finding);
        }
        vo.setFindingCount(projection.getFindingCount());
        vo.setHighSeverityCount(projection.getHighSeverityCount());
        vo.setModel(projection.getModel());
        vo.setRuleVersion(projection.getRuleVersion());
        vo.setResultSource(StringUtils.hasText(projection.getResultSource()) ? projection.getResultSource() : "ai");
        vo.setAiScore(projection.getAiScore());
        vo.setReviewedByName(projection.getReviewedByName());
        vo.setReviewedAt(projection.getReviewedAt());
        vo.setReviewReason(projection.getReviewReason());
        vo.setStale(stale);
        vo.setStaleReason(staleReason);
        vo.setReused(reused);
        return vo;
    }

    private List<EmrQcSectionVO> toSectionVOs(List<PhisEmrSection> sections) {
        List<EmrQcSectionVO> result = new ArrayList<EmrQcSectionVO>();
        for (PhisEmrSection section : sections) {
            EmrQcSectionVO vo = new EmrQcSectionVO();
            vo.setName(section.getName());
            for (PhisEmrSection.Field field : section.getFields()) {
                EmrQcSectionVO.Field fieldVO = new EmrQcSectionVO.Field();
                fieldVO.setName(field.getName());
                fieldVO.setValue(field.getValue());
                vo.getFields().add(fieldVO);
            }
            result.add(vo);
        }
        return result;
    }

    private List<EmrQcAnalysisVO.Dimension> parseDimensions(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<EmrQcAnalysisVO.Dimension>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EmrQcAnalysisVO.Dimension>>() {});
        } catch (Exception ex) {
            return new ArrayList<EmrQcAnalysisVO.Dimension>();
        }
    }

    private String writeDimensions(List<EmrQcAnalysisVO.Dimension> dimensions) {
        try {
            return objectMapper.writeValueAsString(dimensions);
        } catch (Exception ex) {
            throw new ServiceUnavailableException("EMR-QC-UNAVAILABLE", "AI 质控结果保存失败，请稍后重试");
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("EMR-QC-DISABLED", "门诊病历质控功能未启用，请联系管理员");
        }
    }

    private DateRange resolveRange(String dateFrom, String dateTo) {
        boolean hasFrom = StringUtils.hasText(dateFrom);
        boolean hasTo = StringUtils.hasText(dateTo);
        if (!hasFrom && !hasTo) {
            LocalDate today = LocalDate.now();
            return new DateRange(today.minusDays(6), today);
        }
        if (!hasFrom || !hasTo) {
            throw new BusinessException("EMR-QC-400", "起始日期与截止日期必须成对填写");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(dateFrom.trim());
            to = LocalDate.parse(dateTo.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException("EMR-QC-400", "日期格式必须为 yyyy-MM-dd");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("EMR-QC-400", "起始日期不能晚于截止日期");
        }
        if (to.isAfter(from.plusYears(1))) {
            throw new BusinessException("EMR-QC-400", "查询日期跨度不能超过 1 年");
        }
        return new DateRange(from, to);
    }

    private String normalizeQcStatus(String qcStatus) {
        if (!StringUtils.hasText(qcStatus)) {
            return "all";
        }
        String normalized = qcStatus.trim().toLowerCase();
        if (!QC_STATUS.contains(normalized)) {
            throw new BusinessException("EMR-QC-400", "质控状态只支持 all、analyzed");
        }
        return normalized;
    }

    private String validateText(String value, int maxLength, String fieldName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BusinessException("EMR-QC-400", fieldName + "长度不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    private String validateIdCard(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            return null;
        }
        String trimmed = idCard.trim();
        if (!ID_CARD.matcher(trimmed).matches()) {
            throw new BusinessException("EMR-QC-400", "身份证号必须为 18 位");
        }
        return trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private String gender(String code) {
        if ("1".equals(code)) {
            return "男";
        }
        if ("2".equals(code)) {
            return "女";
        }
        return StringUtils.hasText(code) ? code.trim() : "";
    }

    private String age(String idCard, LocalDateTime visitTime) {
        if (!StringUtils.hasText(idCard) || idCard.trim().length() < 14) {
            return "";
        }
        try {
            String birth = idCard.trim().substring(6, 14);
            LocalDate birthday = LocalDate.of(
                Integer.parseInt(birth.substring(0, 4)),
                Integer.parseInt(birth.substring(4, 6)),
                Integer.parseInt(birth.substring(6, 8)));
            LocalDate reference = visitTime == null ? LocalDate.now() : visitTime.toLocalDate();
            if (birthday.isAfter(reference)) {
                return "";
            }
            int years = Period.between(birthday, reference).getYears();
            return years >= 0 && years <= 150 ? String.valueOf(years) : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static final class DateRange {
        private final LocalDate from;
        private final LocalDate to;

        private DateRange(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
        }
    }

    private static final class StaleState {
        private boolean stale;
        private String reason;
    }

    public static final class LoadedDetail {
        private LocalDateTime updateTime;
        private List<PhisEmrSection> sections;
        private String htmlPreview = "";
        private String maskedText;
        private String contentHash;
        private String templateName;
        private LocalDateTime visitTime;

        public List<PhisEmrSection> getSections() {
            return sections;
        }

        public String getContentHash() {
            return contentHash;
        }
    }
}
