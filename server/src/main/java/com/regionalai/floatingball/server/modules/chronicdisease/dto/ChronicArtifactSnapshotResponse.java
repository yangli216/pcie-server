package com.regionalai.floatingball.server.modules.chronicdisease.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ChronicArtifactSnapshotResponse {
    private String snapshotId;
    private String requestId;
    private String status;
    private LocalDateTime savedAt;
    private String artifactType;
}
