package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiUserPermissionRecordView {
    private String permissionId;
    private String tenantId;
    private String orgId;
    private String orgName;
    private String personId;
    private String userId;
    private String personCd;
    private String personName;
    private String departmentId;
    private String departmentName;
    private String personType;
    private String personTypeText;
    private String titleType;
    private String titleTypeText;
    private Boolean active;
    private boolean configured;
    private String operatorUserName;
    private LocalDateTime updateTime;
}
