package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmrQcRecordVO {

    private String idMedrecdoc;
    private String templateName;
    private String patientName;
    private String gender;
    private String age;
    private String idCardMasked;
    private String doctorName;
    private String deptName;
    private LocalDateTime visitTime;
    private LocalDateTime updateTime;
    private String qcStatus;
    private Integer score;
    private Integer findingCount;
    private Integer highSeverityCount;
    private LocalDateTime analyzedAt;
    private String resultSource;
    private boolean stale;
}
