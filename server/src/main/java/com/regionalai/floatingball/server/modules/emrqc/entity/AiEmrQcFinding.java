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
@TableName("c_ai_emr_qc_finding")
public class AiEmrQcFinding extends BaseEntity {

    @TableId(value = "id_finding", type = IdType.ASSIGN_UUID)
    private String idFinding;

    @TableField("id_qc_record")
    private String idQcRecord;

    @TableField("id_analysis")
    private String idAnalysis;

    @TableField("sort_no")
    private Integer sortNo;

    @TableField("severity")
    private String severity;

    @TableField("category")
    private String category;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("section_name")
    private String sectionName;

    @TableField("excerpt")
    private String excerpt;

    @TableField("issue")
    private String issue;

    @TableField("suggestion")
    private String suggestion;

    @TableField("deduction")
    private Integer deduction;

    @TableField("ignored")
    private Boolean ignored;
}
