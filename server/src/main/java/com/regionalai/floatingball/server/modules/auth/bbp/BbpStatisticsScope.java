package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Component
public class BbpStatisticsScope {

    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String ORG_ADMIN = "ORG_ADMIN";
    public static final String ORG_ANALYST = "ORG_ANALYST";

    public Scope resolve(AdminCurrentUser currentUser, String requestedHisOrgId) {
        if (!isBbpOrganizationScoped(currentUser)) {
            return Scope.unrestricted(requestedHisOrgId);
        }
        if (!StringUtils.hasText(currentUser.getBbpOrgId())) {
            throw new ForbiddenException("AUTH-403", "当前 BBP 登录角色缺少机构范围，无法查看统计数据");
        }
        String orgId = currentUser.getBbpOrgId().trim();
        if (StringUtils.hasText(requestedHisOrgId) && !orgId.equals(requestedHisOrgId.trim())) {
            throw new ForbiddenException("AUTH-403", "当前账号无权查看其他机构统计数据");
        }
        return Scope.restricted(orgId, currentUser.getBbpOrgName());
    }

    public boolean isStatisticsOnly(AdminCurrentUser currentUser) {
        List<String> roles = roles(currentUser);
        return currentUser != null
            && "BBP".equals(currentUser.getAuthProvider())
            && roles.contains(ORG_ANALYST)
            && !roles.contains(SYSTEM_ADMIN)
            && !roles.contains(ORG_ADMIN);
    }

    public boolean isAllowedStatisticsApi(String uri) {
        return "/admin/api/auth/me".equals(uri)
            || "/admin/api/auth/logout".equals(uri)
            || uri.startsWith("/admin/api/analytics/")
            || uri.startsWith("/admin/api/xiaoshan-analytics/")
            || uri.startsWith("/admin/api/user-activity/");
    }

    private boolean isBbpOrganizationScoped(AdminCurrentUser currentUser) {
        return currentUser != null
            && "BBP".equals(currentUser.getAuthProvider())
            && !roles(currentUser).contains(SYSTEM_ADMIN);
    }

    private List<String> roles(AdminCurrentUser currentUser) {
        return currentUser == null || currentUser.getRoles() == null
            ? Collections.<String>emptyList() : currentUser.getRoles();
    }

    public static final class Scope {
        private final boolean restricted;
        private final String hisOrgId;
        private final String hisOrgName;

        private Scope(boolean restricted, String hisOrgId, String hisOrgName) {
            this.restricted = restricted;
            this.hisOrgId = hisOrgId;
            this.hisOrgName = hisOrgName;
        }

        static Scope unrestricted(String requestedHisOrgId) {
            return new Scope(false, requestedHisOrgId, null);
        }

        static Scope restricted(String hisOrgId, String hisOrgName) {
            return new Scope(true, hisOrgId, hisOrgName);
        }

        public boolean isRestricted() {
            return restricted;
        }

        public String getHisOrgId() {
            return hisOrgId;
        }

        public String getHisOrgName() {
            return hisOrgName;
        }
    }
}
