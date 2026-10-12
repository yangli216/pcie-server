package com.regionalai.floatingball.server.modules.publichealth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.regionalai.floatingball.server.common.model.BaseEntity;

@Data @EqualsAndHashCode(callSuper = true) @TableName("c_ai_ph_run")
public class PublicHealthRun extends BaseEntity {
    @TableId(value = "generation_id", type = IdType.INPUT)
    private String generationId;
    @TableField("id_org")
    private String idOrg;
    @TableField("id_device")
    private String idDevice;
    @TableField("origin_request_id")
    private String originRequestId;
    @TableField("request_id")
    private String requestId;
    @TableField("input_hash")
    private String inputHash;
    @TableField("status")
    private String status;
    @TableField("result_json")
    private String resultJson;
    @TableField("error_code")
    private String errorCode;
    @TableField("error_message")
    private String errorMessage;
    @TableField("expires_at_ms")
    private Long expiresAtMs;
    @TableField("finished_at")
    private String finishedAt;
}
