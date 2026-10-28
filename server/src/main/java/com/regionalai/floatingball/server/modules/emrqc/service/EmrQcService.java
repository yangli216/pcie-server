package com.regionalai.floatingball.server.modules.emrqc.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.NotFoundException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final int MAX_UPSTREAM_PAGES = 20;
    private static final Set<String> QC_STATUS = new HashSet<String>(
        java.util.Arrays.asList("all", "pending", "analyzed", "stale"));

    private final EmrQcProperties properties;
    private final EmrQcSchemaService schemaService;
    private final PhisSessionScope sessionScope;
    private final PhisRecordCache recordCache;
    private final PhisEmrClient phisClient;
    private final PhisContentNormalizer normalizer;
    private final SensitiveDataMasker masker;
    private final EmrQcRuleEngine ruleEngine;
    private final EmrQcAiClient aiClient;
    private final EmrQcWriter writer;
    private final EmrQcAuditService auditService;
    private final ConfigService configService;
    private final AiEmrQcRecordMapper recordMapper;
    private final com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper orgMapper;
    private final ObjectMapper objectMapper;
    private final BbpDirectoryService directoryService;

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<String, ReentrantLock>();

    public EmrQcService(EmrQcProperties properties,
                        EmrQcSchemaService schemaService,
                        PhisSessionScope sessionScope,
                        PhisRecordCache recordCache,
                        PhisEmrClient phisClient,
                        PhisContentNormalizer normalizer,
                        SensitiveDataMasker masker,
                        EmrQcRuleEngine ruleEngine,
                        EmrQcAiClient aiClient,
                        EmrQcWriter writer,
                        EmrQcAuditService auditService,
                        ConfigService configService,
                        AiEmrQcRecordMapper recordMapper,
                        com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper orgMapper,
                        ObjectMapper objectMapper,
                        BbpDirectoryService directoryService) {
        this.properties = properties;
        this.schemaService = schemaService;
        this.sessionScope = sessionScope;
        this.recordCache = recordCache;
        this.phisClient = phisClient;
        this.normalizer = normalizer;
        this.masker = masker;
        this.ruleEngine = ruleEngine;
        this.aiClient = aiClient;
        this.writer = writer;
        this.auditService = auditService;
        this.configService = configService;
        this.recordMapper = recordMapper;
        this.orgMapper = orgMapper;
        this.objectMapper = objectMapper;
        this.directoryService = directoryService;
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
        sessionScope.require(currentUser);
        String bbpOrgId = currentUser.getBbpOrgId();
        if (!StringUtils.hasText(bbpOrgId)) {
            return Collections.emptyList();
        }
        List<EmrQcDoctorVO> result = new ArrayList<EmrQcDoctorVO>();
        for (BbpPersonView person : directoryService.persons(currentUser, bbpOrgId)) {
            if (!isActiveDoctor(person)) {
                continue;
            }
            EmrQcDoctorVO vo = new EmrQcDoctorVO();
            vo.setUserId(person.getUserId());
            vo.setName(person.getName());
            vo.setDeptId(person.getDepartmentId());
            vo.setDeptName(person.getDepartmentName());
            result.add(vo);
        }
        result.sort(Comparator.comparing(item -> text(item.getName(), item.getUserId())));
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
        for (BbpPersonView person : directoryService.persons(currentUser, bbpOrgId)) {
            if (doctorUserId.equals(person.getUserId()) && isActiveDoctor(person)) {
                return text(person.getLoginName(), text(person.getCode(), person.getUserId()));
            }
        }
        throw new BusinessException("EMR-QC-400", "开立医生不属于当前登录机构");
    }

    private boolean isActiveDoctor(BbpPersonView person) {
        if (person == null || !Boolean.TRUE.equals(person.getActive())
            || !StringUtils.hasText(person.getUserId())) {
            return false;
        }
        String personType = person.getPersonType();
        if ("1".equals(personType) || "9".equals(personType)) {
            return true;
        }
        String personTypeText = person.getPersonTypeText();
        return StringUtils.hasText(personTypeText) && personTypeText.contains("医师");
    }

    private String requireOrgDepartment(AdminCurrentUser currentUser, String department) {
        if (department == null) return null;
        String orgId = currentUser.getBbpOrgId();
        if (!StringUtils.hasText(orgId)) {
            throw new BusinessException("EMR-QC-400", "当前登录账号缺少机构信息，无法按科室筛选");
        }
        for (BbpPersonView person : directoryService.persons(currentUser, orgId)) {
            if (isActiveDoctor(person) && department.equals(text(person.getDepartmentName(), null))) return department;
        }
        throw new BusinessException("EMR-QC-400", "科室不属于当前登录机构的医生目录");
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
        String normalizedDept = requireOrgDepartment(currentUser, validateText(deptName, 128, "科室"));
        String normalizedIdCard = validateIdCard(idCard);
        String insertUser = requireOrgDoctorInsertUser(currentUser, normalizedDoctorId);

        List<PhisEmrRecord> all = fetchAll(scope, range, normalizedName, normalizedIdCard, insertUser);
        if (normalizedDept != null) {
            all.removeIf(record -> !normalizedDept.equals(text(record.getDeptName(), null)));
        }
        recordCache.putAll(currentUser.getAuthSessionId(), all);

        Map<String, AiEmrQcRecord> projections = findProjections(currentUser.getIdOrg(), ids(all));
        List<EmrQcRecordVO> filtered = new ArrayList<EmrQcRecordVO>();
        for (PhisEmrRecord record : all) {
            AiEmrQcRecord projection = projections.get(record.getIdMedrecdoc());
            EmrQcRecordVO vo = toRecordVO(record, projection);
            if ("all".equals(normalizedQcStatus) || normalizedQcStatus.equals(vo.getQcStatus())) {
                filtered.add(vo);
            }
        }
        PageRequest pageRequest = PageRequest.of(current, size);
        int from = pageRequest.fromIndex(filtered.size());
        int to = pageRequest.toIndex(filtered.size());
        List<EmrQcRecordVO> pageRecords = new ArrayList<EmrQcRecordVO>(filtered.subList(from, to));
        return new PageResponse<EmrQcRecordVO>(
            pageRequest.getCurrent(), pageRequest.getSize(), filtered.size(), pageRecords);
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

    public EmrQcAnalysisVO analyzeForTask(EmrQcContext context, String idMedrecdoc, boolean force) {
        ensureEnabled();
        schemaService.requireReady();
        return analyzeInternal(context, idMedrecdoc, force, "batch_analyze");
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
                || !EmrQcRuleEngine.RULE_VERSION.equals(projection.getRuleVersion())) {
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
        PhisEmrRecord record = requireRecord(context, idMedrecdoc);
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
            EmrQcAiClient.Result aiResult = aiClient.analyze(aiConfig, loaded.maskedText);

            List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
            for (EmrQcAnalysisVO.Finding finding : ruleEngine.evaluate(loaded.maskedSections, record.getDeptName(), loaded.templateName)) {
                findings.add(finding);
            }
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (EmrQcAnalysisVO.Finding finding : aiResult.getFindings()) {
                if (EmrQcRubric.specByCategory(finding.getCategory()) == EmrQcRubric.spec(EmrQcRubric.OTHER)) continue;
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
            draft.setIdCardMasked(masker.maskIdCard(record.getIdCard()));
            draft.setDoctorName(record.getDoctorName());
            draft.setDeptName(record.getDeptName());
            draft.setVisitTime(loaded.visitTime);
            draft.setPhisUpdateTime(record.getUpdateTime());
            draft.setContentHash(loaded.contentHash);
            draft.setAnalysisId(analysisId);
            draft.setScore(calculatedScore);
            draft.setDimensionsJson(writeDimensions(aiResult.getDimensions()));
            draft.setFindings(findings);
            draft.setModel(aiConfig.getModel());
            draft.setRuleVersion(EmrQcRuleEngine.RULE_VERSION);
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

    private boolean canReuse(AiEmrQcRecord projection, PhisEmrRecord record, String contentHash) {
        return projection != null
            && StringUtils.hasText(projection.getContentHash())
            && projection.getContentHash().equals(contentHash)
            && EmrQcRuleEngine.RULE_VERSION.equals(projection.getRuleVersion())
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
        if (includePreview) loaded.htmlPreview = EmrQcHtmlPreview.render(data.path("htmlContent").asText(""), record, sections, xmlContent);
        loaded.maskedSections = maskedSections;
        loaded.maskedText = normalizer.canonicalText(maskedSections);
        loaded.contentHash = normalizer.sha256(normalizer.canonicalText(sections));
        loaded.templateName = firstText(data.path("tepName").asText(null), record.getTemplateName());
        loaded.visitTime = normalizer.parseDateTime(data.path("titleTime").asText(null));
        if (loaded.visitTime == null) {
            loaded.visitTime = record.getVisitTime();
        }
        return loaded;
    }

    private List<PhisEmrRecord> fetchAll(PhisSessionScope.Scope scope,
                                          DateRange range,
                                          String patientName,
                                          String idCard,
                                          String insertUser) {
        List<PhisEmrRecord> all = new ArrayList<PhisEmrRecord>();
        Set<String> seen = new LinkedHashSet<String>();
        int pageNum = 1;
        int fetched = 0;
        int total = Integer.MAX_VALUE;
        while (pageNum <= MAX_UPSTREAM_PAGES && fetched < total) {
            PhisEmrClient.ListPage page = phisClient.list(scope.getSession().getCookies(), range.from, range.to,
                patientName, idCard, insertUser, scope.getHisOrgId(), pageNum, 50);
            total = page.getTotal();
            if (page.getRecords().isEmpty()) {
                if (fetched < total) {
                    throw new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 病历列表不完整，请缩小查询范围后重试");
                }
                break;
            }
            for (PhisEmrRecord record : page.getRecords()) {
                if (seen.add(record.getIdMedrecdoc())) {
                    all.add(record);
                }
            }
            fetched += page.getRecords().size();
            pageNum++;
        }
        if (pageNum > MAX_UPSTREAM_PAGES && fetched < total) {
            throw new BusinessException("EMR-QC-400", "病历数量超过单次查询上限，请缩小日期范围或增加筛选条件后重试");
        }
        return all;
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
            throw new BusinessException("EMR-QC-400", "质控状态只支持 all、pending、analyzed、stale");
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
        return trimmed;
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
        private List<PhisEmrSection> maskedSections;
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
