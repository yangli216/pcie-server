package com.regionalai.floatingball.server.modules.phisemr.service;

import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class PhisRecordCache {

    private static final int MAX_RECORDS_PER_SESSION = 2000;
    private static final long SESSION_TTL_MS = 2L * 60L * 60L * 1000L;

    private final ConcurrentMap<String, SessionCache> caches = new ConcurrentHashMap<String, SessionCache>();

    public void putAll(String sessionId, List<PhisEmrRecord> records) {
        if (!StringUtils.hasText(sessionId) || records == null) {
            return;
        }
        SessionCache cache = new SessionCache();
        for (PhisEmrRecord record : records) {
            if (record != null && StringUtils.hasText(record.getIdMedrecdoc())) {
                cache.records.put(record.getIdMedrecdoc(), record);
                if (cache.records.size() > MAX_RECORDS_PER_SESSION) {
                    String eldest = cache.records.keySet().iterator().next();
                    cache.records.remove(eldest);
                }
            }
        }
        caches.put(sessionId, cache);
    }

    public PhisEmrRecord get(String sessionId, String idMedrecdoc) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(idMedrecdoc)) {
            return null;
        }
        SessionCache cache = caches.get(sessionId);
        if (cache == null || System.currentTimeMillis() - cache.updatedAt > SESSION_TTL_MS) {
            caches.remove(sessionId);
            return null;
        }
        return cache.records.get(idMedrecdoc);
    }

    public void removeSession(String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            caches.remove(sessionId);
        }
    }

    private static final class SessionCache {
        private final Map<String, PhisEmrRecord> records = new LinkedHashMap<String, PhisEmrRecord>();
        private final long updatedAt = System.currentTimeMillis();
    }
}
