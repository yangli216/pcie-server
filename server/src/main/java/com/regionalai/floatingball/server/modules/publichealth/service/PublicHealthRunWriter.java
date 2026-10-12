package com.regionalai.floatingball.server.modules.publichealth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.regionalai.floatingball.server.modules.publichealth.entity.*;
import com.regionalai.floatingball.server.modules.publichealth.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;

@Service
public class PublicHealthRunWriter {
    private final PublicHealthOriginMapper origins;
    private final PublicHealthRunMapper runs;
    public PublicHealthRunWriter(PublicHealthOriginMapper origins, PublicHealthRunMapper runs) {
        this.origins = origins;
        this.runs = runs;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserve(PublicHealthOrigin origin, PublicHealthRun run, boolean allowSexCorrection) {
        PublicHealthOrigin old = origins.selectOne(new LambdaQueryWrapper<PublicHealthOrigin>()
                .eq(PublicHealthOrigin::getIdOrg, origin.getIdOrg())
                .eq(PublicHealthOrigin::getIdDevice, origin.getIdDevice())
                .eq(PublicHealthOrigin::getOriginRequestId, origin.getOriginRequestId()));
        if (old == null) {
            origins.insert(origin);
        } else if (!old.getBindingHash().equals(origin.getBindingHash())) {
            throw new PublicHealthException(409,"TASK_CONTEXT_MISMATCH","原调入任务与当前业务身份不匹配");
        }
        if (old != null && !old.getPatientSex().equals(origin.getPatientSex())) {
            if (!"unknown".equals(old.getPatientSex()) || "unknown".equals(origin.getPatientSex()) || !allowSexCorrection) {
                throw new PublicHealthException(409, "TASK_CONTEXT_MISMATCH","该任务的患者性别不能变更");
            }
            int updated = origins.update(null, new LambdaUpdateWrapper<PublicHealthOrigin>()
                    .eq(PublicHealthOrigin::getIdOrigin, old.getIdOrigin())
                    .eq(PublicHealthOrigin::getPatientSex, "unknown")
                    .set(PublicHealthOrigin::getPatientSex, origin.getPatientSex()));
            if (updated != 1) {
                PublicHealthOrigin latest = origins.selectById(old.getIdOrigin());
                if (latest == null || !origin.getPatientSex().equals(latest.getPatientSex())) {
                    throw new PublicHealthException(409,"TASK_CONTEXT_MISMATCH", "患者性别已由其它请求确认");
                }
            }
        }
        runs.insert(run);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(String generationId, String status, String result, String code, String message) {
        return runs.update(null, new LambdaUpdateWrapper<PublicHealthRun>()
                .eq(PublicHealthRun::getGenerationId, generationId)
                .eq(PublicHealthRun::getStatus, "RUNNING")
                .set(PublicHealthRun::getStatus, status)
                .set(PublicHealthRun::getResultJson, result)
                .set(PublicHealthRun::getErrorCode, code)
                .set(PublicHealthRun::getErrorMessage, message)
                .set(PublicHealthRun::getFinishedAt, OffsetDateTime.now().toString())) == 1;
    }
}
