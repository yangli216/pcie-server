package com.regionalai.floatingball.server.modules.release.controller;

import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyView;
import com.regionalai.floatingball.server.modules.release.dto.TauriLatestJson;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.nio.file.Path;

@RestController
@RequestMapping("/v1/client/releases")
public class ClientReleaseController {

    private final ReleaseService releaseService;

    public ClientReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping("/{channel}/latest.json")
    public ResponseEntity<TauriLatestJson> latest(@PathVariable String channel, HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath(null)
            .replaceQuery(null)
            .build()
            .toUriString();
        TauriLatestJson latestJson = releaseService.getAvailableLatestJson(channel, releaseService.normalizeExternalBaseUrl(baseUrl));
        if (latestJson == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(latestJson);
    }

    @GetMapping("/{channel}/policy.json")
    public ReleasePolicyView policy(@PathVariable String channel, HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath(null)
            .replaceQuery(null)
            .build()
            .toUriString();
        return releaseService.getPolicy(channel, releaseService.normalizeExternalBaseUrl(baseUrl));
    }

    @GetMapping("/{channel}/files/{target}/{fileName:.+}")
    public ResponseEntity<Resource> download(@PathVariable String channel,
                                             @PathVariable String target,
                                             @PathVariable String fileName) {
        Path filePath = releaseService.resolveFile(channel, target, fileName);
        return downloadResponse(fileName, filePath);
    }

    @GetMapping("/{channel}/files/{version}/{target}/{fileName:.+}")
    public ResponseEntity<Resource> downloadVersion(@PathVariable String channel,
                                                    @PathVariable String version,
                                                    @PathVariable String target,
                                                    @PathVariable String fileName) {
        Path filePath = releaseService.resolveFile(channel, version, target, fileName);
        return downloadResponse(fileName, filePath);
    }

    private ResponseEntity<Resource> downloadResponse(String fileName, Path filePath) {
        Resource resource = new FileSystemResource(filePath.toFile());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .body(resource);
    }
}
