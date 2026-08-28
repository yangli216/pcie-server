package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Component
public class BbpAdminScope {

    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String ORG_ADMIN = "ORG_ADMIN";

    public boolean isSystemAdmin(AdminCurrentUser currentUser) {
        List<String> roles = currentUser == null || currentUser.getRoles() == null
            ? Collections.<String>emptyList() : currentUser.getRoles();
        return roles.contains(SYSTEM_ADMIN);
    }

    public boolean canManageAiPermissions(AdminCurrentUser currentUser) {
        List<String> roles = currentUser == null || currentUser.getRoles() == null
            ? Collections.<String>emptyList() : currentUser.getRoles();
        return roles.contains(SYSTEM_ADMIN) || roles.contains(ORG_ADMIN);
    }

    public void requireSystemAdmin(AdminCurrentUser currentUser) {
        requireTenantId(currentUser);
        if (!isSystemAdmin(currentUser)) {
            throw new BusinessException("仅系统管理员可以维护 PCIE 后台访问权限");
        }
    }

    public void requireAiPermissionWriteAccess(AdminCurrentUser currentUser) {
        requireTenantId(currentUser);
        if (!canManageAiPermissions(currentUser)) {
            throw new BusinessException("当前账号仅可查看本机构 AI 使用权限，无权授予或撤销");
        }
    }

    public String requireTenantId(AdminCurrentUser currentUser) {
        if (currentUser == null || !"BBP".equals(currentUser.getAuthProvider())
            || !StringUtils.hasText(currentUser.getBbpTenantId())) {
            throw new BusinessException("当前登录缺少有效的 BBP 租户范围");
        }
        return currentUser.getBbpTenantId().trim();
    }

    public String requireOrgAccess(AdminCurrentUser currentUser, String requestedOrgId) {
        requireTenantId(currentUser);
        if (!StringUtils.hasText(requestedOrgId)) {
            throw new BusinessException("BBP机构 ID 不能为空");
        }
        String normalized = requestedOrgId.trim();
        if (isSystemAdmin(currentUser)) {
            return normalized;
        }
        if (!StringUtils.hasText(currentUser.getBbpOrgId())) {
            throw new BusinessException("当前 BBP 登录角色缺少机构范围，无法管理人员权限");
        }
        if (!normalized.equals(currentUser.getBbpOrgId().trim())) {
            throw new BusinessException("当前账号无权访问其他机构人员");
        }
        return normalized;
    }
}
