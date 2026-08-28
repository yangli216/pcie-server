package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

@Data
public class PhisAiPermissionCheckResponse {
    private boolean allowed;
    private String reason;
    private String permissionId;
    private String orgId;
    private String personId;
    private String userId;
    private String personCd;
}
