package com.regionalai.floatingball.server.modules.publichealth.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.service.PublicHealthJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PublicHealthAssetToolTest {
    private static final ObjectMapper M = new ObjectMapper();
    @TempDir Path directory;

    @Test void buildsByteHashedIndexWithoutApprovalOrSecrets() throws Exception {
        Path input = input();
        byte[] original = Files.readAllBytes(input);
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://127.0.0.1:18080/v1/embeddings"))
                .andExpect(jsonPath("$.input[1]").value("second source text"))
                .andRespond(withSuccess("{\"model\":\"test-embedding\",\"data\":["
                        + "{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}", MediaType.APPLICATION_JSON));
        Path out = directory.resolve("built");
        PublicHealthAssetTool.run(new String[]{"build", "--input", input.toString(), "--output", out.toString()}, environment(), http);
        JsonNode recipes = M.readTree(out.resolve("recipes.json").toFile());
        JsonNode report = M.readTree(out.resolve("build-report.json").toFile());
        assertEquals(1, recipes.path("documents").get(0).path("vector").get(0).asInt());
        assertEquals("first source text", recipes.path("documents").get(0).path("text").asText());
        assertEquals(PublicHealthJson.sha256(original), report.path("inputSha256").asText());
        assertEquals(PublicHealthJson.sha256(Files.readAllBytes(out.resolve("recipes.json"))), report.path("recipesSha256").asText());
        assertFalse(report.path("runtimeApproved").asBoolean(true));
        assertFalse(Files.exists(out.resolve("manifest.json")));
        assertFalse(report.toString().contains("secret-for-test"));
        assertArrayEquals(original, Files.readAllBytes(input));
        server.verify();
    }

    @Test void failingLaterBatchPublishesNoIndex() throws Exception {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://127.0.0.1:18080/v1/embeddings"))
                .andRespond(withSuccess("{\"model\":\"test-embedding\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:18080/v1/embeddings"))
                .andRespond(withServerError());
        Path out = directory.resolve("failed");
        Path input = input();
        assertThrows(Exception.class, () -> PublicHealthAssetTool.run(new String[]{"build", "--input", input.toString(),
                "--output", out.toString(), "--batch-size", "1"}, environment(), http));
        assertFalse(Files.exists(out));
        server.verify();
    }

    @Test void retrievesUsingProductionRankingAndDoesNotReturnVectors() throws Exception {
        ObjectNode recipes = candidate();
        recipes.put("dimensions", 2);
        ((ObjectNode) recipes.path("documents").get(0)).putArray("vector").add(1).add(0);
        ((ObjectNode) recipes.path("documents").get(1)).putArray("vector").add(0).add(1);
        Path indexed = directory.resolve("recipes.json");
        M.writeValue(indexed.toFile(), recipes);
        Path queries = directory.resolve("queries.json");
        Files.write(queries, "[{\"id\":\"synthetic\",\"query\":\"synthetic region season energy\"}]".getBytes("UTF-8"));
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://127.0.0.1:18080/v1/embeddings"))
                .andRespond(withSuccess("{\"model\":\"test-embedding\",\"data\":[{\"index\":0,\"embedding\":[0,1]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:18080/v1/rerank"))
                .andExpect(jsonPath("$.documents[0]").value("second source text"))
                .andRespond(withSuccess("{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}", MediaType.APPLICATION_JSON));
        Path out = directory.resolve("retrieved");
        PublicHealthAssetTool.run(new String[]{"retrieve", "--recipes", indexed.toString(), "--queries", queries.toString(),
                "--output", out.toString()}, environment(), http);
        JsonNode report = M.readTree(out.resolve("retrieval-report.json").toFile());
        assertEquals(2, report.path("effectiveCandidates").asInt());
        JsonNode references = report.path("queries").get(0).path("references");
        assertEquals("source-0", references.get(0).path("id").asText());
        assertFalse(references.get(0).has("vector"));
        assertFalse(report.path("originalWorkflowEquivalenceVerified").asBoolean(true));
        server.verify();
    }

    @Test void refusesExistingOutputAndMissingConnectionBeforeNetwork() throws Exception {
        Path input = input();
        Path out = directory.resolve("existing");
        Files.createDirectory(out);
        Path sentinel = out.resolve("retain.txt");
        Files.write(sentinel, new byte[]{42});
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        assertThrows(Exception.class, () -> PublicHealthAssetTool.run(new String[]{"build", "--input", input.toString(),
                "--output", out.toString()}, environment(), http));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(sentinel));
        assertThrows(IllegalArgumentException.class, () -> PublicHealthAssetTool.run(new String[]{"build", "--input", input.toString(),
                "--output", directory.resolve("unconfigured").toString()}, new HashMap<String, String>(), http));
        assertFalse(Files.exists(directory.resolve("unconfigured")));
        server.verify();
    }

    @Test void maximumQueryBatchFitsGatewayBudgetAndLargerBatchFailsBeforeCalls() throws Exception {
        ObjectNode recipes = candidate();
        recipes.put("dimensions", 2);
        for (JsonNode doc : recipes.path("documents")) ((ObjectNode) doc).putArray("vector").add(1).add(0);
        Path indexed = directory.resolve("indexed.json");
        M.writeValue(indexed.toFile(), recipes);
        com.fasterxml.jackson.databind.node.ArrayNode queries = M.createArrayNode();
        for (int i = 0; i < 60; i++) queries.addObject().put("id", "q-" + i).put("query", "synthetic query");
        Path queryFile = directory.resolve("batch.json");
        M.writeValue(queryFile.toFile(), queries);
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        for (int i = 0; i < 60; i++) {
            server.expect(requestTo("http://127.0.0.1:18080/v1/embeddings"))
                    .andRespond(withSuccess("{\"model\":\"test-embedding\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("http://127.0.0.1:18080/v1/rerank"))
                    .andRespond(withSuccess("{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8}]}", MediaType.APPLICATION_JSON));
        }
        Path out = directory.resolve("maximum");
        PublicHealthAssetTool.run(new String[]{"retrieve", "--recipes", indexed.toString(), "--queries", queryFile.toString(),
                "--output", out.toString()}, environment(), http);
        assertEquals(60, M.readTree(out.resolve("retrieval-report.json").toFile()).path("queries").size());
        queries.addObject().put("id", "q-60").put("query", "synthetic query");
        M.writeValue(queryFile.toFile(), queries);
        assertThrows(IllegalArgumentException.class, () -> PublicHealthAssetTool.run(new String[]{"retrieve", "--recipes", indexed.toString(),
                "--queries", queryFile.toString(), "--output", directory.resolve("too-many").toString()}, environment(), http));
        assertFalse(Files.exists(directory.resolve("too-many")));
        server.verify();
    }

    @Test void rejectsCredentialBearingUrlAndUnknownArguments() throws Exception {
        Map<String, String> env = environment();
        env.put("FB_PH_INDEX_BASE_URL", "http://user:secret@127.0.0.1/v1");
        Path input = input();
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        assertThrows(IllegalArgumentException.class, () -> PublicHealthAssetTool.run(new String[]{"build", "--input", input.toString(),
                "--output", directory.resolve("bad-url").toString()}, env, http));
        assertThrows(IllegalArgumentException.class, () -> PublicHealthAssetTool.run(new String[]{"build", "--api-key", "secret-for-test"}, environment(), http));
        server.verify();
    }

    private Path input() throws Exception {
        Path p = directory.resolve("candidate.json");
        M.writeValue(p.toFile(), candidate());
        return p;
    }

    private ObjectNode candidate() {
        ObjectNode value = M.createObjectNode();
        value.put("embeddingModel", "test-embedding");
        value.put("chunkingVersion", "source-v1");
        value.put("reranker", "bge-reranker-large");
        for (int i = 0; i < 2; i++) {
            ObjectNode doc = value.withArray("documents").addObject();
            doc.put("id", "source-" + i).put("documentId", "document")
                    .put("source", "synthetic").put("title", "test")
                    .put("text", i == 0 ? "first source text" : "second source text");
        }
        return value;
    }

    private Map<String, String> environment() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("FB_PH_INDEX_BASE_URL", "http://127.0.0.1:18080/v1");
        env.put("FB_PH_INDEX_API_KEY", "secret-for-test");
        return env;
    }
}
