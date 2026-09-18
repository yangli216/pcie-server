package com.regionalai.floatingball.server.modules.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_op_log")
public class AiOpLog extends BaseEntity {

    @TableId(value = "id_log", type = IdType.ASSIGN_UUID)
    private String idLog;

    @TableField("id_device")
    private String idDevice;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_his_org")
    private String hisOrgId;

    @TableField("na_his_org")
    private String hisOrgName;

    @TableField("sd_log_type")
    private String sdLogType;

    @TableField("na_module")
    private String naModule;

    @TableField(exist = false)
    private String displayModule;

    @TableField("op_action")
    private String opAction;

    @TableField(exist = false)
    private String displayAction;

    @TableField("op_title")
    private String opTitle;

    @TableField(exist = false)
    private String displayTitle;

    @TableField("source_module")
    private String sourceModule;

    @TableField(exist = false)
    private String displaySourceModule;

    @TableField("scene_code")
    private String sceneCode;

    @TableField(exist = false)
    private String displayScene;

    @TableField("trace_id")
    private String traceId;

    @TableField("des_op")
    private String desOp;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("audio_file_path")
    private String audioFilePath;

    @TableField("consultation_id")
    private String consultationId;

    @TableField("op_result")
    private String opResult;

    @TableField(exist = false)
    private String provider;

    @TableField(exist = false)
    private String model;

    @TableField(exist = false)
    private Long durationMs;

    @TableField(exist = false)
    private Long firstTokenMs;

    @TableField("operation_time")
    private LocalDateTime operationTime;
}
