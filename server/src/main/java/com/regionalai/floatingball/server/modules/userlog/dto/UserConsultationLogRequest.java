package com.regionalai.floatingball.server.modules.userlog.dto;

import lombok.Data;

@Data
public class UserConsultationLogRequest {

    private String consultationId;
    private String consultationRoundId;
    private String consultationType;
    private Long consultationTime;

    private String patientId;
    private String patientName;
    private String patientGender;
    private String patientAge;

    private String doctorId;
    private String doctorWorkNo;
    private String doctorName;
    private String orgCode;
    private String hisOrgId;
    private String orgName;
    private String deptId;
    private String deptName;

    private String speechText;
    private String audio;
    private String audioMimeType;
    private String audioFormat;
    private String audioFileName;

    private Object firstSnapshot;
    private Object finalSnapshot;
    private Object selectionSnapshot;
    private Object changeSummary;
    private Boolean abandoned;
}
