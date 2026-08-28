package com.regionalai.floatingball.server.modules.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void historyShouldUseUnifiedPagination() {
        upload("1.2.15", "darwin-aarch64", "PCIE_1.2.15_aarch64.app.tar.gz", false);
        upload("1.2.16", "windows-x86_64", "PCIE_1.2.16_x64-setup.nsis.zip", true);

        PageResponse<ReleaseHistoryView> page = releaseService.history("production", 0, 500);

        assertEquals(1L, page.getCurrent());
        assertEquals(100L, page.getSize());
        assertTrue(page.getTotal() >= 2L);
        assertTrue(page.getRecords().stream().anyMatch(item -> "1.2.16".equals(item.getVersion())));
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
        assertTrue(macItem.getDownloadUrl().startsWith("http://release.lan:8080/v1/client/releases/production/files/darwin-aarch64/"));
        assertTrue(macItem.getFileSize() > 0);
    }

    @Test
    void win7ReleaseChannelsShouldRemainIsolatedFromRegularWindowsChannels() {
        uploadToChannel(
            "win7-production",
            "1.4.6",
            "windows-x86_64",
            "PCIE-Win7-Legacy_1.4.6_x64_zh-CN.msi.zip",
            true
        );

        TauriLatestJson win7Latest = releaseService.getLatestJson("win7-production");
        ReleasePolicyView win7Policy = releaseService.getPolicy("win7-production");
        assertEquals("1.4.6", win7Latest.getVersion());
        assertTrue(win7Latest.getPlatforms().containsKey("windows-x86_64"));
        assertTrue(Boolean.TRUE.equals(win7Policy.getForceUpdate()));
        assertEquals("1.4.6", win7Policy.getMinSupportedVersion());

        assertNull(releaseService.getAvailableLatestJson("production", null));
        assertFalse(Boolean.TRUE.equals(releaseService.getPolicy("production").getForceUpdate()));

        List<ReleaseView> channels = releaseService.list(null);
        assertEquals(4, channels.size());
        assertTrue(channels.stream().anyMatch(item -> "win7-testing".equals(item.getChannel())));
    }

    @Test
    void win7ReleaseUploadShouldAdvanceBeyondHistoricalMaximumAndUseRollbackForDowngrades() {
        uploadToChannel("win7-testing", "1.4.6", "windows-x86_64", "PCIE-Win7_1.4.6.msi.zip", false);
        uploadToChannel("win7-testing", "1.4.7", "windows-x86_64", "PCIE-Win7_1.4.7.msi.zip", false);

        ReleaseRollbackRequest rollbackRequest = new ReleaseRollbackRequest();
        rollbackRequest.setChannel("win7-testing");
        rollbackRequest.setVersion("1.4.6");
        releaseService.rollback(rollbackRequest);

        BusinessException reusedVersion = assertThrows(
            BusinessException.class,
            () -> uploadToChannel("win7-testing", "1.4.7", "windows-x86_64", "PCIE-Win7_1.4.7-reused.msi.zip", false)
        );
        assertEquals("RELEASE-VERSION", reusedVersion.getCode());
        assertTrue(reusedVersion.getMessage().contains("1.4.7"));

        BusinessException lowerVersion = assertThrows(
            BusinessException.class,
            () -> uploadToChannel("win7-testing", "1.4.5", "windows-x86_64", "PCIE-Win7_1.4.5.msi.zip", false)
        );
        assertEquals("RELEASE-VERSION", lowerVersion.getCode());

        uploadToChannel("win7-testing", "1.4.8", "windows-x86_64", "PCIE-Win7_1.4.8.msi.zip", false);
        assertEquals("1.4.8", releaseService.getLatestJson("win7-testing").getVersion());
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

    private void upload(String version, String target, String fileName, boolean forceUpdate) {
        uploadToChannel("production", version, target, fileName, forceUpdate);
    }

    private void uploadToChannel(String channel,
                                 String version,
                                 String target,
                                 String fileName,
                                 boolean forceUpdate) {
        ReleaseUploadRequest request = new ReleaseUploadRequest();
        request.setChannel(channel);
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
            ("package-" + version).getBytes(StandardCharsets.UTF_8)
        ));
        releaseService.upload(request);
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
