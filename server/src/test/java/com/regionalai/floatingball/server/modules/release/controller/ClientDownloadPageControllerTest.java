package com.regionalai.floatingball.server.modules.release.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseUploadRequest;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDownloadPageControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadPageShouldRenderEmptyStateWhenNoReleaseExists() {
        ReleaseService releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
        ClientDownloadPageController controller = new ClientDownloadPageController(releaseService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/client-download");

        ResponseEntity<String> response = controller.downloadPage("production", request);

        assertTrue(response.getBody().contains("全医慧助（PCIE）客户端下载"));
        assertTrue(response.getBody().contains("暂无可下载客户端"));
        assertTrue(response.getBody().contains("/client-download?channel=testing"));
        assertTrue(response.getBody().contains("/client-download?channel=win7-production"));
        assertTrue(response.getBody().contains("/client-download?channel=win7-testing"));
    }

    @Test
    void downloadPageShouldAcceptWin7ReleaseChannel() {
        ReleaseService releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
        ClientDownloadPageController controller = new ClientDownloadPageController(releaseService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/client-download");

        ResponseEntity<String> response = controller.downloadPage("win7-production", request);

        assertTrue(response.getBody().contains("class=\"tab active\" href=\"/client-download?channel=win7-production\""));
    }

    @Test
    void downloadPageShouldRenderCurrentReleaseDownloadLink() {
        ReleaseService releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
        releaseService.upload(uploadRequest());
        ClientDownloadPageController controller = new ClientDownloadPageController(releaseService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/client-download");

        ResponseEntity<String> response = controller.downloadPage("production", request);

        assertTrue(response.getBody().contains("PCIE_1.2.15_aarch64.app.tar.gz"));
        assertTrue(response.getBody().contains("darwin-aarch64"));
        assertTrue(response.getBody().contains("http://release.local/v1/client/releases/production/files/darwin-aarch64/PCIE_1.2.15_aarch64.app.tar.gz"));
    }

    private ReleaseUploadRequest uploadRequest() {
        ReleaseUploadRequest request = new ReleaseUploadRequest();
        request.setChannel("production");
        request.setMetadataFile(new MockMultipartFile(
            "metadataFile",
            "latest.json",
            "application/json",
            buildLatestJson().getBytes(StandardCharsets.UTF_8)
        ));
        request.setFile(new MockMultipartFile(
            "file",
            "PCIE_1.2.15_aarch64.app.tar.gz",
            "application/octet-stream",
            "package-1.2.15".getBytes(StandardCharsets.UTF_8)
        ));
        return request;
    }

    private String buildLatestJson() {
        return "{"
            + "\"version\":\"1.2.15\","
            + "\"notes\":\"release 1.2.15\","
            + "\"pub_date\":\"2026-04-24T10:00:00Z\","
            + "\"platforms\":{"
            + "\"darwin-aarch64\":{"
            + "\"signature\":\"signature-1.2.15\","
            + "\"url\":\"https://example.com/PCIE_1.2.15_aarch64.app.tar.gz\""
            + "}"
            + "}"
            + "}";
    }
}
