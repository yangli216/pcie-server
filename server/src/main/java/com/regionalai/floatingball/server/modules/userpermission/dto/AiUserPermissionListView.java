package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiUserPermissionListView {
    private String orgId;
    private boolean manageable;
    private int total;
    private int configuredCount;
    private int unconfiguredCount;
    private int riskAuthorizedCount;
    private int filteredTotal;
    private int current;
    private int size;
    private List<AiUserPermissionDepartmentView> departments;
    private List<AiUserPermissionRecordView> records;
}
