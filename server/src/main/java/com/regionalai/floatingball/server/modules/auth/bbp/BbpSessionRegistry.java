package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class BbpSessionRegistry {

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    public void register(String sessionId,
                         String tenantId,
                         BbpCookieJar cookies,
                         long expiresAt) {
        register(sessionId, tenantId, cookies, expiresAt, null);
    }

    public void register(String sessionId,
                         String tenantId,
                         BbpCookieJar cookies,
                         long expiresAt,
                         BbpRoleView role) {
        cleanup();
        sessions.put(sessionId, new Session(tenantId, cookies, expiresAt, role));
    }

    public Session requireSession(String sessionId) {
        cleanup();
        Session session = StringUtils.hasText(sessionId) ? sessions.get(sessionId) : null;
        if (session == null || session.getExpiresAt() < System.currentTimeMillis()) {
            if (StringUtils.hasText(sessionId)) {
                sessions.remove(sessionId);
            }
            throw new BusinessException("BBP会话已失效，请退出后重新登录");
        }
        return session;
    }

    public Session requireSession(String sessionId,
                                  String tenantId,
                                  String orgId,
                                  String userId,
                                  String roleId) {
        cleanup();
        Session exact = StringUtils.hasText(sessionId) ? sessions.get(sessionId) : null;
        if (exact != null) {
            return exact;
        }
        Session matching = sessions.entrySet().stream()
            .filter(item -> sameIdentity(item.getValue(), tenantId, orgId, userId, roleId))
            .max(Comparator.comparingLong(item -> item.getValue().getExpiresAt()))
            .map(Map.Entry::getValue)
            .orElse(null);
        if (matching == null) {
            throw new BusinessException("BBP会话已失效，请退出后重新登录");
        }
        if (StringUtils.hasText(sessionId)) {
            sessions.putIfAbsent(sessionId, matching);
        }
        return matching;
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(item -> item.getValue().getExpiresAt() < now);
    }

    private boolean sameIdentity(Session session,
                                 String tenantId,
                                 String orgId,
                                 String userId,
                                 String roleId) {
        if (session == null || session.getRole() == null
            || !StringUtils.hasText(tenantId) || !StringUtils.hasText(orgId)
            || !StringUtils.hasText(userId) || !StringUtils.hasText(roleId)) {
            return false;
        }
        BbpRoleView role = session.getRole();
        return same(session.getTenantId(), tenantId)
            && same(role.getTenantId(), tenantId)
            && same(role.getOrgId(), orgId)
            && same(role.getUserId(), userId)
            && same(role.getRoleId(), roleId);
    }

    private boolean same(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right)
            && left.trim().equals(right.trim());
    }

    public static class Session {
        private final String tenantId;
        private final BbpCookieJar cookies;
        private final long expiresAt;
        private final BbpRoleView role;

        Session(String tenantId, BbpCookieJar cookies, long expiresAt) {
            this(tenantId, cookies, expiresAt, null);
        }

        Session(String tenantId, BbpCookieJar cookies, long expiresAt, BbpRoleView role) {
            this.tenantId = tenantId;
            this.cookies = cookies;
            this.expiresAt = expiresAt;
            this.role = role;
        }

        public String getTenantId() {
            return tenantId;
        }

        public BbpCookieJar getCookies() {
            return cookies;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        public BbpRoleView getRole() {
            return role;
        }
    }
}
