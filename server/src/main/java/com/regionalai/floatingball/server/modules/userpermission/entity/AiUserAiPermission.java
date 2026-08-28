package com.regionalai.floatingball.server.modules.userpermission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_user_ai_permission")
public class AiUserAiPermission extends BaseEntity {

    @TableId(value = "id_permission", type = IdType.ASSIGN_UUID)
    private String idPermission;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("org_id")
    private String orgId;

    @TableField("org_name")
    private String orgName;

    @TableField("person_id")
    private String personId;

    @TableField("user_id")
    private String userId;

    @TableField("person_cd")
    private String personCd;

    @TableField("person_name")
    private String personName;

    @TableField("dept_id")
    private String deptId;

    @TableField("dept_name")
    private String deptName;

    @TableField("subject_key")
    private String subjectKey;

    @TableField("sd_status")
    private String sdStatus;

    @TableField("operator_user_id")
    private String operatorUserId;

    @TableField("operator_user_name")
    private String operatorUserName;
}
