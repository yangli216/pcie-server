package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class PhisAiPermissionCheckRequest {
    private String tenantId;

    @NotBlank(message = "机构 ID 不能为空")
    private String orgId;

    private String personId;
    private String userId;
    private String personCd;
}
