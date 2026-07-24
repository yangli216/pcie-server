package com.regionalai.floatingball.server.modules.chronicdisease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.TcdVisitDrugRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.entity.AiChronicDiseaseFollowUp;
import com.regionalai.floatingball.server.modules.chronicdisease.mapper.AiChronicDiseaseFollowUpMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChronicDiseaseFollowUpService {

    private static final Set<String> VISIT_STATUSES = setOf("1", "2", "3");
    private static final Set<String> VISIT_KINDS = setOf("1", "2");
    private static final Set<String> ARTERIOPALMUS_SINGLE_CODES = setOf("0", "1", "2", "3");

    private final AiChronicDiseaseFollowUpMapper followUpMapper;
    private final ChronicDiseaseFollowUpWriter followUpWriter;
    private final ObjectMapper objectMapper;

    public ChronicDiseaseFollowUpService(
        AiChronicDiseaseFollowUpMapper followUpMapper,
        ChronicDiseaseFollowUpWriter followUpWriter,
        ObjectMapper objectMapper
    ) {
        this.followUpMapper = followUpMapper;
        this.followUpWriter = followUpWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChronicDiseaseFollowUpResponse save(
        AiDevice device,
        String requestId,
        ChronicDiseaseFollowUpRequest request
    ) {
        if (device == null || !StringUtils.hasText(device.getIdOrg())) {
            throw new BusinessException("CHRONIC-AUTH-001", "设备机构上下文缺失，请重新注册设备");
        }
        String normalizedRequestId = trim(requestId);
        if (!StringUtils.hasText(normalizedRequestId) || normalizedRequestId.length() > 64) {
            throw new BusinessException("X-Request-Id 不能为空且不能超过 64 个字符");
        }

        normalize(request);
        validate(request);

        AiChronicDiseaseFollowUp existing = findByRequestId(device.getIdOrg(), normalizedRequestId);
        if (existing != null) {
            return resolveIdempotent(existing, request);
        }

        AiChronicDiseaseFollowUp entity = toEntity(device, normalizedRequestId, request);
        try {
            followUpWriter.insert(entity);
        } catch (DuplicateKeyException ex) {
            AiChronicDiseaseFollowUp concurrent = findByRequestId(device.getIdOrg(), normalizedRequestId);
            if (concurrent != null) {
                return resolveIdempotent(concurrent, request);
            }
            throw ex;
        }
        return toResponse(entity);
    }

    private void normalize(ChronicDiseaseFollowUpRequest request) {
        request.setIdPhr(trim(request.getIdPhr()));
        request.setIdRecord(trim(request.getIdRecord()));
        request.setId(trim(request.getId()));
        request.setStatus(trim(request.getStatus()));
        request.setSdVisitKind(normalizeCsv(request.getSdVisitKind()));
        request.setSdHySymptom(normalizeCsv(request.getSdHySymptom()));
        request.setSdDbsSymptom(normalizeCsv(request.getSdDbsSymptom()));
        request.setSdArteriopalmus(normalizeCsv(request.getSdArteriopalmus()));
        request.setSdComplications(normalizeCsv(request.getSdComplications()));
        request.setSdComorbidity(normalizeCsv(request.getSdComorbidity()));
        request.setSdMajorCc(normalizeCsv(request.getSdMajorCc()));
        request.setTargetOrganDamage(normalizeCsv(request.getTargetOrganDamage()));
        request.setIsGlu(defaultIfBlank(request.getIsGlu(), "1"));
        request.setDesPresAdvice(normalizeRichText(request.getDesPresAdvice()));

        boolean hypertension = visitKinds(request).contains("1");
        boolean diabetes = visitKinds(request).contains("2");
        if (!hypertension) {
            request.setDtHyPlan("");
            request.setSdHySymptom("");
            request.setFgCardiovascular("");
            request.setSdSalt("");
            request.setSdAdvSalt("");
            request.setSdMajorCc("");
            request.setTargetOrganDamage("");
        }
        if (!diabetes) {
            request.setDtDbsPlan("");
            request.setGlu("");
            request.setFbgMeal("");
            request.setSdDbsSymptom("");
            request.setSdArteriopalmus("");
            request.setLowEffects("");
            request.setRice("");
            request.setTargRice("");
            request.setDesAdr("");
            request.setSdComplications("");
            request.setDesComplications("");
        } else if ("1".equals(request.getIsGlu())) {
            request.setFbgMeal("");
        } else if ("0".equals(request.getIsGlu())) {
            request.setGlu("");
        }

        if ("0".equals(trim(request.getSdWehtherSmoke()))
            || "2".equals(trim(request.getSdWehtherSmoke()))) {
            request.setDaySmoke("");
            request.setAdvDaySmoke("");
        }
        if ("0".equals(trim(request.getSdWhetherDrink()))
            || "4".equals(trim(request.getSdWhetherDrink()))) {
            request.setDayDrink("");
            request.setAdvDayDrink("");
            request.setSdMainDrinking("");
        }
        if (!"2".equals(trim(request.getSdSideEffects()))) {
            request.setDesSideEffects("");
        }
        if (!csvValues(request.getSdComplications()).contains("0")) {
            request.setDesComplications("");
        }
        if (!csvValues(request.getSdComorbidity()).contains("0")) {
            request.setDesComorbidity("");
        }
        normalizeDrugs(request);
        request.setBmi(calculateBmi(request.getStature(), request.getAvoirdupois()));
    }

    private void normalizeDrugs(ChronicDiseaseFollowUpRequest request) {
        if (!"1".equals(trim(request.getFgDrugChange()))) {
            request.setDrugList(new ArrayList<TcdVisitDrugRequest>());
            return;
        }
        List<TcdVisitDrugRequest> drugs = request.getDrugList() == null
            ? Collections.<TcdVisitDrugRequest>emptyList()
            : request.getDrugList();
        request.setDrugList(drugs.stream()
            .filter(item -> item != null && StringUtils.hasText(item.getIdDrug()))
            .peek(item -> {
                item.setId(trim(item.getId()));
                item.setIdDrug(trim(item.getIdDrug()));
                item.setIdPherec(trim(item.getIdPherec()));
                item.setNaDrug(trim(item.getNaDrug()));
                item.setSdDrugFreq(trim(item.getSdDrugFreq()));
                item.setPerDose(trim(item.getPerDose()));
                item.setDoseUnit(trim(item.getDoseUnit()));
                item.setInsulin(trim(item.getInsulin()));
            })
            .collect(Collectors.toList()));
    }

    private void validate(ChronicDiseaseFollowUpRequest request) {
        require(request.getIdPhr(), "人员主键 idPhr");
        require(request.getIdRecord(), "登记表主键 idRecord");
        requireEnum(VISIT_STATUSES, request.getStatus(), "status");
        Set<String> kinds = visitKinds(request);
        if (kinds.isEmpty()) {
            throw new BusinessException("sdVisitKind 至少包含高血压 1 或糖尿病 2");
        }
        for (String kind : kinds) {
            requireEnum(VISIT_KINDS, kind, "sdVisitKind");
        }

        if ("3".equals(request.getStatus())) {
            require(request.getInputUser(), "录入医生 inputUser");
            require(request.getIdUser(), "责任医生 idUser");
        }

        validateNumber(request.getStature(), new BigDecimal("0"), new BigDecimal("300"), "身高 stature");
        validateNumber(request.getAvoirdupois(), BigDecimal.ZERO, new BigDecimal("1000"), "体重 avoirdupois");
        validateNumber(request.getAdvAdp(), BigDecimal.ZERO, new BigDecimal("1000"), "目标体重 advAdp");
        validateNumber(request.getWaistline(), BigDecimal.ZERO, new BigDecimal("999"), "腰围 waistline");
        validateNumber(request.getAdvWaistline(), BigDecimal.ZERO, new BigDecimal("999"), "目标腰围 advWaistline");
        BigDecimal pressureH = validateNumber(
            request.getPressureH(), BigDecimal.ZERO, new BigDecimal("300"), "收缩压 pressureH"
        );
        BigDecimal pressureL = validateNumber(
            request.getPressureL(), BigDecimal.ZERO, new BigDecimal("200"), "舒张压 pressureL"
        );
        validateNumber(request.getHeartRate(), BigDecimal.ZERO, new BigDecimal("200"), "心率 heartRate");
        if (pressureH.compareTo(pressureL) < 0) {
            throw new BusinessException("收缩压需大于或等于舒张压");
        }

        boolean hypertension = kinds.contains("1");
        boolean diabetes = kinds.contains("2");
        if (hypertension) {
            requireCsv(request.getSdHySymptom(), "高血压症状 sdHySymptom");
            require(request.getSdSalt(), "日摄盐量 sdSalt");
            require(request.getSdAdvSalt(), "日摄盐目标量 sdAdvSalt");
            validateExclusive(request.getSdHySymptom(), "1", "高血压无症状");
            validateExclusive(request.getSdMajorCc(), "0", "高血压并发症/合并症“无”");
            validateExclusive(request.getTargetOrganDamage(), "0", "靶器官损伤“无”");
        }
        if (diabetes) {
            requireEnum(setOf("0", "1"), request.getIsGlu(), "isGlu");
            String glucose = "1".equals(request.getIsGlu()) ? request.getGlu() : request.getFbgMeal();
            validateNumber(glucose, BigDecimal.ZERO, new BigDecimal("100"), "血糖");
            requireCsv(request.getSdDbsSymptom(), "糖尿病症状 sdDbsSymptom");
            require(request.getRice(), "主食 rice");
            require(request.getTargRice(), "目标主食 targRice");
            validateExclusive(request.getSdDbsSymptom(), "1", "糖尿病无症状");
            validateArteriopalmus(request.getSdArteriopalmus());
        }

        require(request.getSdPsychicAdj(), "心理调整 sdPsychicAdj");
        require(request.getSdWehtherSmoke(), "吸烟情况 sdWehtherSmoke");
        require(request.getSdWhetherDrink(), "饮酒情况 sdWhetherDrink");
        validateNumber(request.getSportWeek(), BigDecimal.ZERO, new BigDecimal("99"), "每周运动 sportWeek");
        validateNumber(request.getAdvSportWeek(), BigDecimal.ZERO, new BigDecimal("99"), "每周运动目标 advSportWeek");
        validateNumber(request.getSportMinute(), BigDecimal.ZERO, new BigDecimal("999"), "每次运动时长 sportMinute");
        validateNumber(
            request.getAdvSportMinute(), BigDecimal.ZERO, new BigDecimal("999"), "每次运动时长目标 advSportMinute"
        );

        if ("2".equals(request.getStatus()) || "3".equals(request.getStatus())) {
            requireEnum(setOf("0", "1"), request.getFgDrugChange(), "fgDrugChange");
            require(request.getSdDrugPro(), "服药依从性 sdDrugPro");
            requireEnum(setOf("1", "2"), request.getSdSideEffects(), "sdSideEffects");
            if ("2".equals(request.getSdSideEffects())) {
                require(request.getDesSideEffects(), "药物不良反应情况 desSideEffects");
            }
        }
    }

    private void validateArteriopalmus(String value) {
        List<String> values = csvValues(value);
        long singleCount = values.stream().filter(ARTERIOPALMUS_SINGLE_CODES::contains).count();
        if (singleCount > 0 && values.size() > 1) {
            throw new BusinessException("sdArteriopalmus 的单选状态不能与其它值并存");
        }
    }

    private void validateExclusive(String value, String exclusive, String label) {
        List<String> values = csvValues(value);
        if (values.contains(exclusive) && values.size() > 1) {
            throw new BusinessException(label + "不能与其它选项并存");
        }
    }

    private AiChronicDiseaseFollowUp findByRequestId(String idOrg, String requestId) {
        return followUpMapper.selectOne(new LambdaQueryWrapper<AiChronicDiseaseFollowUp>()
            .eq(AiChronicDiseaseFollowUp::getIdOrg, idOrg)
            .eq(AiChronicDiseaseFollowUp::getRequestId, requestId)
            .eq(AiChronicDiseaseFollowUp::getFgActive, "1"));
    }

    private ChronicDiseaseFollowUpResponse resolveIdempotent(
        AiChronicDiseaseFollowUp existing,
        ChronicDiseaseFollowUpRequest request
    ) {
        if (!safeEquals(request.getIdPhr(), existing.getIdPhr())
            || !safeEquals(request.getIdRecord(), existing.getIdRecord())
            || !safeEquals(request.getSdVisitKind(), existing.getSdVisitKind())) {
            throw new ConflictException(
                "CHRONIC-FOLLOWUP-CONFLICT",
                "相同 X-Request-Id 已用于其他人员、登记表或随访类型"
            );
        }
        return toResponse(existing);
    }

    private AiChronicDiseaseFollowUp toEntity(
        AiDevice device,
        String requestId,
        ChronicDiseaseFollowUpRequest request
    ) {
        AiChronicDiseaseFollowUp entity = new AiChronicDiseaseFollowUp();
        entity.setRequestId(requestId);
        entity.setIdDevice(device.getIdDevice());
        entity.setIdOrg(device.getIdOrg());
        entity.setIdPhr(request.getIdPhr());
        entity.setIdRecord(request.getIdRecord());
        entity.setSourceFormId(emptyToNull(request.getId()));
        entity.setVisitStatus(request.getStatus());
        entity.setSdVisitKind(request.getSdVisitKind());
        entity.setDtHyPlan(emptyToNull(request.getDtHyPlan()));
        entity.setDtDbsPlan(emptyToNull(request.getDtDbsPlan()));
        entity.setInputUser(emptyToNull(request.getInputUser()));
        entity.setIdUser(emptyToNull(request.getIdUser()));
        entity.setFormDataJson(toJson(request));

        // 兼容尚未发布的旧实验表列；原 TcdVisitForm JSON 是当前唯一业务正文。
        entity.setPatientId(request.getIdPhr());
        entity.setVisitId(request.getIdRecord());
        entity.setPatientName(request.getIdPhr());
        entity.setDiseaseType(diseaseType(request.getSdVisitKind()));
        entity.setManagementSource("public_health");
        entity.setManagementEvidence("sdVisitKind=" + request.getSdVisitKind());
        entity.setTemplateVersion("TCD-VISIT-FORM-INSTANCE-1");
        entity.setPathVersion("TCD-FUSED-FOLLOWUP");
        entity.setEvidenceVersion("TcdVisitForm-instance");
        entity.setRuleVersion("TCD-INSTANCE-RULES-1");
        entity.setFollowUpDate(LocalDate.now());
        entity.setFollowUpMethod("integrated");
        entity.setSymptomCodes(defaultIfBlank(
            normalizeCsv(
                defaultIfBlank(request.getSdHySymptom(), "")
                    + ","
                    + defaultIfBlank(request.getSdDbsSymptom(), "")
            ),
            "-"
        ));
        entity.setSystolicPressure(integerValue(request.getPressureH()));
        entity.setDiastolicPressure(integerValue(request.getPressureL()));
        entity.setFastingGlucose(decimalValue(request.getGlu()));
        entity.setPostprandialGlucose(decimalValue(request.getFbgMeal()));
        entity.setHeightCm(decimalValue(request.getStature()));
        entity.setWeightKg(decimalValue(request.getAvoirdupois()));
        entity.setBmi(decimalValue(request.getBmi()));
        entity.setWaistCm(decimalValue(request.getWaistline()));
        entity.setDailyCigarettes(integerValue(request.getDaySmoke()));
        entity.setDailyAlcoholUnits(decimalValue(request.getDayDrink()));
        entity.setWeeklyExerciseSessions(integerValue(request.getSportWeek()));
        entity.setExerciseMinutes(integerValue(request.getSportMinute()));
        entity.setSaltIntakeLevel(defaultIfBlank(request.getSdSalt(), "-"));
        entity.setPsychologicalStatus(defaultIfBlank(request.getSdPsychicAdj(), "-"));
        entity.setMedicationAdherence(defaultIfBlank(request.getSdDrugPro(), "-"));
        entity.setAdverseReaction("2".equals(request.getSdSideEffects()) ? "1" : "0");
        entity.setAdverseReactionText(emptyToNull(request.getDesSideEffects()));
        entity.setMedicationSummary(medicationSummary(request.getDrugList()));
        entity.setFollowUpClassification(defaultIfBlank(request.getDesComor(), "-"));
        entity.setReferralRequired("0");
        entity.setReferralReason(emptyToNull(request.getDesRef()));
        entity.setReferralOrganization(emptyToNull(request.getRefDep()));
        entity.setNextFollowUpDate(resolveNextDate(request));
        entity.setIdDoctor(emptyToNull(request.getIdUser()));
        entity.setNaDoctor(defaultIfBlank(request.getIdUser(), defaultIfBlank(request.getInputUser(), "-")));
        entity.setNotes(emptyToNull(request.getNote()));
        entity.setSaveStatus("saved");
        entity.setFgActive("1");
        return entity;
    }

    private ChronicDiseaseFollowUpResponse toResponse(AiChronicDiseaseFollowUp entity) {
        LocalDateTime savedAt = entity.getInsertTime() == null ? LocalDateTime.now() : entity.getInsertTime();
        return new ChronicDiseaseFollowUpResponse(
            entity.getIdFollowUp(),
            entity.getRequestId(),
            "saved",
            savedAt,
            entity.getIdPhr(),
            entity.getIdRecord(),
            entity.getSdVisitKind()
        );
    }

    private String toJson(ChronicDiseaseFollowUpRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("慢病融合随访数据序列化失败");
        }
    }

    private String medicationSummary(List<TcdVisitDrugRequest> drugs) {
        String value = drugs == null ? "" : drugs.stream()
            .map(TcdVisitDrugRequest::getNaDrug)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("、"));
        return value.length() > 2000 ? value.substring(0, 2000) : emptyToNull(value);
    }

    private LocalDate resolveNextDate(ChronicDiseaseFollowUpRequest request) {
        for (String value : Arrays.asList(request.getDtHyPlan(), request.getDtDbsPlan())) {
            if (StringUtils.hasText(value)) {
                try {
                    return LocalDate.parse(value);
                } catch (DateTimeParseException ignored) {
                    // 原实例日期是字符串；无法解析时兼容列使用保存日期，JSON 保留原值。
                }
            }
        }
        return LocalDate.now();
    }

    private String diseaseType(String visitKind) {
        Set<String> kinds = new LinkedHashSet<String>(csvValues(visitKind));
        if (kinds.contains("1") && kinds.contains("2")) return "combined";
        return kinds.contains("1") ? "hypertension" : "type2_diabetes";
    }

    private String calculateBmi(String stature, String avoirdupois) {
        BigDecimal height = decimalValue(stature);
        BigDecimal weight = decimalValue(avoirdupois);
        if (height == null || weight == null || height.compareTo(BigDecimal.ZERO) <= 0) return "";
        BigDecimal meters = height.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return weight.divide(meters.multiply(meters), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal validateNumber(
        String value,
        BigDecimal min,
        BigDecimal max,
        String label
    ) {
        require(value, label);
        BigDecimal parsed = decimalValue(value);
        if (parsed == null) {
            throw new BusinessException(label + "必须是数字");
        }
        if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) {
            throw new BusinessException(label + "超出允许范围[" + min + "," + max + "]");
        }
        return parsed;
    }

    private void require(String value, String label) {
        if (!StringUtils.hasText(value)) throw new BusinessException(label + "不能为空");
    }

    private void requireCsv(String value, String label) {
        if (csvValues(value).isEmpty()) throw new BusinessException(label + "不能为空");
    }

    private void requireEnum(Set<String> allowed, String value, String label) {
        if (!allowed.contains(trim(value))) throw new BusinessException(label + "不是受支持的值");
    }

    private Set<String> visitKinds(ChronicDiseaseFollowUpRequest request) {
        return new LinkedHashSet<String>(csvValues(request.getSdVisitKind()));
    }

    private String normalizeCsv(String value) {
        return new LinkedHashSet<String>(csvValues(value)).stream().collect(Collectors.joining(","));
    }

    private List<String> csvValues(String value) {
        if (!StringUtils.hasText(value)) return Collections.emptyList();
        return Arrays.stream(value.split(","))
            .map(this::trim)
            .filter(StringUtils::hasText)
            .collect(Collectors.toList());
    }

    private String normalizeRichText(String value) {
        String normalized = trim(value);
        return "<p><br></p>".equals(normalized) ? "" : normalized;
    }

    private Integer integerValue(String value) {
        BigDecimal decimal = decimalValue(value);
        return decimal == null ? null : decimal.intValue();
    }

    private BigDecimal decimalValue(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String emptyToNull(String value) {
        String normalized = trim(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(values))
        );
    }
}
