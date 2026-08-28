package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AiUserPermissionOrgRequest {
    @NotBlank(message = "机构 ID 不能为空")
    private String orgId;
}
