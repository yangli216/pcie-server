package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.config.dto.ResolvedAiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicHealthRecipeIndexBuilderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResolvedAiConfig config = new ResolvedAiConfig();
    private final PublicHealthEmbeddingClient embeddings = mock(PublicHealthEmbeddingClient.class);
    private final PublicHealthRecipeIndexBuilder builder = new PublicHealthRecipeIndexBuilder(embeddings, mapper);
    @TempDir Path directory;

    @Test void preservesExactTextOrderMetadataAndInputAcrossBatches() throws Exception {
        ObjectNode input = candidate(3);
        input.put("reviewStatus", "APPROVED");
        input.putObject("provenance").put("datasetId", "SYNTHETIC-DATASET");
        document(input, 0).put("text", "  合成首段\n\n不裁剪  \n");
        document(input, 0).put("position", 9);
        JsonNode before = input.deepCopy();
        List<List<String>> calls = new ArrayList<List<String>>();
        List<Integer> expectedDimensions = new ArrayList<Integer>();
        when(embeddings.embed(same(config), eq("synthetic-embedding"), anyList(), nullable(Integer.class)))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(2);
                    calls.add(new ArrayList<String>(texts));
                    expectedDimensions.add(invocation.getArgument(3));
                    double[][] vectors = new double[texts.size()][];
                    for (int i = 0; i < vectors.length; i++) {
                        vectors[i] = new double[] {calls.size(), i + 1};
                    }
                    return vectors;
                });

        ObjectNode result = builder.build(input, config, 2);
        assertEquals(before, input);
        assertEquals(Arrays.asList(null, 2), expectedDimensions);
        assertEquals(Arrays.asList("  合成首段\n\n不裁剪  \n", "合成段落 1"), calls.get(0));
        assertEquals(Arrays.asList("合成段落 2"), calls.get(1));
        for (int i = 0; i < 3; i++) {
            ObjectNode copied = document(result, i).deepCopy();
            copied.remove("vector");
            assertEquals(input.path("documents").get(i), copied);
        }
        assertEquals(2, result.path("dimensions").asInt());
        assertEquals("NOT_ASSERTED", result.path("provenance").path("reviewStatus").asText());
        assertEquals("INDEX_BUILT_PENDING_REVIEW", result.path("provenance").path("status").asText());
        assertEquals(input.path("provenance"), result.path("provenance").path("source"));
        assertEquals(PublicHealthJson.sha256(mapper.writeValueAsBytes(input.path("documents"))),
                result.path("provenance").path("inputDocumentsSha256").asText());
        assertFalse(result.has("reviewStatus"));
        assertFalse(result.has("organizationId"));
        assertFalse(result.has("manifest"));
        assertDoesNotThrow(() -> PublicHealthAssetService.validateRecipes(result));
    }

    @Test void rejectsDuplicateIdsBeforeEmbedding() {
        ObjectNode input = candidate(2);
        document(input, 1).put("id", "segment-0");
        assertThrows(PublicHealthException.class, () -> builder.build(input, config, 2));
        verifyNoInteractions(embeddings);
    }

    @Test void rejectsMissingOrInvalidMetadataBeforeEmbedding() {
        for (String field : Arrays.asList("embeddingModel", "chunkingVersion", "reranker")) {
            ObjectNode input = candidate(1);
            input.remove(field);
            assertThrows(RuntimeException.class, () -> builder.build(input, config, 1), field);
        }
        for (String field : Arrays.asList("id", "documentId", "source", "title", "text")) {
            ObjectNode missing = candidate(1);
            document(missing, 0).remove(field);
            assertThrows(RuntimeException.class, () -> builder.build(missing, config, 1), field);
            ObjectNode empty = candidate(1);
            document(empty, 0).put(field, " \n");
            assertThrows(RuntimeException.class, () -> builder.build(empty, config, 1), field);
        }
        ObjectNode wrongReranker = candidate(1);
        wrongReranker.put("reranker", "other-reranker");
        assertThrows(PublicHealthException.class, () -> builder.build(wrongReranker, config, 1));
        assertThrows(PublicHealthException.class, () -> builder.build(candidate(0), config, 1));
        assertThrows(PublicHealthException.class, () -> builder.build(null, config, 1));
        verifyNoInteractions(embeddings);
    }

    @Test void honorsRuntimeTextAndMetadataLimits() {
        ObjectNode input = candidate(1);
        document(input, 0).put("text", repeat("a", 16001));
        assertThrows(PublicHealthException.class, () -> builder.build(input, config, 1));
        document(input, 0).put("text", "合成文本");
        document(input, 0).put("title", repeat("a", 257));
        assertThrows(IllegalArgumentException.class, () -> builder.build(input, config, 1));
        verifyNoInteractions(embeddings);
    }

    @Test void refusesExistingVectorsIncludingNullWithoutMutatingInput() {
        ObjectNode input = candidate(1);
        document(input, 0).putNull("vector");
        JsonNode before = input.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> builder.build(input, config, 1));
        assertEquals(before, input);
        verifyNoInteractions(embeddings);
    }

    @Test void rejectsBatchSizesOutsideRangeBeforeCallingUpstream() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(candidate(1), config, 0));
        assertThrows(IllegalArgumentException.class, () -> builder.build(candidate(1), config, 65));
        verifyNoInteractions(embeddings);
    }

    @Test void rejectsDimensionDriftWithinAndAcrossBatches() {
        when(embeddings.embed(same(config), anyString(), anyList(), nullable(Integer.class)))
                .thenReturn(new double[][] {{1, 2}, {1, 2, 3}});
        assertThrows(PublicHealthException.class, () -> builder.build(candidate(2), config, 2));
        reset(embeddings);
        when(embeddings.embed(same(config), anyString(), anyList(), nullable(Integer.class)))
                .thenReturn(new double[][] {{1, 2}})
                .thenReturn(new double[][] {{1, 2, 3}});
        assertThrows(PublicHealthException.class, () -> builder.build(candidate(2), config, 1));
        verify(embeddings).embed(same(config), eq("synthetic-embedding"), eq(Arrays.asList("合成段落 1")), eq(2));
    }

    @Test void rejectsWrongCountZeroNonfiniteAndEmptyVectors() {
        double[][][] invalid = new double[][][] {
                {}, {{0, 0}}, {{Double.NaN, 1}}, {{Double.POSITIVE_INFINITY, 1}}, {{}}, {null}
        };
        for (double[][] vectors : invalid) {
            reset(embeddings);
            when(embeddings.embed(same(config), anyString(), anyList(), nullable(Integer.class))).thenReturn(vectors);
            assertThrows(PublicHealthException.class, () -> builder.build(candidate(1), config, 64));
        }
    }

    @Test void failingSecondBatchReturnsNoPartialIndexAndLeavesInputUnchanged() {
        ObjectNode input = candidate(3);
        JsonNode before = input.deepCopy();
        when(embeddings.embed(same(config), anyString(), anyList(), nullable(Integer.class)))
                .thenReturn(new double[][] {{1, 2}})
                .thenThrow(PublicHealthAssetService.unavailable("KNOWLEDGE_NOT_READY"));
        assertThrows(PublicHealthException.class, () -> builder.build(input, config, 1));
        assertEquals(before, input);
        verify(embeddings, times(2)).embed(same(config), anyString(), anyList(), nullable(Integer.class));
    }

    @Test void syntheticIndexLoadsOnlyThroughExistingOrgApprovalAndHashGates() throws Exception {
        when(embeddings.embed(same(config), anyString(), anyList(), nullable(Integer.class)))
                .thenReturn(new double[][] {{1, 2}});
        ObjectNode result = builder.build(candidate(1), config, 64);
        byte[] indexBytes = mapper.writeValueAsBytes(result);
        Path organizationDirectory = Files.createDirectory(directory.resolve("synthetic"));
        Files.write(organizationDirectory.resolve("recipes.json"), indexBytes);
        PublicHealthProperties properties = new PublicHealthProperties();
        properties.setAssetDirectory(directory.toString());
        properties.getOrgAssets().put("ORG-SYNTHETIC", "synthetic");
        PublicHealthAssetService service = new PublicHealthAssetService(properties, mapper);
        assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(), "weight_management"));

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("organizationId", "ORG-SYNTHETIC");
        manifest.put("reviewStatus", "PENDING_REVIEW");
        manifest.putArray("capabilities").add("weight_management");
        manifest.putObject("recipes").put("assetId", "SYNTHETIC-RECIPES")
                .put("version", "synthetic-v1").put("file", "recipes.json")
                .put("sha256", PublicHealthJson.sha256(indexBytes));
        writeManifest(organizationDirectory, manifest);
        assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(), "weight_management"));

        // Approval exists only in this synthetic fixture; the builder never writes it.
        manifest.put("reviewStatus", "APPROVED");
        writeManifest(organizationDirectory, manifest);
        assertEquals(result, service.load(PublicHealthTestFixture.device(), "weight_management").recipes);
        manifest.put("organizationId", "OTHER-SYNTHETIC-ORG");
        writeManifest(organizationDirectory, manifest);
        assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(), "weight_management"));
        manifest.put("organizationId", "ORG-SYNTHETIC");
        writeManifest(organizationDirectory, manifest);
        Files.write(organizationDirectory.resolve("recipes.json"), "{}".getBytes("UTF-8"));
        assertThrows(PublicHealthException.class, () -> service.load(PublicHealthTestFixture.device(), "weight_management"));
    }

    @Test void sharedRuntimeValidationRejectsOverflowingDimensions() {
        ObjectNode input = candidate(1);
        document(input, 0).putArray("vector").add(1);
        input.put("dimensions", 4294967297L);
        assertThrows(PublicHealthException.class, () -> PublicHealthAssetService.validateRecipes(input));
    }

    private ObjectNode candidate(int count) {
        ObjectNode value = mapper.createObjectNode();
        value.put("embeddingModel", "synthetic-embedding");
        value.put("chunkingVersion", "synthetic-original-v1");
        value.put("reranker", "bge-reranker-large");
        ArrayNode documents = value.putArray("documents");
        for (int i = 0; i < count; i++) {
            documents.addObject().put("id", "segment-" + i).put("documentId", "SYNTHETIC-DOC")
                    .put("source", "synthetic://recipe/" + i).put("title", "合成食谱")
                    .put("text", "合成段落 " + i);
        }
        return value;
    }

    private ObjectNode document(JsonNode value, int index) {
        return (ObjectNode) value.path("documents").get(index);
    }

    private String repeat(String value, int count) {
        return String.join("", java.util.Collections.nCopies(count, value));
    }

    private void writeManifest(Path folder, ObjectNode manifest) throws Exception {
        Files.write(folder.resolve("manifest.json"), mapper.writeValueAsBytes(manifest));
    }
}
