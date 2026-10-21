package com.regionalai.floatingball.server.modules.assistantlog.dto;

import lombok.Data;
import java.time.LocalDateTime;

/** Internal database projection; never returned directly by the API. */
@Data
public class AssistantLogRow {
    private String idLog;
    private String idOrg;
    private String hisOrgId;
    private String organizationName;
    private LocalDateTime operationTime;
    private String opResult;
    private String payloadJson;
}
