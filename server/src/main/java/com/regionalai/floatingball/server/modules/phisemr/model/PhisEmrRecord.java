package com.regionalai.floatingball.server.modules.phisemr.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
public class PhisEmrRecord {

    private String idMedrecdoc;
    private String idTep;
    private String templateName;
    @ToString.Exclude
    private String patientName;
    private String genderCode;
    @ToString.Exclude
    private String idCard;
    @ToString.Exclude
    private String idArchives;
    private String doctorName;
    private String doctorUserId;
    private String deptName;
    private String hisOrgId;
    private LocalDateTime visitTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private boolean snapshot;
}
