package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class AiUserPermissionUpdateRequest {

    @NotBlank(message = "机构 ID 不能为空")
    private String orgId;

    @NotNull(message = "权限状态不能为空")
    private Boolean enabled;
}
