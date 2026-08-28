package com.regionalai.floatingball.server.modules.lisresult.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.ObjectIdUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.lisresult.dto.LisReportItemRequest;
import com.regionalai.floatingball.server.modules.lisresult.dto.LisReportSubmitRequest;
import com.regionalai.floatingball.server.modules.lisresult.dto.LisReportSubmitResponse;
import com.regionalai.floatingball.server.modules.lisresult.dto.PacsReportSubmitRequest;
import com.regionalai.floatingball.server.modules.lisresult.entity.HiOdsApply;
import com.regionalai.floatingball.server.modules.lisresult.entity.HiOdsApplyLisReport;
import com.regionalai.floatingball.server.modules.lisresult.entity.HiOdsApplyPacsReport;
import com.regionalai.floatingball.server.modules.lisresult.mapper.HiOdsApplyLisReportMapper;
import com.regionalai.floatingball.server.modules.lisresult.mapper.HiOdsApplyMapper;
import com.regionalai.floatingball.server.modules.lisresult.mapper.HiOdsApplyPacsReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class LisResultEntryService {

    private static final String DISP_LIS = "1";
    private static final String DISP_PACS = "2";
    private static final String BUSINESS_OUTPATIENT = "1";
    private static final String BUSINESS_INPATIENT = "2";
    private static final String STATUS_REPORTED = "3";
    private static final String STATUS_VOID = "9";
    private static final int MAX_REPORT_ITEMS = 200;
    private static final int DEFAULT_RECENT_DAYS = 3;

    private final HiOdsApplyMapper applyMapper;
    private final HiOdsApplyLisReportMapper reportMapper;
    private final HiOdsApplyPacsReportMapper pacsReportMapper;
    private final DatabaseDialect databaseDialect;

    public LisResultEntryService(HiOdsApplyMapper applyMapper,
                                 HiOdsApplyLisReportMapper reportMapper,
                                 HiOdsApplyPacsReportMapper pacsReportMapper,
                                 DatabaseDialect databaseDialect) {
        this.applyMapper = applyMapper;
        this.reportMapper = reportMapper;
        this.pacsReportMapper = pacsReportMapper;
        this.databaseDialect = databaseDialect;
    }

    public PageResponse<HiOdsApply> listApplies(long current,
                                                long size,
                                                String keyword,
                                                String dispType,
                                                String businessType,
                                                String status,
                                                String idOrg,
                                                String dateFrom,
                                                String dateTo) {
        PageRequest pageRequest = PageRequest.of(current, size);
        TimeRange timeRange = resolveApplyTimeRange(dateFrom, dateTo);
        Page<HiOdsApply> page = new Page<HiOdsApply>(pageRequest.getCurrent(), pageRequest.getSize());
        QueryWrapper<HiOdsApply> wrapper = new QueryWrapper<HiOdsApply>()
            .select(
                "id_apply",
                "cd_apply",
                "na_apply",
                "sd_disp",
                "sd_business",
                "sd_apply",
                "fg_urgent",
                "id_pi",
                "id_vis",
                "id_reg",
                "nas_diag",
                "na_dept_exec",
                "na_doc_exec",
                "id_org",
                "insert_time"
            )
            .and(q -> q.isNull("id_result").or().eq("id_result", ""))
            .ge("insert_time", timeRange.start)
            .lt("insert_time", timeRange.endExclusive)
            .orderByDesc("insert_time")
            .orderByDesc("fg_urgent");

        if (StringUtils.hasText(dispType)) {
            String normalizedDispType = dispType.trim();
            if (!DISP_LIS.equals(normalizedDispType) && !DISP_PACS.equals(normalizedDispType)) {
                throw new BusinessException("申请单类别只支持检验或检查");
            }
            wrapper.eq("sd_disp", normalizedDispType);
        } else {
            wrapper.in("sd_disp", Arrays.asList(DISP_LIS, DISP_PACS));
        }
        if (StringUtils.hasText(businessType)) {
            String normalizedBusinessType = businessType.trim();
            if (!BUSINESS_OUTPATIENT.equals(normalizedBusinessType) && !BUSINESS_INPATIENT.equals(normalizedBusinessType)) {
                throw new BusinessException("申请类别只支持门诊或住院");
            }
            wrapper.eq("sd_business", normalizedBusinessType);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("sd_apply", status.trim());
        } else {
            wrapper.notIn("sd_apply", Arrays.asList(STATUS_REPORTED, STATUS_VOID));
        }
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            wrapper.and(q -> q.like("cd_apply", value)
                .or().like("na_apply", value)
                .or().like("id_pi", value)
                .or().like("id_vis", value)
                .or().like("nas_diag", value));
        }
        if (StringUtils.hasText(idOrg)) {
            wrapper.eq("id_org", idOrg.trim());
        }

        Page<HiOdsApply> result = applyMapper.selectPage(page, wrapper);
        return new PageResponse<HiOdsApply>(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    private TimeRange resolveApplyTimeRange(String dateFrom, String dateTo) {
        LocalDate today = LocalDate.now();
        LocalDate from = StringUtils.hasText(dateFrom)
            ? parseDate(dateFrom, "开始日期格式不正确")
            : today.minusDays(DEFAULT_RECENT_DAYS - 1L);
        LocalDate to = StringUtils.hasText(dateTo)
            ? parseDate(dateTo, "结束日期格式不正确")
            : today;
        if (to.isBefore(from)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        return new TimeRange(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private LocalDate parseDate(String value, String message) {
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(message);
        }
    }

    public List<HiOdsApplyLisReport> listReports(String idApply) {
        if (!StringUtils.hasText(idApply)) {
            throw new BusinessException("申请单ID不能为空");
        }
        return reportMapper.selectList(new QueryWrapper<HiOdsApplyLisReport>()
            .select(
                "id_report",
                "id_apply",
                "resultid",
                "id_report_group",
                "cd_result",
                "na_result",
                "test_result",
                "result_qualitative",
                "reference_range",
                "reference_low",
                "reference_high",
                "result_unit",
                "result_hint",
                "instrument_code",
                "instrument_name",
                "insert_user",
                "insert_time",
                "update_user",
                "update_time"
            )
            .eq("id_apply", idApply.trim())
            .orderByAsc("insert_time")
            .orderByAsc("cd_result"));
    }

    public HiOdsApplyPacsReport getPacsReport(String idApply) {
        if (!StringUtils.hasText(idApply)) {
            throw new BusinessException("申请单ID不能为空");
        }
        return pacsReportMapper.selectOne(new QueryWrapper<HiOdsApplyPacsReport>()
            .select(
                "id_report",
                "id_apply",
                "\"RESULT\"",
                "remark",
                "clinical_impression",
                "negative_positive",
                "diagnostic_imaging",
                "na_insert_user",
                "na_update_user",
                "cd_study",
                "id_dept",
                "na_dept",
                "insert_user",
                "insert_time",
                "update_user",
                "update_time"
            )
            .eq("id_apply", idApply.trim())
            .orderByDesc("update_time")
            .last(databaseDialect.firstRows(1)));
    }

    @Transactional
    public LisReportSubmitResponse submitReport(String idApply,
                                                LisReportSubmitRequest request,
                                                AdminCurrentUser currentUser) {
        if (!StringUtils.hasText(idApply)) {
            throw new BusinessException("申请单ID不能为空");
        }
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }

        List<LisReportItemRequest> validItems = collectValidItems(request.getItems());
        if (validItems.isEmpty()) {
            throw new BusinessException("至少录入一项检验结果");
        }
        if (validItems.size() > MAX_REPORT_ITEMS) {
            throw new BusinessException("单份报告最多录入" + MAX_REPORT_ITEMS + "项结果");
        }

        HiOdsApply apply = findApplyForWriteback(idApply);
        validateWritableApply(apply, DISP_LIS, "仅支持检验申请单录入指标结果");

        LocalDateTime now = LocalDateTime.now();
        String reportGroupId = ObjectIdUtils.next();
        String operator = resolveOperator(currentUser, request.getReportDoctor());
        String auditDoctor = StringUtils.hasText(request.getAuditDoctor()) ? request.getAuditDoctor().trim() : operator;
        String instrumentCode = StringUtils.hasText(request.getInstrumentCode()) ? request.getInstrumentCode().trim() : "MANUAL";
        String instrumentName = StringUtils.hasText(request.getInstrumentName()) ? request.getInstrumentName().trim() : "手工录入";

        for (LisReportItemRequest item : validItems) {
            HiOdsApplyLisReport report = new HiOdsApplyLisReport();
            report.setIdReport(ObjectIdUtils.next());
            report.setIdApply(apply.getIdApply());
            report.setResultid(reportGroupId);
            report.setIdReportGroup(reportGroupId);
            report.setCdResult(trimToNull(item.getCdResult()));
            report.setNaResult(trimToNull(item.getNaResult()));
            report.setTestResult(trimToNull(item.getTestResult()));
            report.setResultQualitative(trimToNull(item.getResultQualitative()));
            report.setReferenceRange(trimToNull(item.getReferenceRange()));
            report.setReferenceLow(trimToNull(item.getReferenceLow()));
            report.setReferenceHigh(trimToNull(item.getReferenceHigh()));
            report.setResultUnit(trimToNull(item.getResultUnit()));
            report.setResultHint(trimToNull(item.getResultHint()));
            report.setInstrumentCode(instrumentCode);
            report.setInstrumentName(instrumentName);
            report.setIdOrg(apply.getIdOrg());
            report.setIdTet(apply.getIdTet());
            report.setRevision(1);
            report.setInsertUser(operator);
            report.setInsertTime(now);
            report.setUpdateUser(auditDoctor);
            report.setUpdateTime(now);
            reportMapper.insert(report);
        }

        markApplyReported(apply, reportGroupId, operator, now);

        return new LisReportSubmitResponse(apply.getIdApply(), reportGroupId, validItems.size());
    }

    @Transactional
    public LisReportSubmitResponse submitPacsReport(String idApply,
                                                    PacsReportSubmitRequest request,
                                                    AdminCurrentUser currentUser) {
        if (!StringUtils.hasText(idApply)) {
            throw new BusinessException("申请单ID不能为空");
        }
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getResult()) && !StringUtils.hasText(request.getDiagnosticImaging())) {
            throw new BusinessException("检查结果或影像诊断至少填写一项");
        }

        HiOdsApply apply = findApplyForWriteback(idApply);
        validateWritableApply(apply, DISP_PACS, "仅支持检查申请单录入检查报告");

        LocalDateTime now = LocalDateTime.now();
        String reportId = ObjectIdUtils.next();
        String operator = resolveOperator(currentUser, request.getReportDoctor());
        String auditDoctor = StringUtils.hasText(request.getAuditDoctor()) ? request.getAuditDoctor().trim() : operator;

        HiOdsApplyPacsReport report = new HiOdsApplyPacsReport();
        report.setIdReport(reportId);
        report.setIdApply(apply.getIdApply());
        report.setResult(trimToNull(request.getResult()));
        report.setRemark(trimToNull(request.getRemark()));
        report.setClinicalImpression(trimToNull(request.getClinicalImpression()));
        report.setNegativePositive(trimToNull(request.getNegativePositive()));
        report.setDiagnosticImaging(trimToNull(request.getDiagnosticImaging()));
        report.setNaInsertUser(operator);
        report.setNaUpdateUser(auditDoctor);
        report.setCdStudy(trimToNull(request.getCdStudy()));
        report.setIdDept(trimToNull(request.getIdDept()));
        report.setNaDept(trimToNull(request.getNaDept()));
        report.setIdOrg(apply.getIdOrg());
        report.setIdTet(apply.getIdTet());
        report.setRevision(1);
        report.setInsertUser(operator);
        report.setInsertTime(now);
        report.setUpdateUser(auditDoctor);
        report.setUpdateTime(now);
        pacsReportMapper.insert(report);

        markApplyReported(apply, reportId, operator, now);
        return new LisReportSubmitResponse(apply.getIdApply(), reportId, 1);
    }

    private HiOdsApply findApplyForWriteback(String idApply) {
        return applyMapper.selectOne(new QueryWrapper<HiOdsApply>()
            .select(
                "id_apply",
                "sd_disp",
                "sd_apply",
                "id_result",
                "id_org",
                "id_tet",
                "revision",
                "dt_exec"
            )
            .eq("id_apply", idApply.trim())
            .last(databaseDialect.firstRows(1)));
    }

    private void validateWritableApply(HiOdsApply apply, String expectedDispType, String dispErrorMessage) {
        if (apply == null) {
            throw new BusinessException("申请单不存在");
        }
        if (!expectedDispType.equals(apply.getSdDisp())) {
            throw new BusinessException(dispErrorMessage);
        }
        if (STATUS_VOID.equals(apply.getSdApply())) {
            throw new BusinessException("申请单已作废，不能回写结果");
        }
        if (STATUS_REPORTED.equals(apply.getSdApply()) || StringUtils.hasText(apply.getIdResult())) {
            throw new BusinessException("申请单已报告，不能重复回写");
        }
    }

    private void markApplyReported(HiOdsApply apply, String reportId, String operator, LocalDateTime now) {
        int nextRevision = apply.getRevision() == null ? 1 : apply.getRevision() + 1;
        LocalDateTime execTime = apply.getDtExec() == null ? now : apply.getDtExec();
        applyMapper.update(null, new UpdateWrapper<HiOdsApply>()
            .eq("id_apply", apply.getIdApply())
            .set("id_result", reportId)
            .set("sd_apply", STATUS_REPORTED)
            .set("dt_exec", execTime)
            .set("update_user", operator)
            .set("update_time", now)
            .set("revision", nextRevision));
    }

    private List<LisReportItemRequest> collectValidItems(List<LisReportItemRequest> items) {
        List<LisReportItemRequest> validItems = new ArrayList<LisReportItemRequest>();
        if (CollectionUtils.isEmpty(items)) {
            return validItems;
        }
        for (LisReportItemRequest item : items) {
            if (item == null) {
                continue;
            }
            if (StringUtils.hasText(item.getNaResult())
                && (StringUtils.hasText(item.getTestResult()) || StringUtils.hasText(item.getResultQualitative()))) {
                validItems.add(item);
            }
        }
        return validItems;
    }

    private String resolveOperator(AdminCurrentUser currentUser, String requestedReportDoctor) {
        if (StringUtils.hasText(requestedReportDoctor)) {
            return requestedReportDoctor.trim();
        }
        if (currentUser != null && StringUtils.hasText(currentUser.getNaUser())) {
            return currentUser.getNaUser();
        }
        if (currentUser != null && StringUtils.hasText(currentUser.getCdUser())) {
            return currentUser.getCdUser();
        }
        return "手工录入";
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static class TimeRange {
        private final LocalDateTime start;
        private final LocalDateTime endExclusive;

        private TimeRange(LocalDateTime start, LocalDateTime endExclusive) {
            this.start = start;
            this.endExclusive = endExclusive;
        }
    }
}
