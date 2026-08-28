package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiUserPermissionBatchUpdateResponse {
    private String orgId;
    private boolean enabled;
    private int requestedCount;
    private int changedCount;
    private int unchangedCount;
    private List<AiUserPermissionRecordView> records;
}
