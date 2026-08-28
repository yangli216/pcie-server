package com.regionalai.floatingball.server.modules.auth.dto;

import lombok.Data;

@Data
public class AdminAuthCapabilities {
    private String mode;
    private boolean bbpEnabled;
    private boolean tenantRequired;
    private String tenantId;
}
