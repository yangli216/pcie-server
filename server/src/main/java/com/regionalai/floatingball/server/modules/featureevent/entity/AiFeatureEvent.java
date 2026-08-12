package com.regionalai.floatingball.server.modules.featureevent.entity;

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
@TableName("c_ai_feature_event")
public class AiFeatureEvent extends BaseEntity {

    @TableId(value = "id_event", type = IdType.ASSIGN_UUID)
    private String idEvent;

    @TableField("id_device")
    private String idDevice;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_region")
    private String idRegion;

    @TableField("id_his_org")
    private String hisOrgId;

    @TableField("na_his_org")
    private String hisOrgName;

    @TableField("feature_code")
    private String featureCode;

    @TableField("feature_name")
    private String featureName;

    @TableField("event_action")
    private String eventAction;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("trace_id")
    private String traceId;

    @TableField("consultation_id")
    private String consultationId;

    @TableField("session_id")
    private String sessionId;

    @TableField("source_module")
    private String sourceModule;

    @TableField("scene_code")
    private String sceneCode;

    @TableField("id_doctor")
    private String idDoctor;

    @TableField("cd_doctor")
    private String doctorWorkNo;

    @TableField("na_doctor")
    private String naDoctor;

    @TableField("id_dept")
    private String idDept;

    @TableField("na_dept")
    private String naDept;

    @TableField("event_status")
    private String eventStatus;

    @TableField("client_version")
    private String clientVersion;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("event_time")
    private LocalDateTime eventTime;
}
