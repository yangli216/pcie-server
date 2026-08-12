package com.regionalai.floatingball.server.modules.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseBatchUploadRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseDownloadItem;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseHistoryView;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyUpdateRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyView;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseRollbackRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseUploadRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseView;
import com.regionalai.floatingball.server.modules.release.dto.TauriLatestJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseServiceTest {

    @TempDir
    Path tempDir;

    private ReleaseService releaseService;

    @BeforeEach
    void setUp() {
        releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
    }

    @Test
    void uploadShouldSnapshotPreviousVersionAndRollback() {
        upload("1.2.15", "darwin-aarch64", "PCIE_1.2.15_aarch64.app.tar.gz", false);
        upload("1.2.16", "windows-x86_64", "PCIE_1.2.16_x64-setup.nsis.zip", true);

        TauriLatestJson latestAfterUpload = releaseService.getLatestJson("production");
        assertEquals("1.2.16", latestAfterUpload.getVersion());
        assertTrue(latestAfterUpload.getPlatforms().containsKey("windows-x86_64"));
        assertFalse(latestAfterUpload.getPlatforms().containsKey("darwin-aarch64"));

        List<ReleaseHistoryView> history = releaseService.history("production");
        assertTrue(history.stream().anyMatch(item -> "1.2.15".equals(item.getVersion())));
        assertTrue(history.stream().anyMatch(item -> "1.2.16".equals(item.getVersion()) && Boolean.TRUE.equals(item.getActive())));

        ReleaseRollbackRequest rollbackRequest = new ReleaseRollbackRequest();
        rollbackRequest.setChannel("production");
        rollbackRequest.setVersion("1.2.15");
        releaseService.rollback(rollbackRequest);

        TauriLatestJson latestAfterRollback = releaseService.getLatestJson("production");
        ReleasePolicyView policyAfterRollback = releaseService.getPolicy("production");
        assertEquals("1.2.15", latestAfterRollback.getVersion());
        assertTrue(latestAfterRollback.getPlatforms().containsKey("darwin-aarch64"));
        assertFalse(latestAfterRollback.getPlatforms().containsKey("windows-x86_64"));
        assertFalse(Boolean.TRUE.equals(policyAfterRollback.getForceUpdate()));
        assertEquals("1.2.15", policyAfterRollback.getLatestVersion());
    }

    @Test
    void updatePolicyShouldToggleForceUpdateForCurrentVersion() {
        upload("1.2.15", "darwin-aarch64", "PCIE_1.2.15_aarch64.app.tar.gz", false);

        ReleasePolicyUpdateRequest enableRequest = new ReleasePolicyUpdateRequest();
        enableRequest.setChannel("production");
        enableRequest.setForceUpdate(true);
        releaseService.updatePolicy(enableRequest);

        ReleasePolicyView enabledPolicy = releaseService.getPolicy("production");
        assertTrue(Boolean.TRUE.equals(enabledPolicy.getForceUpdate()));
        assertEquals("1.2.15", enabledPolicy.getMinSupportedVersion());

        ReleasePolicyUpdateRequest disableRequest = new ReleasePolicyUpdateRequest();
        disableRequest.setChannel("production");
        disableRequest.setForceUpdate(false);
        releaseService.updatePolicy(disableRequest);

        ReleasePolicyView disabledPolicy = releaseService.getPolicy("production");
        assertFalse(Boolean.TRUE.equals(disabledPolicy.getForceUpdate()));
        assertNull(disabledPolicy.getMinSupportedVersion());
    }

    @Test
    void downloadItemsShouldExposeCurrentReleaseFilesForFirstInstall() {
        upload("1.2.15", "darwin-aarch64", "PCIE_1.2.15_aarch64.app.tar.gz", false);
        upload("1.2.15", "windows-x86_64", "PCIE_1.2.15_x64-setup.nsis.zip", false);

        List<ReleaseDownloadItem> items = releaseService.downloadItems("production", "http://release.lan:8080");

        assertEquals(2, items.size());
        ReleaseDownloadItem macItem = items.stream()
            .filter(item -> "darwin-aarch64".equals(item.getTarget()))
            .findFirst()
            .orElseThrow(AssertionError::new);
        assertEquals("1.2.15", macItem.getVersion());
        assertEquals("PCIE_1.2.15_aarch64.app.tar.gz", macItem.getFileName());
        assertTrue(macItem.getDownloadUrl().startsWith("http://release.lan:8080/v1/client/releases/production/files/1.2.15/darwin-aarch64/"));
        assertTrue(macItem.getFileSize() > 0);
    }

    @Test
    void uploadBatchShouldPublishMultipleChannelsAndInferTargetsFromPackageFiles() {
        ReleaseBatchUploadRequest request = new ReleaseBatchUploadRequest();
        request.setChannels(Arrays.asList("testing", "production"));
        request.setForceUpdate(true);
        request.setMetadataFile(new MockMultipartFile(
            "metadataFile",
            "latest.json",
            "application/json",
            buildLatestJson(
                "1.3.0",
                "darwin-aarch64",
                "PCIE_1.3.0_aarch64.app.tar.gz",
                "windows-x86_64",
                "PCIE_1.3.0_x64-setup.nsis.zip"
            ).getBytes(StandardCharsets.UTF_8)
        ));
        request.setFiles(Arrays.asList(
            new MockMultipartFile(
                "files",
                "PCIE_1.3.0_x64-setup.nsis.zip",
                "application/octet-stream",
                "package-win".getBytes(StandardCharsets.UTF_8)
            ),
            new MockMultipartFile(
                "files",
                "PCIE_1.3.0_aarch64.app.tar.gz",
                "application/octet-stream",
                "package-mac".getBytes(StandardCharsets.UTF_8)
            )
        ));

        List<ReleaseView> views = releaseService.uploadBatch(request);

        assertEquals(2, views.size());
        for (String channel : Arrays.asList("testing", "production")) {
            TauriLatestJson latestJson = releaseService.getLatestJson(channel);
            ReleasePolicyView policy = releaseService.getPolicy(channel);
            assertEquals("1.3.0", latestJson.getVersion());
            assertEquals(2, latestJson.getPlatforms().size());
            assertTrue(latestJson.getPlatforms().containsKey("darwin-aarch64"));
            assertTrue(latestJson.getPlatforms().containsKey("windows-x86_64"));
            assertTrue(latestJson.getPlatforms().get("windows-x86_64").getUrl().contains("/windows-x86_64/"));
            assertTrue(Boolean.TRUE.equals(policy.getForceUpdate()));
            assertEquals("1.3.0", policy.getMinSupportedVersion());
        }

        ReleaseView productionView = views.stream()
            .filter(item -> "production".equals(item.getChannel()))
            .findFirst()
            .orElseThrow(AssertionError::new);
        assertEquals(2, productionView.getPlatforms().size());
    }

    @Test
    void uploadBatchShouldPublishUniversalPackageToAllMatchingTargets() {
        ReleaseBatchUploadRequest request = new ReleaseBatchUploadRequest();
        request.setChannels(Arrays.asList("production"));
        request.setMetadataFile(new MockMultipartFile(
            "metadataFile",
            "latest.json",
            "application/json",
            buildLatestJson(
                "1.3.1",
                "darwin-aarch64",
                "PCIE_universal.app.tar.gz",
                "darwin-x86_64",
                "PCIE_universal.app.tar.gz"
            ).getBytes(StandardCharsets.UTF_8)
        ));
        request.setFiles(Arrays.asList(
            new MockMultipartFile(
                "files",
                "PCIE_universal.app.tar.gz",
                "application/octet-stream",
                "package-universal".getBytes(StandardCharsets.UTF_8)
            )
        ));

        List<ReleaseView> views = releaseService.uploadBatch(request);

        assertEquals(1, views.size());
        TauriLatestJson latestJson = releaseService.getLatestJson("production");
        assertEquals("1.3.1", latestJson.getVersion());
        assertEquals(2, latestJson.getPlatforms().size());
        assertTrue(latestJson.getPlatforms().containsKey("darwin-aarch64"));
        assertTrue(latestJson.getPlatforms().containsKey("darwin-x86_64"));
        assertTrue(latestJson.getPlatforms().get("darwin-aarch64").getUrl().contains("/darwin-aarch64/PCIE_universal.app.tar.gz"));
        assertTrue(latestJson.getPlatforms().get("darwin-x86_64").getUrl().contains("/darwin-x86_64/PCIE_universal.app.tar.gz"));
        assertEquals("signature-darwin-aarch64", latestJson.getPlatforms().get("darwin-aarch64").getSignature());
        assertEquals("signature-darwin-x86_64", latestJson.getPlatforms().get("darwin-x86_64").getSignature());
        assertEquals(2, views.get(0).getPlatforms().size());
    }

    @Test
    void versionedPackagesShouldKeepSameFileNameWithDifferentContentAndRollbackToOldContent() throws Exception {
        String fileName = "PCIE-setup.zip";
        uploadWithContent("1.0.0", "windows-x86_64", fileName, "package-v1", false);
        uploadWithContent("2.0.0", "windows-x86_64", fileName, "package-v2", false);

        assertEquals(
            "package-v1",
            new String(Files.readAllBytes(releaseService.resolveFile("production", "1.0.0", "windows-x86_64", fileName)), StandardCharsets.UTF_8)
        );
        assertEquals(
            "package-v2",
            new String(Files.readAllBytes(releaseService.resolveFile("production", "2.0.0", "windows-x86_64", fileName)), StandardCharsets.UTF_8)
        );
        assertTrue(
            releaseService.getLatestJson("production").getPlatforms().get("windows-x86_64").getUrl()
                .contains("/files/2.0.0/windows-x86_64/" + fileName)
        );

        ReleaseRollbackRequest rollbackRequest = new ReleaseRollbackRequest();
        rollbackRequest.setChannel("production");
        rollbackRequest.setVersion("1.0.0");
        releaseService.rollback(rollbackRequest);

        assertEquals(
            "package-v1",
            new String(Files.readAllBytes(releaseService.resolveFile("production", "windows-x86_64", fileName)), StandardCharsets.UTF_8)
        );
        assertTrue(
            releaseService.getLatestJson("production").getPlatforms().get("windows-x86_64").getUrl()
                .contains("/files/1.0.0/windows-x86_64/" + fileName)
        );
    }

    @Test
    void sameVersionPathShouldBeImmutableButAllowIdenticalRetry() {
        String fileName = "PCIE-setup.zip";
        uploadWithContent("1.0.0", "windows-x86_64", fileName, "same-package", false);

        uploadWithContent("1.0.0", "windows-x86_64", fileName, "same-package", false);
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> uploadWithContent("1.0.0", "windows-x86_64", fileName, "evil-package", false)
        );

        assertEquals("RELEASE-IMMUTABLE", exception.getCode());
    }

    @Test
    void independentReaderShouldImmediatelyObservePublishedVersionedPackage() throws Exception {
        String fileName = "PCIE-setup.zip";
        uploadWithContent("1.0.0", "windows-x86_64", fileName, "shared-package", false);

        ReleaseService independentReader = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());

        assertEquals("1.0.0", independentReader.getLatestJson("production").getVersion());
        assertEquals(
            "shared-package",
            new String(
                Files.readAllBytes(independentReader.resolveFile("production", "1.0.0", "windows-x86_64", fileName)),
                StandardCharsets.UTF_8
            )
        );
    }

    @Test
    void legacyCurrentReleaseShouldKeepLegacyDownloadUrlAndRemainDownloadable() throws Exception {
        writeLegacyRelease("1.0.0", "windows-x86_64", "PCIE-legacy.zip", "legacy-package", false);
        ReleaseService legacyReader = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());

        TauriLatestJson latestJson = legacyReader.getLatestJson("production", "http://release.local");

        assertEquals(
            "http://release.local/v1/client/releases/production/files/windows-x86_64/PCIE-legacy.zip",
            latestJson.getPlatforms().get("windows-x86_64").getUrl()
        );
        assertEquals(
            "legacy-package",
            new String(
                Files.readAllBytes(legacyReader.resolveFile("production", "windows-x86_64", "PCIE-legacy.zip")),
                StandardCharsets.UTF_8
            )
        );
    }

    @Test
    void rollbackOfLegacyHistoryShouldRestoreLegacyUrlAndContent() throws Exception {
        writeLegacyRelease("1.0.0", "windows-x86_64", "PCIE-legacy.zip", "legacy-package", false);
        releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
        uploadWithContent("2.0.0", "windows-x86_64", "PCIE-current.zip", "current-package", true);

        ReleaseRollbackRequest rollbackRequest = new ReleaseRollbackRequest();
        rollbackRequest.setChannel("production");
        rollbackRequest.setVersion("1.0.0");
        releaseService.rollback(rollbackRequest);

        TauriLatestJson latestJson = releaseService.getLatestJson("production", "http://release.local");
        assertEquals(
            "http://release.local/v1/client/releases/production/files/windows-x86_64/PCIE-legacy.zip",
            latestJson.getPlatforms().get("windows-x86_64").getUrl()
        );
        assertEquals(
            "legacy-package",
            new String(
                Files.readAllBytes(releaseService.resolveFile("production", "windows-x86_64", "PCIE-legacy.zip")),
                StandardCharsets.UTF_8
            )
        );
    }

    @Test
    void compatibilityCacheSecondWriteFailureShouldNotSplitAuthoritativeState() throws Exception {
        uploadWithContent("1.0.0", "windows-x86_64", "PCIE-v1.zip", "package-v1", false);
        uploadWithContent("2.0.0", "windows-x86_64", "PCIE-v2.zip", "package-v2", true);
        ReleaseService serviceWithSecondCacheWriteFailure = new ReleaseService(
            tempDir.toString(),
            "http://release.local",
            new ObjectMapper()
        ) {
            @Override
            protected void writeCompatibilityPolicy(String channel, ReleasePolicyView policy) throws IOException {
                throw new IOException("injected policy cache failure");
            }
        };
        ReleaseRollbackRequest rollbackRequest = new ReleaseRollbackRequest();
        rollbackRequest.setChannel("production");
        rollbackRequest.setVersion("1.0.0");

        serviceWithSecondCacheWriteFailure.rollback(rollbackRequest);

        // The compatibility cache is deliberately split: latest points to v1 while the
        // failed second write leaves the force-update policy at v2. Public reads must never
        // use this impossible combination.
        ObjectMapper objectMapper = new ObjectMapper();
        TauriLatestJson cachedLatest = objectMapper.readValue(
            tempDir.resolve("production/latest.json").toFile(),
            TauriLatestJson.class
        );
        ReleasePolicyView cachedPolicy = objectMapper.readValue(
            tempDir.resolve("production/policy.json").toFile(),
            ReleasePolicyView.class
        );
        assertEquals("1.0.0", cachedLatest.getVersion());
        assertEquals("2.0.0", cachedPolicy.getMinSupportedVersion());

        ReleaseService independentReader = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());

        assertEquals("1.0.0", independentReader.getLatestJson("production").getVersion());
        ReleasePolicyView policy = independentReader.getPolicy("production");
        assertEquals("1.0.0", policy.getLatestVersion());
        assertNull(policy.getMinSupportedVersion());
        assertFalse(Boolean.TRUE.equals(policy.getForceUpdate()));
        assertEquals(
            "package-v1",
            new String(
                Files.readAllBytes(independentReader.resolveFile("production", "1.0.0", "windows-x86_64", "PCIE-v1.zip")),
                StandardCharsets.UTF_8
            )
        );
    }

    private void upload(String version, String target, String fileName, boolean forceUpdate) {
        uploadWithContent(version, target, fileName, "package-" + version, forceUpdate);
    }

    private void uploadWithContent(String version,
                                   String target,
                                   String fileName,
                                   String content,
                                   boolean forceUpdate) {
        releaseService.upload(uploadRequest(version, target, fileName, content, forceUpdate));
    }

    private ReleaseUploadRequest uploadRequest(String version,
                                                String target,
                                                String fileName,
                                                String content,
                                                boolean forceUpdate) {
        ReleaseUploadRequest request = new ReleaseUploadRequest();
        request.setChannel("production");
        request.setForceUpdate(forceUpdate);
        request.setMetadataFile(new MockMultipartFile(
            "metadataFile",
            "latest.json",
            "application/json",
            buildLatestJson(version, target, fileName).getBytes(StandardCharsets.UTF_8)
        ));
        request.setFile(new MockMultipartFile(
            "file",
            fileName,
            "application/octet-stream",
            content.getBytes(StandardCharsets.UTF_8)
        ));
        return request;
    }

    private void writeLegacyRelease(String version,
                                    String target,
                                    String fileName,
                                    String content,
                                    boolean forceUpdate) throws Exception {
        Path channelDirectory = tempDir.resolve("production");
        Path targetDirectory = channelDirectory.resolve(target);
        Files.createDirectories(targetDirectory);
        Files.write(targetDirectory.resolve(fileName), content.getBytes(StandardCharsets.UTF_8));
        Files.write(
            channelDirectory.resolve("latest.json"),
            buildLatestJson(version, target, fileName).getBytes(StandardCharsets.UTF_8)
        );
        String policyJson = "{"
            + "\"channel\":\"production\","
            + "\"latestVersion\":\"" + version + "\","
            + "\"forceUpdate\":" + forceUpdate + ","
            + "\"minSupportedVersion\":" + (forceUpdate ? "\"" + version + "\"" : "null")
            + "}";
        Files.write(channelDirectory.resolve("policy.json"), policyJson.getBytes(StandardCharsets.UTF_8));
    }

    private String buildLatestJson(String version, String target, String fileName) {
        return "{"
            + "\"version\":\"" + version + "\","
            + "\"notes\":\"release " + version + "\","
            + "\"pub_date\":\"2026-04-24T10:00:00Z\","
            + "\"platforms\":{"
            + "\"" + target + "\":{"
            + "\"signature\":\"signature-" + version + "\","
            + "\"url\":\"https://example.com/" + fileName + "\""
            + "}"
            + "}"
            + "}";
    }

    private String buildLatestJson(String version,
                                   String firstTarget,
                                   String firstFileName,
                                   String secondTarget,
                                   String secondFileName) {
        return "{"
            + "\"version\":\"" + version + "\","
            + "\"notes\":\"release " + version + "\","
            + "\"pub_date\":\"2026-04-24T10:00:00Z\","
            + "\"platforms\":{"
            + "\"" + firstTarget + "\":{"
            + "\"signature\":\"signature-" + firstTarget + "\","
            + "\"url\":\"https://example.com/" + firstFileName + "\""
            + "},"
            + "\"" + secondTarget + "\":{"
            + "\"signature\":\"signature-" + secondTarget + "\","
            + "\"url\":\"https://example.com/" + secondFileName + "\""
            + "}"
            + "}"
            + "}";
    }
}
