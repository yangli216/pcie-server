package com.regionalai.floatingball.server.modules.release.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.release.dto.TauriLatestJson;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientReleaseControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void latestShouldReturnNoContentWhenChannelHasNoRelease() {
        ReleaseService releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
        ClientReleaseController controller = new ClientReleaseController(releaseService);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/v1/client/releases/production/latest.json"
        );

        ResponseEntity<TauriLatestJson> response = controller.latest("production", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void versionedDownloadShouldResolveExactVersion() throws Exception {
        Path packagePath = tempDir.resolve("PCIE-setup.zip");
        Files.write(packagePath, "package-v1".getBytes(StandardCharsets.UTF_8));
        ReleaseService releaseService = mock(ReleaseService.class);
        when(releaseService.resolveFile("production", "1.0.0", "windows-x86_64", "PCIE-setup.zip"))
            .thenReturn(packagePath);
        ClientReleaseController controller = new ClientReleaseController(releaseService);

        ResponseEntity<Resource> response = controller.downloadVersion(
            "production",
            "1.0.0",
            "windows-x86_64",
            "PCIE-setup.zip"
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("package-v1", new String(Files.readAllBytes(response.getBody().getFile().toPath()), StandardCharsets.UTF_8));
        verify(releaseService).resolveFile("production", "1.0.0", "windows-x86_64", "PCIE-setup.zip");
    }
}
