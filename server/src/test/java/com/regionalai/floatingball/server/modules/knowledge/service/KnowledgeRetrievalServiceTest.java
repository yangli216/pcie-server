package com.regionalai.floatingball.server.modules.knowledge.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.knowledge.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeRetrievalServiceTest {
    @TempDir Path directory;
    KnowledgeTestFixture fixture;
    KnowledgeRetrievalProvider provider;
    KnowledgeRetrievalService service;
    @BeforeEach void setup() throws Exception {
        fixture = new KnowledgeTestFixture(directory); provider = mock(KnowledgeRetrievalProvider.class);
        when(provider.name()).thenReturn("weknora"); service = new KnowledgeRetrievalService(fixture.catalog, provider);
    }
    @Test void evidenceUsesApprovedSourceAndExactBodyInsteadOfProviderMetadata() {
        when(provider.search(any(), eq("合成资料"), eq(5))).thenReturn(Collections.singletonList(hit("DOC-SYNTHETIC", "CHUNK-SYNTHETIC", "KB-SYNTHETIC", KnowledgeTestFixture.CONTENT)));
        KnowledgeSearchResponse result = service.search(KnowledgeTestFixture.device(), KnowledgeTestFixture.request());
        assertEquals("合成资料", result.getQuery()); assertEquals("合成工程资料", result.getResults().get(0).getTitle());
        assertEquals("source-v1", result.getResults().get(0).getSourceVersion()); assertEquals("SOURCE-SYNTHETIC", result.getResults().get(0).getSourceId());
        assertEquals(KnowledgeTestFixture.CONTENT, result.getResults().get(0).getContent());
        assertEquals(KnowledgeJson.sha256(KnowledgeTestFixture.CONTENT), result.getResults().get(0).getContentSha256());
        assertEquals(fixture.release().getManifestSha256(), result.getManifestSha256());
    }
    @Test void bodyScopeAndDuplicateDriftRejectWholeResultSet() {
        KnowledgeProviderHit valid = hit("DOC-SYNTHETIC", "CHUNK-SYNTHETIC", "KB-SYNTHETIC", KnowledgeTestFixture.CONTENT);
        List<List<KnowledgeProviderHit>> variants = Arrays.asList(
            Collections.singletonList(hit("UNAPPROVED", "CHUNK-SYNTHETIC", "KB-SYNTHETIC", KnowledgeTestFixture.CONTENT)),
            Collections.singletonList(hit("DOC-SYNTHETIC", "OTHER-CHUNK", "KB-SYNTHETIC", KnowledgeTestFixture.CONTENT)),
            Collections.singletonList(hit("DOC-SYNTHETIC", "CHUNK-SYNTHETIC", "OTHER-KB", KnowledgeTestFixture.CONTENT)),
            Collections.singletonList(hit("DOC-SYNTHETIC", "CHUNK-SYNTHETIC", null, KnowledgeTestFixture.CONTENT + " ")),
            Arrays.asList(valid, valid));
        for (List<KnowledgeProviderHit> hits : variants) {
            when(provider.search(any(), anyString(), anyInt())).thenReturn(hits);
            assertEquals("KNOWLEDGE_CONTENT_CHANGED", assertThrows(BusinessException.class, () -> service.search(KnowledgeTestFixture.device(), KnowledgeTestFixture.request())).getCode());
        }
    }
    @Test void wrongOrganizationAndOldVersionNeverReachProvider() {
        assertThrows(BusinessException.class, () -> service.search(KnowledgeTestFixture.device("OTHER-ORG"), KnowledgeTestFixture.request()));
        KnowledgeSearchRequest request = KnowledgeTestFixture.request(); request.setReleaseVersion("old");
        assertEquals("KNOWLEDGE_VERSION_CHANGED", assertThrows(BusinessException.class, () -> service.search(KnowledgeTestFixture.device(), request)).getCode());
        verifyNoInteractions(provider);
    }
    @Test void noHitsRemainASuccessAndQueryLimitsAreValidatedBeforeRetrieval() {
        when(provider.search(any(), anyString(), anyInt())).thenReturn(Collections.emptyList());
        assertTrue(service.search(KnowledgeTestFixture.device(), KnowledgeTestFixture.request()).getResults().isEmpty());
        reset(provider);
        KnowledgeSearchRequest request = KnowledgeTestFixture.request(); request.setLimit(null);
        assertThrows(BusinessException.class, () -> service.search(KnowledgeTestFixture.device(), request));
        request.setLimit(21); assertThrows(BusinessException.class, () -> service.search(KnowledgeTestFixture.device(), request));
        request.setLimit(5); request.setQuery("   "); assertThrows(BusinessException.class, () -> service.search(KnowledgeTestFixture.device(), request));
        verifyNoInteractions(provider);
    }
    private static KnowledgeProviderHit hit(String doc, String chunk, String kb, String body) { return new KnowledgeProviderHit(chunk, doc, kb, body, .9, "vector"); }
}
