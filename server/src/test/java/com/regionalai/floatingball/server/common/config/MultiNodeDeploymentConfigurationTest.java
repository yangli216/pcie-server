package com.regionalai.floatingball.server.common.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiNodeDeploymentConfigurationTest {

    private static final Path MULTI_NODE_DIRECTORY = Paths.get("../deploy/multi-node");
    private static final Path STANDALONE_DIRECTORY = Paths.get("../deploy/standalone");
    private static final Path DOCKER_MULTI_NODE_DIRECTORY = Paths.get("../deploy/docker-multi-node");

    private static final Set<String> LONG_RUNNING_LOCATIONS = new LinkedHashSet<String>(Arrays.asList(
            "= /v1/ai/chat",
            "= /v1/ai/speech/transcribe",
            "= /v1/ai/speech/realtime",
            "= /v1/ai/speech/realtime/ws"
    ));

    @Test
    void nginxKeepsOnePrimaryAndRoutesOnlyFourExactPathsToLongRunningPool() throws Exception {
        String nginx = read("nginx.conf.example");

        String primary = namedBlock(nginx, "upstream", "pcie_primary");
        String longRunning = namedBlock(nginx, "upstream", "pcie_long");
        Map<String, String> locations = locationBlocks(nginx);

        assertEquals(1, activeServerDirectives(primary), "pcie_primary must contain exactly one active server");
        assertTrue(longRunning.contains("least_conn;"), "pcie_long must use least_conn");
        assertTrue(activeServerDirectives(longRunning) >= 2, "pcie_long must demonstrate at least two active nodes");

        Set<String> routedToLongRunningPool = new LinkedHashSet<String>();
        for (Map.Entry<String, String> location : locations.entrySet()) {
            if (location.getValue().contains("proxy_pass http://pcie_long;")) {
                routedToLongRunningPool.add(location.getKey());
            }
        }
        assertEquals(LONG_RUNNING_LOCATIONS, routedToLongRunningPool,
                "only the four exact AI/voice paths may enter pcie_long");

        assertTrue(locations.get("^~ /admin/api/releases").contains("proxy_pass http://pcie_primary;"));
        assertTrue(locations.get("/").contains("proxy_pass http://pcie_primary;"));
    }

    @Test
    void nginxDisablesTransparentRetriesAndPreservesStreamingProtocols() throws Exception {
        Map<String, String> locations = locationBlocks(read("nginx.conf.example"));

        for (Map.Entry<String, String> location : locations.entrySet()) {
            if (location.getValue().contains("proxy_pass ")) {
                assertTrue(location.getValue().contains("proxy_next_upstream off;"),
                        "proxied location must not transparently replay signed requests: " + location.getKey());
            }
        }

        String webSocket = locations.get("= /v1/ai/speech/realtime/ws");
        assertTrue(webSocket.contains("access_log off;"));
        assertTrue(webSocket.contains("proxy_set_header Upgrade $http_upgrade;"));
        assertTrue(webSocket.contains("proxy_set_header Connection $connection_upgrade;"));

        String chat = locations.get("= /v1/ai/chat");
        assertTrue(chat.contains("proxy_buffering off;"), "SSE must not be buffered by Nginx");
    }

    @Test
    void standaloneAndMultiNodeNginxAcceptOnlyCanonicalAiPaths() throws Exception {
        assertCanonicalAiRouteBoundary(STANDALONE_DIRECTORY, "pcie_server");
        assertCanonicalAiRouteBoundary(MULTI_NODE_DIRECTORY, "pcie_long");
    }

    @Test
    void environmentTemplatePinsScaleOutSafetyModesWithoutCredentials() throws Exception {
        Map<String, String> environment = environment(read("pcie-server.env.example"));

        assertEquals("ai-scale-out", environment.get("FB_DEPLOYMENT_MODE"));
        assertEquals("<unique-node-id>", environment.get("FB_NODE_ID"));
        assertEquals("database", environment.get("FB_NONCE_STORE"));
        assertEquals("shared-posix", environment.get("FB_STORAGE_MODE"));
        assertEquals("<same-storage-id-on-every-node>", environment.get("FB_SHARED_STORAGE_ID"));
        assertEquals(
            "<measured-per-node-pool-size>",
            environment.get("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")
        );
        assertEquals(
            "<measured-per-node-minimum-idle>",
            environment.get("SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE")
        );
        assertEquals(
            "<measured-connection-timeout-ms>",
            environment.get("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT")
        );

        for (String sensitiveVariable : Arrays.asList(
                "FB_DB_URL", "FB_DB_USERNAME", "FB_DB_PASSWORD", "FB_AES_KEY")) {
            String value = environment.get(sensitiveVariable);
            assertTrue(isPlaceholder(value), sensitiveVariable + " must remain a non-secret placeholder");
        }
    }

    @Test
    void systemdAndRunbookKeepSharedStorageAndAdmissionGatesExplicit() throws Exception {
        String service = read("pcie-server.service.example");
        String readme = read("README.md");

        assertTrue(service.contains("remote-fs.target"));
        assertTrue(service.contains("RequiresMountsFor=/mnt/pcie-shared"));
        assertTrue(service.contains("ConditionPathIsMountPoint=/mnt/pcie-shared"));
        assertTrue(service.contains("ExecStartPre=/usr/bin/test -d /mnt/pcie-shared/releases"));
        assertTrue(service.contains("ExecStartPre=/usr/bin/test -d /mnt/pcie-shared/speech-audit"));

        assertTrue(readme.contains("/actuator/traffic"));
        assertTrue(readme.contains("/actuator/traffic/drain"));
        assertTrue(readme.contains("同 nonce 双节点门禁"));
        assertTrue(readme.contains("/actuator/health/nonceStore"));
        assertTrue(readme.contains("共享文件门禁"));
        assertTrue(readme.contains("/actuator/health/storage"));
    }

    @Test
    void standaloneNginxPreservesLargeAndLongRunningSpeechRequests() throws Exception {
        String nginx = new String(
            Files.readAllBytes(STANDALONE_DIRECTORY.resolve("nginx.conf.example")),
            StandardCharsets.UTF_8
        );
        Map<String, String> locations = locationBlocks(nginx);

        for (String path : Arrays.asList(
                "= /v1/ai/speech/transcribe",
                "= /v1/ai/speech/realtime")) {
            String location = locations.get(path);
            assertTrue(location != null, "missing standalone speech location: " + path);
            assertTrue(location.contains("proxy_pass http://pcie_server;"));
            assertTrue(location.contains("client_max_body_size 2048m;"));
            assertTrue(location.contains("proxy_read_timeout 600s;"));
            assertTrue(location.contains("proxy_send_timeout 600s;"));
            assertTrue(location.contains("proxy_next_upstream off;"));
        }
    }

    @Test
    void dockerComposeDefinesOneCompleteIsolatedTestStack() throws Exception {
        Map<String, Object> compose = dockerCompose();
        Map<String, Object> services = section(compose, "services");

        assertEquals("pcie-docker-multi-node-test", String.valueOf(compose.get("name")));
        assertEquals(
            new LinkedHashSet<String>(Arrays.asList(
                "opengauss",
                "schema-init",
                "storage-init",
                "pcie-primary",
                "pcie-capacity",
                "nginx"
            )),
            services.keySet()
        );
        assertEquals(
            new LinkedHashSet<String>(Arrays.asList("pcie-db-data", "pcie-shared")),
            section(compose, "volumes").keySet()
        );
        assertEquals(
            new LinkedHashSet<String>(Arrays.asList("pcie-test")),
            section(compose, "networks").keySet()
        );
        assertEquals("bridge", String.valueOf(section(section(compose, "networks"), "pcie-test").get("driver")));
        for (String serviceName : services.keySet()) {
            assertTrue(strings(section(services, serviceName).get("networks")).contains("pcie-test"),
                "service must join the isolated Compose network: " + serviceName);
        }
    }

    @Test
    void dockerComposeInitializesDedicatedOpenGaussSchemaBeforeStartingApplications() throws Exception {
        Map<String, Object> services = section(dockerCompose(), "services");
        Map<String, Object> database = section(services, "opengauss");
        Map<String, Object> schemaInit = section(services, "schema-init");
        Map<String, Object> databaseEnvironment = section(database, "environment");
        Map<String, Object> schemaEnvironment = section(schemaInit, "environment");

        assertEquals("${OPENGAUSS_IMAGE:-enmotech/opengauss-lite:5.0.3}", String.valueOf(database.get("image")));
        assertEquals("linux/arm64", String.valueOf(database.get("platform")));
        assertEquals(
            new LinkedHashSet<String>(Arrays.asList("127.0.0.1:15433:5432")),
            new LinkedHashSet<String>(strings(database.get("ports")))
        );
        assertTrue(strings(database.get("volumes")).contains("pcie-db-data:/var/lib/opengauss/data"));
        assertTrue(strings(database.get("volumes")).contains(
            "./prepare-database.sh:/docker-entrypoint-initdb.d/010-prepare-database.sh:ro"
        ));
        assertTrue(String.valueOf(databaseEnvironment.get("GS_PASSWORD")).contains(":?set PCIE_DB_PASSWORD"));
        assertEquals("service_healthy", dependencyCondition(schemaInit, "opengauss"));
        assertEquals("no", String.valueOf(schemaInit.get("restart")));
        assertEquals(database.get("image"), schemaInit.get("image"));
        assertEquals(database.get("platform"), schemaInit.get("platform"));

        assertEquals("opengauss", String.valueOf(schemaEnvironment.get("PCIE_DB_HOST")));
        assertEquals("5432", String.valueOf(schemaEnvironment.get("PCIE_DB_PORT")));
        assertEquals(databaseEnvironment.get("GS_DB"), schemaEnvironment.get("PCIE_DB_NAME"));
        assertEquals(databaseEnvironment.get("GS_USERNAME"), schemaEnvironment.get("PCIE_DB_USERNAME"));
        assertEquals(databaseEnvironment.get("GS_PASSWORD"), schemaEnvironment.get("PCIE_DB_PASSWORD"));
        assertFalse(schemaEnvironment.containsKey("PCIE_DB_ADMIN_USERNAME"));
        assertFalse(schemaEnvironment.containsKey("PCIE_DB_ADMIN_PASSWORD"));
        assertEquals("/opt/pcie/init.sql", String.valueOf(schemaEnvironment.get("PCIE_SCHEMA_SQL")));
        assertTrue(strings(schemaInit.get("entrypoint")).contains("/opt/pcie/init-schema.sh"));
        assertTrue(strings(schemaInit.get("volumes")).contains("./init-schema.sh:/opt/pcie/init-schema.sh:ro"));
        assertTrue(strings(schemaInit.get("volumes")).contains(
            "../../server/src/main/resources/sql/gaussdb/init.sql:/opt/pcie/init.sql:ro"
        ));

        String initScript = read(DOCKER_MULTI_NODE_DIRECTORY, "init-schema.sh");
        for (String variable : Arrays.asList(
                "PCIE_DB_HOST",
                "PCIE_DB_PORT",
                "PCIE_DB_NAME",
                "PCIE_DB_USERNAME",
                "PCIE_DB_PASSWORD",
                "PCIE_SCHEMA_SQL")) {
            assertTrue(initScript.contains(variable), "schema initializer must consume " + variable);
        }
        assertTrue(initScript.contains("ON_ERROR_STOP=1"));
        assertTrue(initScript.contains("-f \"$PCIE_SCHEMA_SQL\""));
        assertTrue(initScript.contains("c_ai_docker_schema_state"));
        assertTrue(initScript.contains("docker_full_init_v1"));
        assertTrue(initScript.contains("c_ai_device"));
        assertTrue(initScript.contains("c_ai_request_nonce"));
        assertFalse(initScript.contains("c_ai_schema_migration"));
        assertFalse(initScript.contains("feature_event_minimization_v1"));
        assertFalse(initScript.contains("PCIE_DB_ADMIN_USERNAME"));
        assertFalse(initScript.contains("PCIE_DB_ADMIN_PASSWORD"));

        String databasePreparation = read(DOCKER_MULTI_NODE_DIRECTORY, "prepare-database.sh");
        assertTrue(databasePreparation.contains("GS_DB"));
        assertTrue(databasePreparation.contains("GS_USERNAME"));
        assertTrue(databasePreparation.contains("GS_PASSWORD"));
        assertTrue(databasePreparation.contains("-U omm"));
        assertTrue(databasePreparation.contains("ON_ERROR_STOP=1"));
        assertTrue(databasePreparation.contains("ALTER DATABASE"));
        assertTrue(databasePreparation.contains("ALTER SCHEMA public"));

        for (String application : Arrays.asList("pcie-primary", "pcie-capacity")) {
            Map<String, Object> service = section(services, application);
            Map<String, Object> environment = section(service, "environment");
            assertEquals("service_healthy", dependencyCondition(service, "opengauss"));
            assertEquals("service_completed_successfully", dependencyCondition(service, "schema-init"));
            assertEquals(databaseEnvironment.get("GS_USERNAME"), environment.get("FB_DB_USERNAME"));
            assertEquals(databaseEnvironment.get("GS_PASSWORD"), environment.get("FB_DB_PASSWORD"));
            assertTrue(String.valueOf(environment.get("FB_DB_URL")).contains("opengauss:5432/"));
            assertTrue(String.valueOf(environment.get("FB_DB_URL")).contains(
                "/" + databaseEnvironment.get("GS_DB") + "?"
            ));
            assertFalse(String.valueOf(environment.get("FB_DB_URL")).contains("127.0.0.1"));
            assertFalse(String.valueOf(environment.get("FB_DB_URL")).contains("host.docker.internal"));
        }
    }

    @Test
    void dockerComposePinsUniqueScaleOutNodesToDatabaseNonceAndSharedPosix() throws Exception {
        Map<String, Object> services = section(dockerCompose(), "services");
        Map<String, Object> primary = section(services, "pcie-primary");
        Map<String, Object> capacity = section(services, "pcie-capacity");
        Map<String, Object> primaryEnvironment = section(primary, "environment");
        Map<String, Object> capacityEnvironment = section(capacity, "environment");

        assertEquals(primary.get("image"), capacity.get("image"));
        assertEquals(primary.get("build"), capacity.get("build"));
        assertEquals("gaussdb", String.valueOf(primaryEnvironment.get("SPRING_PROFILES_ACTIVE")));
        assertEquals("gaussdb", String.valueOf(capacityEnvironment.get("SPRING_PROFILES_ACTIVE")));
        assertEquals("pcie-primary", String.valueOf(primaryEnvironment.get("FB_NODE_ID")));
        assertEquals("pcie-capacity", String.valueOf(capacityEnvironment.get("FB_NODE_ID")));
        assertFalse(primaryEnvironment.get("FB_NODE_ID").equals(capacityEnvironment.get("FB_NODE_ID")));

        Set<String> expectedHikariArguments = setOf(
            "--spring.datasource.hikari.maximum-pool-size=${PCIE_DB_POOL_MAX_SIZE:-10}",
            "--spring.datasource.hikari.minimum-idle=${PCIE_DB_POOL_MIN_IDLE:-2}",
            "--spring.datasource.hikari.connection-timeout=${PCIE_DB_CONNECTION_TIMEOUT_MS:-10000}"
        );
        for (String application : Arrays.asList("pcie-primary", "pcie-capacity")) {
            Map<String, Object> service = section(services, application);
            Map<String, Object> environment = section(service, "environment");
            assertEquals("ai-scale-out", String.valueOf(environment.get("FB_DEPLOYMENT_MODE")));
            assertEquals("database", String.valueOf(environment.get("FB_NONCE_STORE")));
            assertEquals("shared-posix", String.valueOf(environment.get("FB_STORAGE_MODE")));
            assertEquals("false", String.valueOf(environment.get("FB_ADMIN_BOOTSTRAP_RESET_ENABLED")));
            assertFalse(environment.containsKey("FB_FEATURE_EVENT_SCHEMA_VALIDATION_ENABLED"));
            assertEquals("0.0.0.0", String.valueOf(environment.get("FB_MANAGEMENT_ADDRESS")));
            assertEquals("8081", String.valueOf(environment.get("FB_MANAGEMENT_PORT")));
            assertEquals(expectedHikariArguments, new LinkedHashSet<String>(strings(service.get("command"))));
            assertFalse(environment.containsKey("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"));
            assertFalse(environment.containsKey("SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE"));
            assertFalse(environment.containsKey("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT"));
        }
        assertEquals(primaryEnvironment.get("FB_SHARED_STORAGE_ID"), capacityEnvironment.get("FB_SHARED_STORAGE_ID"));
        assertFalse(String.valueOf(primaryEnvironment.get("FB_SHARED_STORAGE_ID")).trim().isEmpty());
    }

    @Test
    void dockerComposeSharesOneNamedPosixVolumeAndInitializesStorageMarkers() throws Exception {
        Map<String, Object> services = section(dockerCompose(), "services");
        Map<String, Object> storageInit = section(services, "storage-init");
        Map<String, Object> storageEnvironment = section(storageInit, "environment");
        String storageCommand = strings(storageInit.get("command")).get(0);

        assertEquals("no", String.valueOf(storageInit.get("restart")));
        assertTrue(strings(storageInit.get("volumes")).contains("pcie-shared:/mnt/pcie-shared"));
        assertTrue(storageCommand.contains("releases speech-audit"));
        assertTrue(storageCommand.contains(".pcie-storage-id"));

        for (String application : Arrays.asList("pcie-primary", "pcie-capacity")) {
            Map<String, Object> service = section(services, application);
            Map<String, Object> environment = section(service, "environment");
            assertEquals("service_completed_successfully", dependencyCondition(service, "storage-init"));
            assertTrue(strings(service.get("volumes")).contains("pcie-shared:/mnt/pcie-shared"));
            assertEquals("/mnt/pcie-shared/releases", String.valueOf(environment.get("FB_RELEASE_STORAGE_DIR")));
            assertEquals(
                "/mnt/pcie-shared/speech-audit",
                String.valueOf(environment.get("FB_AUDIT_SPEECH_FILE_DIR"))
            );
            assertEquals(storageEnvironment.get("PCIE_SHARED_STORAGE_ID"), environment.get("FB_SHARED_STORAGE_ID"));
        }
    }

    @Test
    void dockerComposePublishesOnlyExpectedLoopbackPorts() throws Exception {
        Map<String, Object> services = section(dockerCompose(), "services");
        Map<String, Set<String>> expectedPorts = new LinkedHashMap<String, Set<String>>();
        expectedPorts.put("opengauss", setOf("127.0.0.1:15433:5432"));
        expectedPorts.put("pcie-primary", setOf("127.0.0.1:18101:8080", "127.0.0.1:19101:8081"));
        expectedPorts.put("pcie-capacity", setOf("127.0.0.1:18102:8080", "127.0.0.1:19102:8081"));
        expectedPorts.put("nginx", setOf("127.0.0.1:18000:8080"));

        for (Map.Entry<String, Set<String>> expected : expectedPorts.entrySet()) {
            Set<String> actual = new LinkedHashSet<String>(strings(section(services, expected.getKey()).get("ports")));
            assertEquals(expected.getValue(), actual, "unexpected published ports for " + expected.getKey());
            for (String port : actual) {
                assertTrue(port.startsWith("127.0.0.1:"), "host port must stay on loopback: " + port);
            }
        }
        assertFalse(section(services, "schema-init").containsKey("ports"));
        assertFalse(section(services, "storage-init").containsKey("ports"));

        String nginx = read(DOCKER_MULTI_NODE_DIRECTORY, "nginx.conf");
        assertFalse(nginx.contains("/actuator"));
        assertFalse(nginx.contains(":8081"));
    }

    @Test
    void dockerNginxKeepsOnePrimaryAndNeverReplaysSignedRequests() throws Exception {
        String nginx = read(DOCKER_MULTI_NODE_DIRECTORY, "nginx.conf");
        String primary = namedBlock(nginx, "upstream", "pcie_primary");
        String longRunning = namedBlock(nginx, "upstream", "pcie_long");
        Map<String, String> locations = locationBlocks(nginx);

        assertEquals(1, activeServerDirectives(primary));
        assertTrue(primary.contains("server pcie-primary:8080"));
        assertFalse(primary.contains("pcie-capacity:8080"));
        assertEquals(2, activeServerDirectives(longRunning));
        assertTrue(longRunning.contains("least_conn;"));
        assertTrue(longRunning.contains("server pcie-primary:8080"));
        assertTrue(longRunning.contains("server pcie-capacity:8080"));

        Set<String> routedToLongRunningPool = new LinkedHashSet<String>();
        for (Map.Entry<String, String> location : locations.entrySet()) {
            if (location.getValue().contains("proxy_pass http://pcie_long;")) {
                routedToLongRunningPool.add(location.getKey());
            }
            if (location.getValue().contains("proxy_pass ")) {
                assertTrue(location.getValue().contains("proxy_next_upstream off;"),
                    "Docker Nginx must not replay a signed request: " + location.getKey());
            }
        }
        assertEquals(LONG_RUNNING_LOCATIONS, routedToLongRunningPool);
        assertTrue(locations.get("^~ /admin/api/releases").contains("proxy_pass http://pcie_primary;"));
        assertTrue(locations.get("/").contains("proxy_pass http://pcie_primary;"));
        assertTrue(locations.get("= /v1/ai/chat").contains("proxy_buffering off;"));
        assertTrue(locations.get("= /v1/ai/speech/realtime/ws").contains(
            "proxy_set_header Upgrade $http_upgrade;"
        ));
        assertCanonicalAiRouteBoundary(DOCKER_MULTI_NODE_DIRECTORY, "pcie_long", "nginx.conf");
    }

    @Test
    void dockerImageRunsThePackagedJarAsAConfiguredNonRootUser() throws Exception {
        Map<String, Object> services = section(dockerCompose(), "services");
        Map<String, Object> primary = section(services, "pcie-primary");
        Map<String, Object> build = section(primary, "build");
        String dockerfile = read(DOCKER_MULTI_NODE_DIRECTORY, "Dockerfile");

        assertEquals("../..", String.valueOf(build.get("context")));
        assertEquals("deploy/docker-multi-node/Dockerfile", String.valueOf(build.get("dockerfile")));
        assertTrue(dockerfile.contains("server/target/pcie-server-*.jar"));
        assertTrue(dockerfile.contains("USER ${PCIE_UID}:${PCIE_GID}"));
        assertTrue(dockerfile.contains("EXPOSE 8080 8081"));
        assertTrue(dockerfile.contains("ENTRYPOINT [\"java\", \"-jar\", \"/opt/pcie-server/pcie-server.jar\"]"));
    }

    private String read(String filename) throws IOException {
        return read(MULTI_NODE_DIRECTORY, filename);
    }

    private String read(Path directory, String filename) throws IOException {
        return new String(Files.readAllBytes(directory.resolve(filename)), StandardCharsets.UTF_8);
    }

    private void assertCanonicalAiRouteBoundary(Path directory, String expectedUpstream) throws IOException {
        assertCanonicalAiRouteBoundary(directory, expectedUpstream, "nginx.conf.example");
    }

    private void assertCanonicalAiRouteBoundary(
        Path directory,
        String expectedUpstream,
        String filename
    ) throws IOException {
        String nginx = read(directory, filename);
        Map<String, String> locations = locationBlocks(nginx);

        assertTrue(nginx.contains("merge_slashes off;"),
                "duplicate-slash variants must remain visible to the reject rule");
        for (String canonicalLocation : LONG_RUNNING_LOCATIONS) {
            String canonicalPath = canonicalLocation.substring(2);
            assertEquals(canonicalLocation, selectedLocationSelector(locations, canonicalPath));
            String block = locations.get(canonicalLocation);
            assertTrue(block.contains("proxy_pass http://" + expectedUpstream + ";"));
            assertFalse(block.contains("return 404;"));
        }

        String catchAllSelector = selectedLocationSelector(locations, "/v1/ai/not-canonical");
        assertTrue(catchAllSelector.startsWith("~ "), "non-canonical AI paths need a regex catch-all");
        String catchAll = locations.get(catchAllSelector);
        assertTrue(catchAll.contains("access_log off;"));
        assertTrue(catchAll.contains("return 404;"));
        assertFalse(catchAll.contains("proxy_pass "));

        for (String variant : Arrays.asList(
                "/v1/ai/chat/",
                "/v1/ai/chat;variant=1",
                "/v1/ai/chat/extra",
                "/v1/ai/speech/transcribe/",
                "/v1/ai/speech/realtime/extra",
                "/v1/ai/speech/realtime/ws/",
                "/v1/ai/speech/realtime/ws;variant=1",
                "/v1/ai/speech/realtime/ws/extra",
                "/v1//ai/chat",
                "//v1/ai/chat")) {
            assertEquals(catchAllSelector, selectedLocationSelector(locations, variant),
                    "AI path variant must be rejected rather than proxied: " + variant);
        }

        String canonicalWebSocket = selectedLocationSelector(
                locations,
                "/v1/ai/speech/realtime/ws?token=secret&nonce=nonce&sig=signature"
        );
        assertEquals("= /v1/ai/speech/realtime/ws", canonicalWebSocket);
        assertTrue(locations.get(canonicalWebSocket).contains("access_log off;"));

        for (String webSocketVariant : Arrays.asList(
                "/v1/ai/speech/realtime/ws/?token=secret&nonce=nonce&sig=signature",
                "/v1/ai/speech/realtime/ws;variant=1?token=secret&nonce=nonce&sig=signature",
                "/v1/ai/speech/realtime/ws/extra?token=secret&nonce=nonce&sig=signature")) {
            String selector = selectedLocationSelector(locations, webSocketVariant);
            assertEquals(catchAllSelector, selector);
            assertTrue(locations.get(selector).contains("access_log off;"),
                    "WebSocket variants must not log signed query parameters");
        }
    }

    private String namedBlock(String source, String directive, String name) {
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(directive)
                + "\\s+" + Pattern.quote(name) + "\\s*\\{");
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(), "missing " + directive + " block: " + name);
        return bracedBlock(source, matcher.end() - 1);
    }

    private Map<String, String> locationBlocks(String nginx) {
        Pattern pattern = Pattern.compile("(?m)^\\s*location\\s+([^\\{]+?)\\s*\\{");
        Matcher matcher = pattern.matcher(nginx);
        Map<String, String> locations = new LinkedHashMap<String, String>();
        while (matcher.find()) {
            locations.put(matcher.group(1).trim(), bracedBlock(nginx, matcher.end() - 1));
        }
        return locations;
    }

    private String selectedLocationSelector(Map<String, String> locations, String requestTarget) {
        String path = requestTarget;
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }

        String exactSelector = "= " + path;
        if (locations.containsKey(exactSelector)) {
            return exactSelector;
        }
        for (String selector : locations.keySet()) {
            if (!selector.startsWith("~ ")) {
                continue;
            }
            String expression = selector.substring(2).trim();
            if (expression.length() >= 2 && expression.startsWith("\"") && expression.endsWith("\"")) {
                expression = expression.substring(1, expression.length() - 1);
            }
            if (Pattern.compile(expression).matcher(path).find()) {
                return selector;
            }
        }
        return "/";
    }

    private String bracedBlock(String source, int openingBrace) {
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openingBrace + 1, index);
                }
            }
        }
        throw new AssertionError("unterminated block starting at character " + openingBrace);
    }

    private int activeServerDirectives(String upstream) {
        int count = 0;
        for (String line : upstream.split("\\R")) {
            if (line.trim().startsWith("server ")) {
                count++;
            }
        }
        return count;
    }

    private Map<String, String> environment(String source) {
        Map<String, String> environment = new LinkedHashMap<String, String>();
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                environment.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
            }
        }
        return environment;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dockerCompose() throws IOException {
        try (InputStream input = Files.newInputStream(DOCKER_MULTI_NODE_DIRECTORY.resolve("compose.yml"))) {
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map, "missing map section: " + key);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> values(Object value) {
        assertTrue(value instanceof List, "expected list but was: " + value);
        return (List<Object>) value;
    }

    private List<String> strings(Object value) {
        List<String> result = new java.util.ArrayList<String>();
        for (Object item : values(value)) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private String dependencyCondition(Map<String, Object> service, String dependency) {
        return String.valueOf(section(section(service, "depends_on"), dependency).get("condition"));
    }

    private Set<String> setOf(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private boolean isPlaceholder(String value) {
        return value != null && value.length() > 2 && value.startsWith("<") && value.endsWith(">");
    }
}
