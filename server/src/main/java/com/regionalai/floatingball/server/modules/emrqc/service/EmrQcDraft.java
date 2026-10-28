package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class EmrQcDraft {

    private String idOrg;
    private String hisOrgId;
    private String idMedrecdoc;
    private String idTep;
    private String templateName;
    private String patientNameMasked;
    private String patientName;
    private String genderCode;
    private String patientAge;
    private String idCardEncrypted;
    private String archivesEncrypted;
    private String doctorUserId;
    private LocalDateTime phisCreateTime;
    private String idCardMasked;
    private String doctorName;
    private String deptName;
    private LocalDateTime visitTime;
    private LocalDateTime phisUpdateTime;
    private String contentHash;
    private String analysisId;
    private Integer score;
    private String dimensionsJson;
    private List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
    private String model;
    private String ruleVersion;
    private LocalDateTime analyzedAt;
}
