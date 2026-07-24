package com.regionalai.floatingball.server.modules.chronicdisease.service;

import com.regionalai.floatingball.server.modules.chronicdisease.entity.AiChronicArtifactSnapshot;
import com.regionalai.floatingball.server.modules.chronicdisease.mapper.AiChronicArtifactSnapshotMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChronicArtifactSnapshotWriter {

    private final AiChronicArtifactSnapshotMapper snapshotMapper;

    public ChronicArtifactSnapshotWriter(AiChronicArtifactSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AiChronicArtifactSnapshot entity) {
        snapshotMapper.insert(entity);
    }
}
