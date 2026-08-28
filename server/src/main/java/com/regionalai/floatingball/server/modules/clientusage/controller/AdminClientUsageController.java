package com.regionalai.floatingball.server.modules.clientusage.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageItemVO;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageQueryDTO;
import com.regionalai.floatingball.server.modules.clientusage.service.ClientUsageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/admin/api/client-usage")
public class AdminClientUsageController {

    private final ClientUsageService clientUsageService;

    public AdminClientUsageController(ClientUsageService clientUsageService) {
        this.clientUsageService = clientUsageService;
    }

    @GetMapping
    public ApiResponse<PageResponse<ClientUsageItemVO>> list(ClientUsageQueryDTO query,
                                                             @RequestParam(defaultValue = "1") long current,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             HttpServletRequest request) {
        return ApiResponse.success(
            clientUsageService.list(query, current, size),
            RequestIdUtils.resolve(request)
        );
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> export(ClientUsageQueryDTO query) {
        byte[] data = clientUsageService.exportExcel(query);
        String fileName = "client-usage-" + System.currentTimeMillis() + ".xlsx";
        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString())
            .contentLength(data.length)
            .body(resource);
    }
}
