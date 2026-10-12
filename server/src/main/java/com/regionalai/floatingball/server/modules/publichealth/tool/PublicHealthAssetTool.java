package com.regionalai.floatingball.server.modules.publichealth.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityProperties;
import com.regionalai.floatingball.server.common.outbound.OutboundSecurityService;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import com.regionalai.floatingball.server.modules.publichealth.service.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Explicit offline operator entry point. Never starts Spring or approves assets. */
public final class PublicHealthAssetTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_ASSET_BYTES = 64L * 1024 * 1024;

    private PublicHealthAssetTool() { }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println("build --input FILE --output NEW_DIR [--batch-size 16]\n"
                    + "retrieve --recipes FILE --queries FILE --output NEW_DIR [--candidates 20]\n"
                    + "Connection: FB_PH_INDEX_BASE_URL, FB_PH_INDEX_API_KEY. No manifest is approved.");
            return;
        }
        try {
            run(args, System.getenv(), httpClient());
            System.out.println("Completed. Technical artifacts saved; no runtime approval or activation.");
        } catch (Exception ex) {
            // Upstream exceptions may contain authorization headers or response bodies.
            String code = ex instanceof IllegalArgumentException ? "INVALID_INPUT_OR_CONFIGURATION"
                    : ex instanceof FileAlreadyExistsException ? "OUTPUT_ALREADY_EXISTS" : "INDEX_OPERATION_FAILED";
            System.err.println("Public health asset tool failed: " + code + ". No completed output published.");
            System.exit(1);
        }
    }

    static void run(String[] args, Map<String, String> environment, RestTemplate http) throws Exception {
        String command = args[0];
        if (!"build".equals(command) && !"retrieve".equals(command)) {
            throw new IllegalArgumentException("Unknown command");
        }
        Map<String, String> options = options(args, "build".equals(command)
                ? Arrays.asList("--input", "--output", "--batch-size")
                : Arrays.asList("--recipes", "--queries", "--output", "--candidates"));
        Path output = Paths.get(required(options, "--output")).toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException("Output must be a new directory");
        }
        ResolvedAiConfig config = connection(environment);
        OutboundSecurityProperties security = new OutboundSecurityProperties();
        security.setAllowAllHosts(false);
        security.setAllowedHosts(Collections.singletonList(new URI(config.getBaseUrl()).getHost()));
        OutboundSecurityService outbound = new OutboundSecurityService(security);
        PublicHealthEmbeddingClient embeddings = new PublicHealthEmbeddingClient(outbound, http, MAPPER);
        ObjectNode report = MAPPER.createObjectNode();
        report.put("schemaVersion", "public-health-index-tool.v1");
        report.put("command", command);
        report.put("startedAt", Instant.now().toString());
        report.put("runtimeApproved", false);
        report.put("runtimeActivated", false);
        report.put("modelWeightsVerified", false);
        report.put("originalWorkflowEquivalenceVerified", false);
        long started = System.nanoTime();
        Map<String, byte[]> artifacts = new LinkedHashMap<String, byte[]>();
        if ("build".equals(command)) {
            byte[] bytes = read(Paths.get(required(options, "--input")), MAX_ASSET_BYTES);
            JsonNode input = MAPPER.readTree(bytes);
            int batchSize = number(options, "--batch-size", 16, 1, 64);
            ObjectNode recipes = new PublicHealthRecipeIndexBuilder(embeddings, MAPPER).build(input, config, batchSize);
            byte[] indexed = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(recipes);
            if (indexed.length > MAX_ASSET_BYTES) {
                throw new IllegalArgumentException("Index exceeds runtime file size limit");
            }
            artifacts.put("recipes.json", indexed);
            report.put("status", "INDEX_BUILT_PENDING_REVIEW");
            report.put("inputSha256", PublicHealthJson.sha256(bytes));
            report.put("recipesSha256", PublicHealthJson.sha256(indexed));
            report.put("embeddingModel", recipes.path("embeddingModel").asText());
            report.put("dimensions", recipes.path("dimensions").asInt());
            report.put("segments", recipes.path("documents").size());
            report.put("batchSize", batchSize);
            report.put("chunkingVersion", recipes.path("chunkingVersion").asText());
        } else {
            byte[] bytes = read(Paths.get(required(options, "--recipes")), MAX_ASSET_BYTES);
            JsonNode recipes = MAPPER.readTree(bytes);
            PublicHealthAssetService.validateRecipes(recipes);
            byte[] queryBytes = read(Paths.get(required(options, "--queries")), 1024 * 1024);
            JsonNode queries = MAPPER.readTree(queryBytes);
            validateQueries(queries);
            int candidates = number(options, "--candidates", 20, 4, 100);
            PublicHealthProperties properties = new PublicHealthProperties();
            properties.setRetrievalCandidates(candidates);
            PublicHealthRecipeRetriever retriever = new PublicHealthRecipeRetriever(properties, null, outbound, http, MAPPER);
            ArrayNode results = report.putArray("queries");
            for (JsonNode query : queries) {
                long queryStarted = System.nanoTime();
                ArrayNode references = retriever.retrieve(config, recipes, query.path("query").asText(), () -> { });
                ObjectNode result = results.addObject();
                result.put("id", query.path("id").asText());
                result.put("query", query.path("query").asText());
                result.put("elapsedMs", (System.nanoTime() - queryStarted) / 1000000L);
                result.set("references", references);
            }
            report.put("status", "RETRIEVAL_COMPLETED_NOT_CLINICALLY_REVIEWED");
            report.put("recipesSha256", PublicHealthJson.sha256(bytes));
            report.put("queriesSha256", PublicHealthJson.sha256(queryBytes));
            report.put("embeddingModel", recipes.path("embeddingModel").asText());
            report.put("reranker", recipes.path("reranker").asText());
            report.put("dimensions", recipes.path("dimensions").asInt());
            report.put("candidateLimit", candidates);
            report.put("effectiveCandidates", Math.min(candidates, recipes.path("documents").size()));
            report.put("topK", Math.min(4, recipes.path("documents").size()));
        }
        report.put("elapsedMs", (System.nanoTime() - started) / 1000000L);
        report.put("completedAt", Instant.now().toString());
        artifacts.put("build".equals(command) ? "build-report.json" : "retrieval-report.json",
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        publish(output, artifacts);
    }

    private static ResolvedAiConfig connection(Map<String, String> environment) throws Exception {
        String base = required(environment, "FB_PH_INDEX_BASE_URL");
        URI uri = new URI(base);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid model gateway URL");
        }
        ResolvedAiConfig config = new ResolvedAiConfig();
        config.setBaseUrl(base);
        config.setApiKey(required(environment, "FB_PH_INDEX_API_KEY"));
        return config;
    }

    private static Map<String, String> options(String[] args, List<String> allowed) {
        if (args.length % 2 != 1) throw new IllegalArgumentException("Expected option-value pairs");
        Map<String, String> out = new HashMap<String, String>();
        for (int i = 1; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || out.put(args[i], args[i + 1]) != null) {
                throw new IllegalArgumentException("Unknown or duplicate option");
            }
        }
        return out;
    }

    private static int number(Map<String, String> options, String key, int fallback, int min, int max) {
        int value = options.containsKey(key) ? Integer.parseInt(options.get(key)) : fallback;
        if (value < min || value > max) throw new IllegalArgumentException("Option out of range");
        return value;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing required setting");
        return value.trim();
    }

    private static byte[] read(Path path, long max) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > max) throw new IllegalArgumentException("Invalid input file");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > max) throw new IllegalArgumentException("Input file too large");
        return bytes;
    }

    private static void validateQueries(JsonNode queries) {
        // Each query makes two gateway calls; stay within the existing 120/minute limit.
        if (queries == null || !queries.isArray() || queries.size() == 0 || queries.size() > 60) {
            throw new IllegalArgumentException("Expected 1 to 60 query objects");
        }
        Set<String> ids = new HashSet<String>();
        for (JsonNode q : queries) {
            if (!q.path("id").isTextual() || q.path("id").asText().trim().isEmpty()
                    || q.path("id").asText().length() > 128 || !ids.add(q.path("id").asText())
                    || !q.path("query").isTextual() || q.path("query").asText().trim().isEmpty()
                    || q.path("query").asText().length() > 2048) {
                throw new IllegalArgumentException("Invalid query");
            }
        }
    }

    private static void publish(Path output, Map<String, byte[]> artifacts) throws IOException {
        Path parent = output.getParent();
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".ph-index-staging-");
        try {
            for (Map.Entry<String, byte[]> item : artifacts.entrySet()) {
                Files.write(staging.resolve(item.getKey()), item.getValue(), StandardOpenOption.CREATE_NEW);
            }
            // Same-parent rename, with no REPLACE_EXISTING: never overwrite an operator's prior run.
            Files.move(staging, output);
        } finally {
            if (Files.exists(staging)) {
                try (Stream<Path> paths = Files.walk(staging)) {
                    Iterator<Path> entries = paths.sorted(Comparator.reverseOrder()).iterator();
                    while (entries.hasNext()) Files.delete(entries.next());
                }
            }
        }
    }

    private static RestTemplate httpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(60000);
        return new RestTemplate(factory);
    }
}
