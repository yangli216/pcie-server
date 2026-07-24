package com.regionalai.floatingball.server.modules.chronicdisease.service;

import com.regionalai.floatingball.server.modules.chronicdisease.entity.AiChronicDiseaseFollowUp;
import com.regionalai.floatingball.server.modules.chronicdisease.mapper.AiChronicDiseaseFollowUpMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChronicDiseaseFollowUpWriter {

    private final AiChronicDiseaseFollowUpMapper followUpMapper;

    public ChronicDiseaseFollowUpWriter(AiChronicDiseaseFollowUpMapper followUpMapper) {
        this.followUpMapper = followUpMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AiChronicDiseaseFollowUp entity) {
        followUpMapper.insert(entity);
    }
}
