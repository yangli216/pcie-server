package com.regionalai.floatingball.server.common.outbound;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class OutboundSecurityService {

    private static final Logger log = LoggerFactory.getLogger(OutboundSecurityService.class);

    private final OutboundSecurityProperties properties;
    private final ConcurrentMap<String, HostState> hostStates = new ConcurrentHashMap<String, HostState>();

    public OutboundSecurityService(OutboundSecurityProperties properties) {
        this.properties = properties;
    }

    public URI validateHttpUrl(String rawUrl, String operation) {
        URI uri = parse(rawUrl, operation);
        String scheme = normalizeScheme(uri.getScheme());
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new BusinessException("上游服务地址协议不被允许");
        }
        if ("http".equals(scheme) && !properties.isAllowInsecureHttp()) {
            throw new BusinessException("上游服务地址必须使用 HTTPS");
        }
        return validateUri(uri, operation);
    }

    public URI validateWebSocketUrl(String rawUrl, String operation) {
        URI uri = parse(rawUrl, operation);
        String scheme = normalizeScheme(uri.getScheme());
        if (!"wss".equals(scheme) && !"ws".equals(scheme)) {
            throw new BusinessException("上游实时语音地址协议不被允许");
        }
        if ("ws".equals(scheme) && !properties.isAllowInsecureHttp()) {
            throw new BusinessException("上游实时语音地址必须使用 WSS");
        }
        return validateUri(uri, operation);
    }

    public OutboundCall acquireHttp(String rawUrl, String operation) {
        URI uri = validateHttpUrl(rawUrl, operation);
        return acquire(uri, operation);
    }

    public OutboundCall acquireWebSocket(String rawUrl, String operation) {
        URI uri = validateWebSocketUrl(rawUrl, operation);
        return acquire(uri, operation);
    }

    private OutboundCall acquire(URI uri, String operation) {
        String host = normalizeHost(uri.getHost());
        String stateKey = stateKey(host, operation);
        HostState state = hostStates.computeIfAbsent(stateKey, key -> new HostState());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.circuitOpenedAt > 0L && now - state.circuitOpenedAt < Math.max(1000L, properties.getCircuitOpenMs())) {
                throw new BusinessException("上游服务暂时不可用，请稍后重试");
            }
            if (state.circuitOpenedAt > 0L) {
                state.circuitOpenedAt = 0L;
                state.failureCount = 0;
            }
            if (now - state.windowStartedAt >= 60000L) {
                state.windowStartedAt = now;
                state.requestCount = 0;
            }
            int limit = Math.max(1, properties.getRateLimitPerMinute());
            if (state.requestCount >= limit) {
                throw new BusinessException("上游服务请求过于频繁，请稍后重试");
            }
            state.requestCount++;
        }
        return new OutboundCall(uri, stateKey, host, operation, this);
    }

    private String stateKey(String host, String operation) {
        String normalizedOperation = StringUtils.hasText(operation)
            ? operation.trim().toLowerCase(Locale.ROOT)
            : "unspecified";
        return host + "|" + normalizedOperation;
    }

    private URI validateUri(URI uri, String operation) {
        if (uri.getRawUserInfo() != null) {
            throw new BusinessException("上游服务地址不能包含用户信息");
        }
        String host = normalizeHost(uri.getHost());
        if (!StringUtils.hasText(host)) {
            throw new BusinessException("上游服务地址缺少 host");
        }
        if (!isAllowedHost(host)) {
            log.warn("outbound host rejected by allowlist. operation={}, host={}", operation, host);
            throw new BusinessException("上游服务 host 未在允许名单中：" + host);
        }
        assertResolvableAndPublic(host, operation);
        return uri;
    }

    private URI parse(String rawUrl, String operation) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new BusinessException("上游服务地址不能为空");
        }
        try {
            return new URI(rawUrl.trim());
        } catch (URISyntaxException ex) {
            log.warn("outbound uri parse failed. operation={}, error={}", operation, ex.getMessage());
            throw new BusinessException("上游服务地址格式不合法");
        }
    }

    private void assertResolvableAndPublic(String host, String operation) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception ex) {
            log.warn("outbound host resolve failed. operation={}, host={}, error={}", operation, host, ex.getMessage());
            throw new BusinessException("上游服务 host 无法解析");
        }
        if (addresses == null || addresses.length == 0) {
            throw new BusinessException("上游服务 host 无法解析");
        }
        if (properties.isAllowPrivateNetwork()) {
            return;
        }
        for (InetAddress address : addresses) {
            if (isPrivateOrSpecial(address)) {
                if (properties.isAllowProxyFakeIp() && isProxyFakeIp(address)) {
                    log.info("outbound proxy fake-ip allowed. operation={}, host={}, address={}", operation, host, address.getHostAddress());
                    continue;
                }
                log.warn("outbound private address rejected. operation={}, host={}, address={}", operation, host, address.getHostAddress());
                throw new BusinessException("上游服务地址不能指向本机或私网：" + address.getHostAddress());
            }
        }
    }

    private boolean isAllowedHost(String host) {
        List<String> allowedHosts = normalizeAllowedHosts(properties.getAllowedHosts());
        if (properties.isAllowAllHosts() || allowedHosts.isEmpty()) {
            return true;
        }
        for (String allowedHost : allowedHosts) {
            if (allowedHost.startsWith("*.")) {
                String suffix = allowedHost.substring(1);
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (host.equals(allowedHost)) {
                return true;
            }
        }
        return false;
    }

    private List<String> normalizeAllowedHosts(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            String host = normalizeAllowedHost(value);
            if (StringUtils.hasText(host)) {
                result.add(host);
            }
        }
        return result;
    }

    private String normalizeAllowedHost(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.contains("://")) {
                return normalizeHost(new URI(text).getHost());
            }
        } catch (Exception ignored) {
            return null;
        }
        int slashIndex = text.indexOf('/');
        if (slashIndex >= 0) {
            text = text.substring(0, slashIndex);
        }
        if (text.startsWith("*.")) {
            String suffix = normalizeHost(text.substring(2));
            return StringUtils.hasText(suffix) ? "*." + suffix : null;
        }
        int colonIndex = text.indexOf(':');
        if (colonIndex > 0 && text.indexOf(':', colonIndex + 1) < 0) {
            text = text.substring(0, colonIndex);
        }
        return normalizeHost(text);
    }

    private String normalizeHost(String host) {
        if (!StringUtils.hasText(host)) {
            return null;
        }
        String value = host.trim();
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.indexOf(':') >= 0) {
            return value.toLowerCase(Locale.ROOT);
        }
        return IDN.toASCII(value).toLowerCase(Locale.ROOT);
    }

    private String normalizeScheme(String scheme) {
        return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
    }

    private boolean isPrivateOrSpecial(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            return isPrivateOrSpecialIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return isPrivateOrSpecialIpv6(address.getAddress());
        }
        return true;
    }

    private boolean isPrivateOrSpecialIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0
            || first == 10
            || first == 127
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 192 && second == 0)
            || (first == 198 && (second == 18 || second == 19))
            || first >= 224;
    }

    private boolean isProxyFakeIp(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 198 && (second == 18 || second == 19);
    }

    private boolean isPrivateOrSpecialIpv6(byte[] bytes) {
        int first = bytes[0] & 0xff;
        return (first & 0xfe) == 0xfc
            || Arrays.equals(bytes, new byte[16]);
    }

    private void markSuccess(String stateKey) {
        HostState state = hostStates.get(stateKey);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.failureCount = 0;
            state.circuitOpenedAt = 0L;
        }
    }

    private void markFailure(String stateKey, String host, String operation, Throwable error) {
        HostState state = hostStates.get(stateKey);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.failureCount++;
            if (state.failureCount >= Math.max(1, properties.getCircuitFailureThreshold())) {
                state.circuitOpenedAt = System.currentTimeMillis();
                log.warn("outbound circuit opened. operation={}, host={}, failures={}, error={}",
                    operation, host, state.failureCount, error == null ? "" : error.getMessage());
            }
        }
    }

    private static final class HostState {
        private long windowStartedAt = System.currentTimeMillis();
        private int requestCount;
        private int failureCount;
        private long circuitOpenedAt;
    }

    public static final class OutboundCall {

        private final URI uri;
        private final String stateKey;
        private final String host;
        private final String operation;
        private final OutboundSecurityService owner;
        private boolean completed;

        private OutboundCall(URI uri, String stateKey, String host, String operation,
                             OutboundSecurityService owner) {
            this.uri = uri;
            this.stateKey = stateKey;
            this.host = host;
            this.operation = operation;
            this.owner = owner;
        }

        public URI getUri() {
            return uri;
        }

        public String getUrl() {
            return uri.toString();
        }

        public void success() {
            if (!completed) {
                completed = true;
                owner.markSuccess(stateKey);
            }
        }

        public void failure(Throwable error) {
            if (!completed) {
                completed = true;
                owner.markFailure(stateKey, host, operation, error);
            }
        }
    }
}
