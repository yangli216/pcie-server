package com.regionalai.floatingball.server.modules.chronicdisease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_chronic_followup")
public class AiChronicDiseaseFollowUp extends BaseEntity {

    @TableId(value = "id_followup", type = IdType.ASSIGN_UUID)
    private String idFollowUp;

    @TableField("request_id")
    private String requestId;

    @TableField("id_device")
    private String idDevice;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_phr")
    private String idPhr;

    @TableField("id_record")
    private String idRecord;

    @TableField("source_form_id")
    private String sourceFormId;

    @TableField("visit_status")
    private String visitStatus;

    @TableField("sd_visit_kind")
    private String sdVisitKind;

    @TableField("dt_hy_plan")
    private String dtHyPlan;

    @TableField("dt_dbs_plan")
    private String dtDbsPlan;

    @TableField("input_user")
    private String inputUser;

    @TableField("id_user")
    private String idUser;

    @TableField("form_data_json")
    private String formDataJson;

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

    @TableField("disease_type")
    private String diseaseType;

    @TableField("management_source")
    private String managementSource;

    @TableField("management_evidence")
    private String managementEvidence;

    @TableField("template_version")
    private String templateVersion;

    @TableField("path_version")
    private String pathVersion;

    @TableField("evidence_version")
    private String evidenceVersion;

    @TableField("rule_version")
    private String ruleVersion;

    @TableField("followup_date")
    private LocalDate followUpDate;

    @TableField("followup_method")
    private String followUpMethod;

    @TableField("symptom_codes")
    private String symptomCodes;

    @TableField("systolic_pressure")
    private Integer systolicPressure;

    @TableField("diastolic_pressure")
    private Integer diastolicPressure;

    @TableField("fasting_glucose")
    private BigDecimal fastingGlucose;

    @TableField("postprandial_glucose")
    private BigDecimal postprandialGlucose;

    @TableField("height_cm")
    private BigDecimal heightCm;

    @TableField("weight_kg")
    private BigDecimal weightKg;

    @TableField("bmi")
    private BigDecimal bmi;

    @TableField("waist_cm")
    private BigDecimal waistCm;

    @TableField("daily_cigarettes")
    private Integer dailyCigarettes;

    @TableField("daily_alcohol_units")
    private BigDecimal dailyAlcoholUnits;

    @TableField("weekly_exercise_sessions")
    private Integer weeklyExerciseSessions;

    @TableField("exercise_minutes")
    private Integer exerciseMinutes;

    @TableField("salt_intake_level")
    private String saltIntakeLevel;

    @TableField("psychological_status")
    private String psychologicalStatus;

    @TableField("medication_adherence")
    private String medicationAdherence;

    @TableField("adverse_reaction")
    private String adverseReaction;

    @TableField("adverse_reaction_text")
    private String adverseReactionText;

    @TableField("medication_summary")
    private String medicationSummary;

    @TableField("followup_classification")
    private String followUpClassification;

    @TableField("referral_required")
    private String referralRequired;

    @TableField("referral_reason")
    private String referralReason;

    @TableField("referral_organization")
    private String referralOrganization;

    @TableField("next_followup_date")
    private LocalDate nextFollowUpDate;

    @TableField("id_doctor")
    private String idDoctor;

    @TableField("na_doctor")
    private String naDoctor;

    @TableField("notes")
    private String notes;

    @TableField("save_status")
    private String saveStatus;
}
