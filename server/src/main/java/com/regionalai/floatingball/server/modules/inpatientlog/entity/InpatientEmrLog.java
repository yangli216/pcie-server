package com.regionalai.floatingball.server.modules.inpatientlog.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
@Data @EqualsAndHashCode(callSuper = true)
@TableName("c_ai_inpatient_emr_log")
public class InpatientEmrLog extends BaseEntity {
    @TableId(value = "id_log", type = IdType.INPUT) private String idLog;
    private String idDevice;
    private String idOrg;
    private String generationId;
    private String admissionId;
    private String templateId;
    private String templateName;
    private String templateHash;
    private String hisOrgId;
    private String orgName;
    private String doctorId;
    private String doctorName;
    private String patientId;
    private String patientName;
    private String patientGender;
    private String patientAge;
    private String recordTime;
    private LocalDateTime generationTime;
    private String status;
    private Integer latestSequence;
    private String snapshotJson;
}
