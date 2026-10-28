package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmrQcSessionVO {

    private boolean available;
    private String hisOrgId;
    private String hisOrgName;
    private String deptId;
    private String deptName;
    private String doctorName;
    private String roleName;
    private LocalDateTime sessionExpiresAt;
    private String unavailableCode;
    private String unavailableReason;
}
