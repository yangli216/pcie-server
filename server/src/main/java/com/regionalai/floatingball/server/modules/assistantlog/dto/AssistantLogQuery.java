package com.regionalai.floatingball.server.modules.assistantlog.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AssistantLogQuery {
    private String doctorName;
    private String idOrg;
    private String hisOrgId;
    private String dateFrom;
    private String dateTo;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
