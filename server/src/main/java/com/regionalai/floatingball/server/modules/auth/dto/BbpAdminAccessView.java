package com.regionalai.floatingball.server.modules.auth.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BbpAdminAccessView {
    private String grantId;
    private String tenantId;
    private String orgId;
    private String orgName;
    private String bbpUserId;
    private String personId;
    private String loginName;
    private String personCode;
    private String personName;
    private String departmentId;
    private String departmentName;
    private String personType;
    private String personTypeText;
    private String titleType;
    private String titleTypeText;
    private boolean directoryPresent;
    private Boolean active;
    private boolean adminAccessEnabled;
    private String roleCode;
    private boolean grantRisk;
    private String operatorUserName;
    private LocalDateTime updateTime;
}
