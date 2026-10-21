package com.regionalai.floatingball.server.modules.knowledge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "floating-ball.drug-instructions")
public class DrugInstructionProperties {
    private boolean enabled;
    private String jdbcUrl = "";
    private String username = "";
    private String password = "";
}
