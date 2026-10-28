package com.regionalai.floatingball.server.modules.phisemr.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PhisEmrRecord {

    private String idMedrecdoc;
    private String idTep;
    private String templateName;
    private String patientName;
    private String genderCode;
    private String idCard;
    private String idArchives;
    private String doctorName;
    private String deptName;
    private String hisOrgId;
    private LocalDateTime visitTime;
    private LocalDateTime updateTime;
}
