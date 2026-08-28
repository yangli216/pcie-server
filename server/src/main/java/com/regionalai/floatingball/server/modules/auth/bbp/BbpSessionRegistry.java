package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class BbpSessionRegistry {

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<String, Session>();

    public void register(String sessionId,
                         String tenantId,
                         BbpCookieJar cookies,
                         long expiresAt) {
        cleanup();
        sessions.put(sessionId, new Session(tenantId, cookies, expiresAt));
    }

    public Session requireSession(String sessionId) {
        cleanup();
        Session session = sessions.get(sessionId);
        if (session == null || session.getExpiresAt() < System.currentTimeMillis()) {
            sessions.remove(sessionId);
            throw new BusinessException("BBP会话已失效，请退出后重新登录");
        }
        return session;
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

    public static class Session {
        private final String tenantId;
        private final BbpCookieJar cookies;
        private final long expiresAt;

        Session(String tenantId, BbpCookieJar cookies, long expiresAt) {
            this.tenantId = tenantId;
            this.cookies = cookies;
            this.expiresAt = expiresAt;
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
    }
}
