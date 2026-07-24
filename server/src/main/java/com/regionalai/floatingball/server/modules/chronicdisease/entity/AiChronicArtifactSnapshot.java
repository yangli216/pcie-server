package com.regionalai.floatingball.server.modules.chronicdisease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_chronic_artifact")
public class AiChronicArtifactSnapshot extends BaseEntity {

    @TableId(value = "id_snapshot", type = IdType.ASSIGN_UUID)
    private String idSnapshot;

    @TableField("request_id")
    private String requestId;

    @TableField("artifact_type")
    private String artifactType;

    @TableField("id_device")
    private String idDevice;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_his_org")
    private String idHisOrg;

    @TableField("na_his_org")
    private String naHisOrg;

    @TableField("patient_id")
    private String patientId;

    @TableField("visit_id")
    private String visitId;

    @TableField("patient_name")
    private String patientName;

    @TableField("disease_types_json")
    private String diseaseTypesJson;

    @TableField("data_as_of")
    private LocalDateTime dataAsOf;

    @TableField("assessment_year")
    private Integer assessmentYear;

    @TableField("template_versions_json")
    private String templateVersionsJson;

    @TableField("path_versions_json")
    private String pathVersionsJson;

    @TableField("evidence_versions_json")
    private String evidenceVersionsJson;

    @TableField("rule_version")
    private String ruleVersion;

    @TableField("summary_text")
    private String summaryText;

    @TableField("systolic_pressure")
    private Integer systolicPressure;

    @TableField("diastolic_pressure")
    private Integer diastolicPressure;

    @TableField("blood_glucose")
    private BigDecimal bloodGlucose;

    @TableField("bp_record_count")
    private Integer bloodPressureRecordCount;

    @TableField("glucose_record_count")
    private Integer bloodGlucoseRecordCount;

    @TableField("accepted_items_json")
    private String acceptedItemsJson;

    @TableField("doctor_notes")
    private String doctorNotes;

    @TableField("id_doctor")
    private String idDoctor;

    @TableField("na_doctor")
    private String naDoctor;

    @TableField("save_status")
    private String saveStatus;
}
