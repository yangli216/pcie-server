package com.regionalai.floatingball.server.modules.auth.bbp;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BbpCookieJar {

    private final Map<String, String> cookies = new LinkedHashMap<String, String>();

    public synchronized void capture(HttpHeaders headers) {
        List<String> values = headers.get(HttpHeaders.SET_COOKIE);
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String pair = value.split(";", 2)[0];
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = pair.substring(0, separator).trim();
            String cookieValue = pair.substring(separator + 1).trim();
            if (StringUtils.hasText(name)) {
                cookies.put(name, cookieValue);
            }
        }
    }

    public synchronized String headerValue() {
        return cookies.entrySet().stream()
            .map(item -> item.getKey() + "=" + item.getValue())
            .collect(Collectors.joining("; "));
    }

    public synchronized boolean hasNonEmpty(String name) {
        return StringUtils.hasText(cookies.get(name));
    }
}
