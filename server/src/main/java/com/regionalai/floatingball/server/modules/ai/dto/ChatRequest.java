package com.regionalai.floatingball.server.modules.ai.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

@Data
public class ChatRequest {

    private String model;
    private String configProfile;
    private String consultationId;
    private String traceId;
    private String scene;
    private String sourceModule;
    private String sessionId;
    @NotEmpty(message = "messages 不能为空")
    private List<Map<String, Object>> messages;
    private Boolean stream;
    private Boolean enableSearch;
    private Double temperature;
}
