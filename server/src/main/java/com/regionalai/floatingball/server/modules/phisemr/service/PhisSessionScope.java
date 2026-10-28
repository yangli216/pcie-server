package com.regionalai.floatingball.server.modules.phisemr.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpSessionRegistry;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class PhisSessionScope {

    private final AdminAuthMode authMode;
    private final BbpSessionRegistry sessionRegistry;

    public PhisSessionScope(AdminAuthMode authMode, BbpSessionRegistry sessionRegistry) {
        this.authMode = authMode;
        this.sessionRegistry = sessionRegistry;
    }

    public Scope describe(AdminCurrentUser currentUser) {
        Scope scope = new Scope();
        if (!authMode.isBbp() || currentUser == null || !"BBP".equals(currentUser.getAuthProvider())) {
            scope.setAvailable(false);
            scope.setUnavailableCode("EMR-PHIS-SESSION");
            scope.setUnavailableReason("门诊病历质控仅支持 BBP 认证模式，请使用 BBP 账号登录后台");
            return scope;
        }
        BbpSessionRegistry.Session session;
        try {
            session = sessionRegistry.requireSession(
                currentUser.getAuthSessionId(),
                currentUser.getBbpTenantId(),
                currentUser.getBbpOrgId(),
                currentUser.getBbpUserId(),
                currentUser.getBbpRoleId());
        } catch (BusinessException ex) {
            scope.setAvailable(false);
            scope.setUnavailableCode("EMR-PHIS-SESSION");
            scope.setUnavailableReason("PHIS会话已失效，请重新登录后台");
            return scope;
        }
        BbpRoleView role = session.getRole();
        String hisOrgId = role == null ? null : firstText(role.getOrgCode(), role.getOrgId());
        if (!StringUtils.hasText(hisOrgId)) {
            hisOrgId = firstText(currentUser.getBbpOrgId());
        }
        scope.setAvailable(true);
        scope.setSession(session);
        scope.setHisOrgId(hisOrgId);
        scope.setHisOrgName(role == null
            ? currentUser.getBbpOrgName()
            : firstText(role.getOrgName(), currentUser.getBbpOrgName()));
        scope.setDeptId(role == null ? null : role.getDepartmentId());
        scope.setDeptName(role == null ? null : role.getDepartmentName());
        scope.setDoctorName(role == null
            ? firstText(currentUser.getNaUser())
            : firstText(role.getUserName(), currentUser.getNaUser()));
        scope.setRoleName(role == null ? null : role.getRoleName());
        scope.setSessionExpiresAt(LocalDateTime.ofInstant(
            Instant.ofEpochMilli(session.getExpiresAt()), ZoneId.systemDefault()));
        return scope;
    }

    public Scope require(AdminCurrentUser currentUser) {
        if (!authMode.isBbp()) {
            throw new ForbiddenException("AUTH-403", "门诊病历质控仅在 BBP 认证模式下可用");
        }
        if (isStatisticsOnlyUser(currentUser)) {
            throw new ForbiddenException("AUTH-403", "机构统计专用账号无权访问门诊病历质控");
        }
        Scope scope = describe(currentUser);
        if (scope.isAvailable()) {
            return scope;
        }
        throw new ConflictException(scope.getUnavailableCode(), scope.getUnavailableReason());
    }

    private boolean isStatisticsOnlyUser(AdminCurrentUser currentUser) {
        if (currentUser == null || currentUser.getRoles() == null) {
            return false;
        }
        return currentUser.getRoles().contains("ORG_ANALYST")
            && !currentUser.getRoles().contains("SYSTEM_ADMIN")
            && !currentUser.getRoles().contains("ORG_ADMIN");
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public static class Scope {
        private boolean available;
        private BbpSessionRegistry.Session session;
        private String hisOrgId;
        private String hisOrgName;
        private String deptId;
        private String deptName;
        private String doctorName;
        private String roleName;
        private LocalDateTime sessionExpiresAt;
        private String unavailableCode;
        private String unavailableReason;

        public boolean isAvailable() {
            return available;
        }

        public void setAvailable(boolean available) {
            this.available = available;
        }

        public BbpSessionRegistry.Session getSession() {
            return session;
        }

        public void setSession(BbpSessionRegistry.Session session) {
            this.session = session;
        }

        public String getHisOrgId() {
            return hisOrgId;
        }

        public void setHisOrgId(String hisOrgId) {
            this.hisOrgId = hisOrgId;
        }

        public String getHisOrgName() {
            return hisOrgName;
        }

        public void setHisOrgName(String hisOrgName) {
            this.hisOrgName = hisOrgName;
        }

        public String getDeptId() {
            return deptId;
        }

        public void setDeptId(String deptId) {
            this.deptId = deptId;
        }

        public String getDeptName() {
            return deptName;
        }

        public void setDeptName(String deptName) {
            this.deptName = deptName;
        }

        public String getDoctorName() {
            return doctorName;
        }

        public void setDoctorName(String doctorName) {
            this.doctorName = doctorName;
        }

        public String getRoleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
        }

        public LocalDateTime getSessionExpiresAt() {
            return sessionExpiresAt;
        }

        public void setSessionExpiresAt(LocalDateTime sessionExpiresAt) {
            this.sessionExpiresAt = sessionExpiresAt;
        }

        public String getUnavailableCode() {
            return unavailableCode;
        }

        public void setUnavailableCode(String unavailableCode) {
            this.unavailableCode = unavailableCode;
        }

        public String getUnavailableReason() {
            return unavailableReason;
        }

        public void setUnavailableReason(String unavailableReason) {
            this.unavailableReason = unavailableReason;
        }
    }
}
