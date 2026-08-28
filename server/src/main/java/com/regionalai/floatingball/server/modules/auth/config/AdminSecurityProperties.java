package com.regionalai.floatingball.server.modules.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "floating-ball.admin")
public class AdminSecurityProperties {

    private BootstrapReset bootstrapReset = new BootstrapReset();
    private Auth auth = new Auth();

    public BootstrapReset getBootstrapReset() {
        return bootstrapReset;
    }

    public void setBootstrapReset(BootstrapReset bootstrapReset) {
        this.bootstrapReset = bootstrapReset;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public static class Auth {

        private String mode = "local";
        private Bbp bbp = new Bbp();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Bbp getBbp() {
            return bbp;
        }

        public void setBbp(Bbp bbp) {
            this.bbp = bbp;
        }
    }

    public static class Bbp {

        private String baseUrl = "";
        private String tenantId = "";
        private String systemLoginName = "system";
        private String systemLocalUsername = "admin";
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 30000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getSystemLoginName() {
            return systemLoginName;
        }

        public void setSystemLoginName(String systemLoginName) {
            this.systemLoginName = systemLoginName;
        }

        public String getSystemLocalUsername() {
            return systemLocalUsername;
        }

        public void setSystemLocalUsername(String systemLocalUsername) {
            this.systemLocalUsername = systemLocalUsername;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

    }

    public static class BootstrapReset {

        private boolean enabled;
        private String username = "admin";
        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
