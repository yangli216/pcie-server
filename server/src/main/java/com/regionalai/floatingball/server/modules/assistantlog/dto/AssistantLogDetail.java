package com.regionalai.floatingball.server.modules.assistantlog.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssistantLogDetail extends AssistantLogItem {
    private String inputText;
    private String outputText;
    private boolean contentAvailable;
}
