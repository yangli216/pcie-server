package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter @Setter @Component
@ConfigurationProperties(prefix = "floating-ball.chronic-examinations")
public class ChronicExaminationProperties {
    private boolean enabled;
    private String baseUrl = "";
    private String ak = "";
    private String sk = "";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 8000;
}
