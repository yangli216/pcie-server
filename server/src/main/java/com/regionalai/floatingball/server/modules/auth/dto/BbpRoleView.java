package com.regionalai.floatingball.server.modules.auth.dto;

import lombok.Data;

@Data
public class BbpRoleView {
    private String authorizationId;
    private String roleId;
    private String roleCode;
    private String roleName;
    private String userId;
    private String userName;
    private String loginName;
    private String tenantId;
    private String tenantName;
    private String orgId;
    private String orgCode;
    private String orgName;
    private String departmentId;
    private String departmentName;
    private String departmentAuthorizationId;
    private Boolean active;
}
