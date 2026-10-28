package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcReviewLog;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcReviewLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmrQcAuditService {

    private static final Logger log = LoggerFactory.getLogger(EmrQcAuditService.class);

    private final AiEmrQcReviewLogMapper reviewLogMapper;

    public EmrQcAuditService(AiEmrQcReviewLogMapper reviewLogMapper) {
        this.reviewLogMapper = reviewLogMapper;
    }

    public void record(EmrQcContext context,
                       String action,
                       String idMedrecdoc,
                       String idTask,
                       String resultStatus) {
        try {
            AiEmrQcReviewLog entity = new AiEmrQcReviewLog();
            entity.setIdOrg(context.getIdOrg());
            entity.setOperatorId(context.getOperatorId());
            entity.setOperatorName(context.getOperatorName());
            entity.setAction(action);
            entity.setIdMedrecdoc(idMedrecdoc);
            entity.setIdTask(idTask);
            entity.setRequestId(context.getRequestId());
            entity.setResultStatus(resultStatus);
            reviewLogMapper.insert(entity);
        } catch (Exception ex) {
            log.warn("emr qc audit log insert failed. action={}, error={}", action, ex.getMessage());
        }
    }
}
