package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/** Server-only credentials and token. No patient data is cached or logged. */
@Component
public class HealthPrescriptionClient {
    private final ChronicExaminationProperties properties;
    private final OutboundSecurityService outbound;
    private final ObjectMapper mapper;
    private final RestTemplate http;
    private final Clock clock;
    private CachedToken cached;

    @org.springframework.beans.factory.annotation.Autowired
    public HealthPrescriptionClient(ChronicExaminationProperties properties, OutboundSecurityService outbound, ObjectMapper mapper) {
        this(properties, outbound, mapper, Clock.systemUTC());
    }

    HealthPrescriptionClient(ChronicExaminationProperties properties, OutboundSecurityService outbound, ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.outbound = outbound;
        this.mapper = mapper;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Math.max(1000, Math.min(10000, properties.getConnectTimeoutMs())));
        factory.setReadTimeout(Math.max(1000, Math.min(15000, properties.getReadTimeoutMs())));
        this.http = new RestTemplate(factory);
    }

    public JsonNode query(String idCard) throws Exception {
        if (idCard == null || !idCard.matches("[0-9]{17}[0-9Xx]")) throw new IllegalArgumentException("Invalid identity format");
        Settings settings = settings();
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = token(settings);
            ObjectNode identity = mapper.createObjectNode().put("idTypeCode", "01").put("idNo", idCard);
            ObjectNode body = mapper.createObjectNode().put("content", HealthPrescriptionProtocol.encrypt(identity.toString(), settings.sk)).put("token", token);
            HttpHeaders headers = new HttpHeaders();
            headers.set("token", token);
            headers.set("encrypt", "1");
            try {
                JsonNode response = post(settings.baseUrl + "/api/wjjbgs/cy/getUserInfo", body, headers);
                if (!response.path("isError").isBoolean() || response.path("isError").booleanValue()
                    || !response.path("data").isTextual()) {
                    invalidate(token);
                    throw new IllegalArgumentException("Invalid upstream response");
                }
                return mapper.readTree(HealthPrescriptionProtocol.decrypt(response.path("data").textValue(), settings.sk));
            } catch (HttpClientErrorException ex) {
                int code = ex.getRawStatusCode();
                if (code != 401 && code != 403) throw ex;
                invalidate(token);
                if (attempt == 1) throw ex;
            }
        }
        throw new IllegalStateException("Authentication unavailable");
    }

    private synchronized String token(Settings settings) throws Exception {
        if (cached != null && cached.settings.same(settings) && clock.millis() < cached.expiresAt) return cached.value;
        cached = null;
        String date = HealthPrescriptionProtocol.date(clock.instant());
        ObjectNode login = mapper.createObjectNode().put("type", "simplejson").put("hdservicecode", "tokenservice").put("date", date);
        login.putObject("data").put("ak", settings.ak).put("sign", HealthPrescriptionProtocol.sign(settings.ak, settings.sk, date));
        JsonNode response = post(settings.baseUrl + "/api/login", login, new HttpHeaders());
        JsonNode value = response.path("data").path("token");
        if (!response.path("isError").isBoolean() || response.path("isError").booleanValue()
            || !value.isTextual() || value.textValue().trim().isEmpty() || value.textValue().length() > 8192
            || value.textValue().contains("\r") || value.textValue().contains("\n")) {
            throw new IllegalArgumentException("Authentication unavailable");
        }
        // Legacy integration uses six hours. Refresh conservatively after one hour.
        cached = new CachedToken(settings, value.textValue(), clock.millis() + 3600000L);
        return cached.value;
    }

    private synchronized void invalidate(String value) {
        if (cached != null && cached.value.equals(value)) cached = null;
    }

    private JsonNode post(String url, ObjectNode body, HttpHeaders headers) throws Exception {
        OutboundSecurityService.OutboundCall call = outbound.acquireHttp(url, "健康处方查询");
        try {
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Explicit callbacks avoid RestTemplate's DEBUG/TRACE entity/header logging.
            // Both plaintext login credentials and business token must remain server-private.
            byte[] bytes = mapper.writeValueAsBytes(body);
            JsonNode result = http.execute(call.getUri(), HttpMethod.POST, request -> {
                request.getHeaders().putAll(headers);
                request.getBody().write(bytes);
            }, response -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new IOException("Invalid upstream status");
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int count;
                while ((count = response.getBody().read(chunk)) != -1) {
                    if (buffer.size() + count > 5 * 1024 * 1024) throw new IOException("Upstream response too large");
                    buffer.write(chunk, 0, count);
                }
                JsonNode decoded = mapper.readTree(buffer.toByteArray());
                if (decoded == null || !decoded.isObject()) throw new IOException("Invalid upstream response");
                return decoded;
            });
            call.success();
            return result;
        } catch (Exception ex) {
            // Outbound failure logging must never receive raw HTTP or JSON exception messages.
            call.failure(new IllegalStateException("Health prescription unavailable"));
            throw ex;
        }
    }

    private Settings settings() throws Exception {
        String base = properties.getBaseUrl();
        if (!properties.isEnabled() || base == null || properties.getAk() == null || properties.getAk().trim().isEmpty()
            || properties.getSk() == null || !properties.getSk().matches("[0-9a-fA-F]{32}")) {
            throw new IllegalStateException("Health prescription is not configured");
        }
        URI uri = new URI(base);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Invalid base URL");
        return new Settings(base.endsWith("/") ? base.substring(0, base.length() - 1) : base, properties.getAk(), properties.getSk());
    }

    private static final class Settings {
        final String baseUrl, ak, sk;
        Settings(String baseUrl, String ak, String sk) { this.baseUrl = baseUrl; this.ak = ak; this.sk = sk; }
        boolean same(Settings other) { return Objects.equals(baseUrl, other.baseUrl) && Objects.equals(ak, other.ak) && Objects.equals(sk, other.sk); }
    }
    private static final class CachedToken {
        final Settings settings;
        final String value;
        final long expiresAt;
        CachedToken(Settings settings, String value, long expiresAt) { this.settings = settings; this.value = value; this.expiresAt = expiresAt; }
    }
}
