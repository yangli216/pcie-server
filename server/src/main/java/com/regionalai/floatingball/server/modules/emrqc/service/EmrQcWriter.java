package com.regionalai.floatingball.server.modules.emrqc.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcFinding;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcRecord;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcFindingMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EmrQcWriter {

    private final AiEmrQcRecordMapper recordMapper;
    private final AiEmrQcFindingMapper findingMapper;

    public EmrQcWriter(AiEmrQcRecordMapper recordMapper, AiEmrQcFindingMapper findingMapper) {
        this.recordMapper = recordMapper;
        this.findingMapper = findingMapper;
    }

    public AiEmrQcRecord findRecord(String idOrg, String idMedrecdoc) {
        List<AiEmrQcRecord> records = recordMapper.selectList(new QueryWrapper<AiEmrQcRecord>()
            .eq("id_org", idOrg)
            .eq("id_medrecdoc", idMedrecdoc));
        return records.isEmpty() ? null : records.get(0);
    }

    public List<AiEmrQcFinding> findFindings(String idAnalysis) {
        return findingMapper.selectList(new QueryWrapper<AiEmrQcFinding>()
            .eq("id_analysis", idAnalysis)
            .orderByAsc("sort_no"));
    }

    @Transactional
    public AiEmrQcRecord save(EmrQcDraft draft) {
        AiEmrQcRecord entity = findRecord(draft.getIdOrg(), draft.getIdMedrecdoc());
        boolean insert = entity == null;
        if (insert) {
            entity = new AiEmrQcRecord();
            entity.setIdOrg(draft.getIdOrg());
            entity.setIdMedrecdoc(draft.getIdMedrecdoc());
        }
        entity.setHisOrgId(draft.getHisOrgId());
        entity.setIdTep(draft.getIdTep());
        entity.setTemplateName(draft.getTemplateName());
        entity.setPatientNameMasked(draft.getPatientNameMasked());
        entity.setIdCardMasked(draft.getIdCardMasked());
        entity.setDoctorName(draft.getDoctorName());
        entity.setDeptName(draft.getDeptName());
        entity.setVisitTime(draft.getVisitTime());
        entity.setPhisUpdateTime(draft.getPhisUpdateTime());
        entity.setContentHash(draft.getContentHash());
        entity.setLatestAnalysisId(draft.getAnalysisId());
        entity.setScore(draft.getScore());
        entity.setDimensionsJson(draft.getDimensionsJson());
        entity.setFindingCount(countActive(draft.getFindings()));
        entity.setHighSeverityCount(countHigh(draft.getFindings()));
        entity.setModel(draft.getModel());
        entity.setRuleVersion(draft.getRuleVersion());
        entity.setAnalyzedAt(draft.getAnalyzedAt());
        entity.setResultSource("ai");
        entity.setAiScore(draft.getScore());
        entity.setAiAnalysisId(draft.getAnalysisId());
        if (insert) {
            recordMapper.insert(entity);
        } else {
            recordMapper.updateById(entity);
            clearReviewFields(entity.getIdQcRecord());
        }

        int sortNo = 1;
        for (EmrQcAnalysisVO.Finding source : draft.getFindings()) {
            insertFinding(entity.getIdQcRecord(), draft.getAnalysisId(), sortNo++, source);
        }
        return entity;
    }

    @Transactional
    public AiEmrQcRecord saveReview(AiEmrQcRecord entity,
                                    String analysisId,
                                    int score,
                                    String dimensionsJson,
                                    List<EmrQcAnalysisVO.Finding> findings,
                                    String operatorId,
                                    String operatorName,
                                    String reason) {
        if (!"manual".equals(entity.getResultSource())) {
            entity.setAiScore(entity.getScore());
            entity.setAiAnalysisId(entity.getLatestAnalysisId());
        }
        entity.setLatestAnalysisId(analysisId);
        entity.setScore(score);
        entity.setDimensionsJson(dimensionsJson);
        entity.setFindingCount(countActive(findings));
        entity.setHighSeverityCount(countHigh(findings));
        entity.setResultSource("manual");
        entity.setReviewedBy(operatorId);
        entity.setReviewedByName(operatorName);
        entity.setReviewedAt(java.time.LocalDateTime.now());
        entity.setReviewReason(reason);
        recordMapper.updateById(entity);

        int sortNo = 1;
        for (EmrQcAnalysisVO.Finding source : findings) {
            insertFinding(entity.getIdQcRecord(), analysisId, sortNo++, source);
        }
        return entity;
    }

    private void clearReviewFields(String idQcRecord) {
        recordMapper.update(null, new UpdateWrapper<AiEmrQcRecord>()
            .eq("id_qc_record", idQcRecord)
            .set("reviewed_by", null)
            .set("reviewed_by_name", null)
            .set("reviewed_at", null)
            .set("review_reason", null));
    }

    private void insertFinding(String idQcRecord, String analysisId, int sortNo, EmrQcAnalysisVO.Finding source) {
        AiEmrQcFinding finding = new AiEmrQcFinding();
        finding.setIdQcRecord(idQcRecord);
        finding.setIdAnalysis(analysisId);
        finding.setSortNo(sortNo);
        finding.setSeverity(source.getSeverity());
        finding.setCategory(source.getCategory());
        finding.setRuleCode(source.getRuleCode());
        finding.setSectionName(source.getSectionName());
        finding.setExcerpt(source.getExcerpt());
        finding.setIssue(source.getIssue());
        finding.setSuggestion(source.getSuggestion());
        finding.setDeduction(source.getDeduction() == null ? 0 : source.getDeduction());
        finding.setIgnored(Boolean.TRUE.equals(source.getIgnored()));
        findingMapper.insert(finding);
    }

    private int countActive(List<EmrQcAnalysisVO.Finding> findings) {
        int count = 0;
        for (EmrQcAnalysisVO.Finding finding : findings) {
            if (!Boolean.TRUE.equals(finding.getIgnored())) {
                count++;
            }
        }
        return count;
    }

    public int countHigh(List<EmrQcAnalysisVO.Finding> findings) {
        int count = 0;
        for (EmrQcAnalysisVO.Finding finding : findings) {
            if (!Boolean.TRUE.equals(finding.getIgnored()) && "high".equals(finding.getSeverity())) {
                count++;
            }
        }
        return count;
    }
}
