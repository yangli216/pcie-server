package com.regionalai.floatingball.server.modules.release.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.release.dto.TauriLatestJson;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void latestShouldAcceptEmptyWin7ReleaseChannel() {
        ReleaseService releaseService = new ReleaseService(tempDir.toString(), "http://release.local", new ObjectMapper());
        ClientReleaseController controller = new ClientReleaseController(releaseService);
        MockHttpServletRequest request = new MockHttpServletRequest(
            "GET",
            "/v1/client/releases/win7-production/latest.json"
        );

        ResponseEntity<TauriLatestJson> response = controller.latest("win7-production", request);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }
}
