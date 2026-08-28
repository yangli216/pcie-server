package com.regionalai.floatingball.server.modules.emrtemplate.entity;

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
@TableName("c_ai_outpatient_emr_tpl_snapshot")
public class AiOutpatientEmrTemplateSnapshot extends BaseEntity {

    @TableId(value = "id_snapshot", type = IdType.ASSIGN_UUID)
    private String idSnapshot;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_region")
    private String idRegion;

    @TableField("id_device")
    private String idDevice;

    @TableField("cd_device")
    private String cdDevice;

    @TableField("template_id")
    private String templateId;

    @TableField("template_name")
    private String templateName;

    @TableField("template_hash")
    private String templateHash;

    @TableField("template_html")
    private String templateHtml;

    @TableField("template_definition")
    private String templateDefinition;

    @TableField("parse_result_json")
    private String parseResultJson;

    @TableField("field_count")
    private Integer fieldCount;

    @TableField("writable_field_count")
    private Integer writableFieldCount;

    @TableField("dictionary_field_count")
    private Integer dictionaryFieldCount;

    @TableField("mapped_field_count")
    private Integer mappedFieldCount;

    @TableField("dt_last_received")
    private LocalDateTime lastReceivedAt;
}
