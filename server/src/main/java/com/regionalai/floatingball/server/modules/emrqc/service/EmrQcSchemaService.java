package com.regionalai.floatingball.server.modules.emrqc.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcFindingMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcReviewLogMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmrQcSchemaService {

    private static final Logger log = LoggerFactory.getLogger(EmrQcSchemaService.class);

    private final AiEmrQcRecordMapper recordMapper;
    private final AiEmrQcFindingMapper findingMapper;
    private final AiEmrQcTaskMapper taskMapper;
    private final AiEmrQcReviewLogMapper reviewLogMapper;

    private volatile boolean ready;

    public EmrQcSchemaService(AiEmrQcRecordMapper recordMapper,
                              AiEmrQcFindingMapper findingMapper,
                              AiEmrQcTaskMapper taskMapper,
                              AiEmrQcReviewLogMapper reviewLogMapper) {
        this.recordMapper = recordMapper;
        this.findingMapper = findingMapper;
        this.taskMapper = taskMapper;
        this.reviewLogMapper = reviewLogMapper;
    }

    public void requireReady() {
        if (ready) {
            return;
        }
        try {
            recordMapper.selectCount(new QueryWrapper<>());
            recordMapper.selectCount(new QueryWrapper<com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcRecord>()
                .isNull("patient_name").isNull("gender_code").isNull("patient_age")
                .isNull("id_card_encrypted").isNull("archives_encrypted").isNull("doctor_user_id")
                .isNull("phis_create_time").isNull("snapshot_complete"));
            findingMapper.selectCount(new QueryWrapper<>());
            taskMapper.selectCount(new QueryWrapper<>());
            reviewLogMapper.selectCount(new QueryWrapper<>());
            ready = true;
        } catch (Exception ex) {
            log.warn("emr qc schema not ready: {}", ex.getMessage());
            throw new ServiceUnavailableException("EMR-QC-SCHEMA",
                "门诊病历质控数据表缺失或不可读，请联系 DBA 执行 init.sql 迁移");
        }
    }
}
