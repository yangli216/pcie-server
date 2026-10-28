package com.regionalai.floatingball.server.modules.emrqc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_emr_qc_review_log")
public class AiEmrQcReviewLog extends BaseEntity {

    @TableId(value = "id_log", type = IdType.ASSIGN_UUID)
    private String idLog;

    @TableField("id_org")
    private String idOrg;

    @TableField("operator_id")
    private String operatorId;

    @TableField("operator_name")
    private String operatorName;

    @TableField("action")
    private String action;

    @TableField("id_medrecdoc")
    private String idMedrecdoc;

    @TableField("id_task")
    private String idTask;

    @TableField("request_id")
    private String requestId;

    @TableField("result_status")
    private String resultStatus;
}
