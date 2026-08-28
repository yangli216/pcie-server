package com.regionalai.floatingball.server.modules.auth.bbp;

import lombok.Data;

@Data
public class BbpOrganizationView {
    private String id;
    private String code;
    private String name;
    private String orgId;
    private String tenantId;
    private String orgType;
    private String orgTypeText;
    private String fullName;
    private String parentId;
    private String parentName;
    private Boolean active;
}
