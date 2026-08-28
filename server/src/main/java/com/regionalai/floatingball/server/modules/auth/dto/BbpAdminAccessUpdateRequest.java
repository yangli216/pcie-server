package com.regionalai.floatingball.server.modules.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class BbpAdminAccessUpdateRequest {

    @NotBlank(message = "机构 ID 不能为空")
    private String orgId;

    private String roleCode;

    @NotNull(message = "目标授权状态不能为空")
    private Boolean enabled;
}
