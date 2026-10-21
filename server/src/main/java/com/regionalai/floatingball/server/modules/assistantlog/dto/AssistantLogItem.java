package com.regionalai.floatingball.server.modules.assistantlog.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AssistantLogItem {
    private String idLog;
    private String idOrg;
    private String hisOrgId;
    private String orgName;
    private String doctorId;
    private String doctorName;
    private LocalDateTime usageTime;
    private String inputPreview;
    private int imageCount;
    private boolean success;
}
