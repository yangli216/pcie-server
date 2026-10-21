package com.regionalai.floatingball.server.modules.ai.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.util.List;
import java.util.Map;

@Data
public class ChatRequest {

    @Size(max = 128)
    private String doctorId;
    @Size(max = 128)
    private String doctorName;
    @Size(max = 128)
    private String hisOrgId;
    @Size(max = 128)
    private String hisOrgName;
    private String model;
    private String configProfile;
    private String consultationId;
    private String traceId;
    private String scene;
    private String sourceModule;
    private String operationAction;
    private String operationTitle;
    private String sessionId;
    @NotEmpty(message = "messages 不能为空")
    private List<Map<String, Object>> messages;
    private Boolean stream;
    private Boolean enableSearch;
    private Double temperature;
}
