package com.regionalai.floatingball.server.modules.auth.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdminCurrentUser {

    private String idUser;
    private String cdUser;
    private String naUser;
    private String idOrg;
    private List<String> roles;
    private String authProvider;
    private String authSessionId;
    private String bbpTenantId;
    private String bbpUserId;
    private String bbpRoleId;
    private String bbpOrgId;
    private String bbpOrgCode;
    private String bbpOrgName;
}
