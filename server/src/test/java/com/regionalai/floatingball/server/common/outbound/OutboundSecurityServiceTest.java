package com.regionalai.floatingball.server.common.outbound;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService.OutboundCall;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundSecurityServiceTest {

    @Test
    void validateHttpShouldRejectHostOutsideAllowlist() {
        OutboundSecurityProperties properties = newProperties("example.com");
        properties.setAllowAllHosts(false);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThatThrownBy(() -> service.validateHttpUrl("https://not-allowed.example/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务 host 未在允许名单中：not-allowed.example");
    }

    @Test
    void validateHttpShouldAllowAllHostsWhenExplicitlyEnabled() {
        OutboundSecurityProperties properties = newProperties(null);
        properties.setAllowAllHosts(true);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThat(service.validateHttpUrl("https://1.1.1.1/v1", "test").toString())
            .isEqualTo("https://1.1.1.1/v1");
    }

    @Test
    void validateHttpShouldAllowEmptyAllowlistWhenAllHostsFlagIsDisabled() {
        OutboundSecurityProperties properties = newProperties(null);
        properties.setAllowAllHosts(false);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThat(service.validateHttpUrl("http://10.17.4.31/v1", "test").toString())
            .isEqualTo("http://10.17.4.31/v1");
    }

    @Test
    void allowAllHostsShouldNotBypassInsecureHttpPolicyWhenExplicitlyDisabled() {
        OutboundSecurityProperties properties = newProperties(null);
        properties.setAllowAllHosts(true);
        properties.setAllowInsecureHttp(false);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThatThrownBy(() -> service.validateHttpUrl("http://example.com/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务地址必须使用 HTTPS");
    }

    @Test
    void validateHttpShouldRejectPrivateAddressWhenExplicitlyDisabled() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(false);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThatThrownBy(() -> service.validateHttpUrl("https://127.0.0.1/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务地址不能指向本机或私网：127.0.0.1");
    }

    @Test
    void validateHttpShouldAllowProxyFakeIpWhenExplicitlyEnabled() {
        OutboundSecurityProperties properties = newProperties("198.18.0.55");
        properties.setAllowProxyFakeIp(true);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThat(service.validateHttpUrl("https://198.18.0.55/v1", "test").toString())
            .isEqualTo("https://198.18.0.55/v1");
    }

    @Test
    void validateHttpShouldKeepRejectingPrivateAddressWhenProxyFakeIpEnabled() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(false);
        properties.setAllowProxyFakeIp(true);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThatThrownBy(() -> service.validateHttpUrl("https://127.0.0.1/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务地址不能指向本机或私网：127.0.0.1");
    }

    @Test
    void validateHttpShouldRejectInsecureHttpWhenExplicitlyDisabled() {
        OutboundSecurityProperties properties = newProperties("example.com");
        properties.setAllowInsecureHttp(false);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThatThrownBy(() -> service.validateHttpUrl("http://example.com/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务地址必须使用 HTTPS");
    }

    @Test
    void validateHttpShouldAllowExplicitLocalDevelopmentWhenRelaxed() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(true);
        properties.setAllowInsecureHttp(true);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThat(service.validateHttpUrl("http://127.0.0.1:11434/v1", "test").toString())
            .isEqualTo("http://127.0.0.1:11434/v1");
    }

    @Test
    void acquireShouldRateLimitPerHostAndOperation() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(true);
        properties.setAllowInsecureHttp(true);
        properties.setRateLimitPerMinute(1);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        service.acquireHttp("http://127.0.0.1:11434/v1", "test").success();

        assertThatThrownBy(() -> service.acquireHttp("http://127.0.0.1:11434/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务请求过于频繁，请稍后重试");
    }

    @Test
    void acquireShouldIsolateRateLimitBetweenOperationsOnSameHost() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(true);
        properties.setAllowInsecureHttp(true);
        properties.setRateLimitPerMinute(1);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        service.acquireHttp("http://127.0.0.1:11434/v1", "chat").success();

        assertThat(service.acquireHttp("http://127.0.0.1:11434/v1", "speech"))
            .isNotNull();
    }

    @Test
    void acquireShouldOpenCircuitAfterFailures() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(true);
        properties.setAllowInsecureHttp(true);
        properties.setCircuitFailureThreshold(2);
        properties.setRateLimitPerMinute(10);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        OutboundCall first = service.acquireHttp("http://127.0.0.1:11434/v1", "test");
        first.failure(new RuntimeException("boom-1"));
        OutboundCall second = service.acquireHttp("http://127.0.0.1:11434/v1", "test");
        second.failure(new RuntimeException("boom-2"));

        assertThatThrownBy(() -> service.acquireHttp("http://127.0.0.1:11434/v1", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游服务暂时不可用，请稍后重试");
    }

    @Test
    void acquireShouldIsolateCircuitBetweenOperationsOnSameHost() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowPrivateNetwork(true);
        properties.setAllowInsecureHttp(true);
        properties.setCircuitFailureThreshold(1);
        properties.setRateLimitPerMinute(10);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        service.acquireHttp("http://127.0.0.1:11434/v1", "chat")
            .failure(new RuntimeException("boom"));

        assertThat(service.acquireHttp("http://127.0.0.1:11434/v1", "speech"))
            .isNotNull();
    }

    @Test
    void validateWebSocketShouldAllowOnlySecureSchemeWhenExplicitlyDisabled() {
        OutboundSecurityProperties properties = newProperties("127.0.0.1");
        properties.setAllowInsecureHttp(false);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThatThrownBy(() -> service.validateWebSocketUrl("ws://127.0.0.1/realtime", "test"))
            .isInstanceOf(BusinessException.class)
            .hasMessage("上游实时语音地址必须使用 WSS");
    }

    @Test
    void validateHttpShouldHandleBracketedIpv6Hosts() {
        OutboundSecurityProperties properties = newProperties("[::1]");
        properties.setAllowPrivateNetwork(true);
        OutboundSecurityService service = new OutboundSecurityService(properties);

        assertThat(service.validateHttpUrl("https://[::1]/v1", "test").toString())
            .isEqualTo("https://[::1]/v1");
    }

    private OutboundSecurityProperties newProperties(String allowedHost) {
        OutboundSecurityProperties properties = new OutboundSecurityProperties();
        properties.setAllowedHosts(allowedHost == null ? Collections.<String>emptyList() : Arrays.asList(allowedHost));
        properties.setRateLimitPerMinute(120);
        properties.setCircuitFailureThreshold(5);
        properties.setCircuitOpenMs(30000L);
        return properties;
    }
}
