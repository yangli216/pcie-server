package com.regionalai.floatingball.server.modules.auth.config;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;

@Component
public class AdminAuthMode {

    public static final String LOCAL = "local";
    public static final String BBP = "bbp";

    private final AdminSecurityProperties properties;

    public AdminAuthMode(AdminSecurityProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        String mode = mode();
        if (!LOCAL.equals(mode) && !BBP.equals(mode)) {
            throw new IllegalStateException("floating-ball.admin.auth.mode 只支持 local 或 bbp");
        }
        if (BBP.equals(mode)) {
            normalizeBaseUrl();
        }
    }

    public String mode() {
        String value = properties.getAuth() == null ? null : properties.getAuth().getMode();
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : LOCAL;
    }

    public boolean isBbp() {
        return BBP.equals(mode());
    }

    public void requireBbp() {
        if (!isBbp()) {
            throw new BusinessException("当前未启用 BBP 认证模式");
        }
    }

    public void requireDirectoryWritable() {
        if (isBbp()) {
            throw new BusinessException("BBP模式下机构和人员由 PHIS 统一维护，仅允许查看");
        }
    }

    public String configuredTenantId() {
        String value = properties.getAuth().getBbp().getTenantId();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public String resolveTenantId(String requestedTenantId) {
        String configured = configuredTenantId();
        if (configured != null) {
            if (StringUtils.hasText(requestedTenantId) && !configured.equals(requestedTenantId.trim())) {
                throw new BusinessException("租户与服务端 BBP 配置不一致");
            }
            return configured;
        }
        if (!StringUtils.hasText(requestedTenantId)) {
            throw new BusinessException("BBP 租户 ID 不能为空");
        }
        return requestedTenantId.trim();
    }

    public String resolveLocalUsername(String bbpLoginName, String bbpRoleCode) {
        if (!StringUtils.hasText(bbpLoginName)) {
            throw new BusinessException("BBP登录身份缺少登录名");
        }
        AdminSecurityProperties.Bbp bbp = bbpProperties();
        if (!isSystemLoginName(bbpLoginName)) {
            return bbpLoginName.trim();
        }
        if (!"tenantSystem".equals(bbpRoleCode)) {
            throw new BusinessException("BBP system 账号必须使用租户管理员身份");
        }
        if (!StringUtils.hasText(bbp.getSystemLocalUsername())) {
            throw new BusinessException("BBP system 本地管理员映射未配置");
        }
        return bbp.getSystemLocalUsername().trim();
    }

    public boolean isSystemAdministratorRole(String bbpLoginName, String bbpRoleCode) {
        return isSystemLoginName(bbpLoginName) && "tenantSystem".equals(bbpRoleCode);
    }

    public boolean isSystemLoginName(String bbpLoginName) {
        if (!StringUtils.hasText(bbpLoginName)) {
            return false;
        }
        AdminSecurityProperties.Bbp bbp = bbpProperties();
        String systemLoginName = StringUtils.hasText(bbp.getSystemLoginName())
            ? bbp.getSystemLoginName().trim() : "system";
        return systemLoginName.equals(bbpLoginName.trim());
    }

    public String normalizeBaseUrl() {
        String raw = properties.getAuth().getBbp().getBaseUrl();
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("启用 BBP 认证时必须配置 floating-ball.admin.auth.bbp.base-url");
        }
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException("protocol or host");
            }
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("user info, query or fragment");
            }
            String path = uri.getPath();
            if (path == null || "/".equals(path)) {
                path = "";
            } else {
                path = path.replaceAll("/+$", "");
            }
            return new URI(scheme, null, uri.getHost(), uri.getPort(), path, null, null).toString();
        } catch (Exception ex) {
            throw new IllegalStateException("BBP 服务地址格式不合法", ex);
        }
    }

    public AdminSecurityProperties.Bbp bbpProperties() {
        return properties.getAuth().getBbp();
    }
}
