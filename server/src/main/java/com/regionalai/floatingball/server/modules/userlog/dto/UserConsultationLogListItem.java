package com.regionalai.floatingball.server.modules.userlog.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserConsultationLogListItem {

    private String idLog;
    private String consultationId;
    private String consultationRoundId;
    private String idOrg;
    private String hisOrgId;
    private String naOrg;
    private String idDoctor;
    private String doctorWorkNo;
    private String naDoctor;
    private String consultationType;
    private LocalDateTime consultationTime;
    private String patientId;
    private String patientName;
    private String patientGender;
    private String patientAge;
    private Boolean hasAudio;
    private Boolean hasSpeechText;
    private Integer totalChanges;
    private String status;
}
