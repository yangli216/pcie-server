package com.regionalai.floatingball.server.modules.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class BbpLoginRequest {

    @NotBlank(message = "账号不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String tenantId;
}
