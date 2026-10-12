package com.regionalai.floatingball.server.modules.publichealth.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "floating-ball.public-health")
public class PublicHealthProperties {
    @Value("${floating-ball.public-health.enabled:${FB_PUBLIC_HEALTH_ENABLED:false}}")
    private boolean enabled;
    @Value("${floating-ball.public-health.asset-directory:${FB_PUBLIC_HEALTH_ASSET_DIR:}}")
    private String assetDirectory = "";
    private Map<String, String> orgAssets = new HashMap<String, String>();
    private boolean retrievalEnabled;
    private int retrievalCandidates = 20;
}
