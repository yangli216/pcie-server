package com.regionalai.floatingball.server.modules.auth.bbp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_bbp_admin_grant")
public class BbpAdminGrant extends BaseEntity {

    @TableId(value = "id_grant", type = IdType.ASSIGN_UUID)
    private String idGrant;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("org_id")
    private String orgId;

    @TableField("org_name")
    private String orgName;

    @TableField("bbp_user_id")
    private String bbpUserId;

    @TableField("person_id")
    private String personId;

    @TableField("login_name")
    private String loginName;

    @TableField("person_name")
    private String personName;

    @TableField("role_code")
    private String roleCode;

    @TableField("sd_status")
    private String sdStatus;

    @TableField("operator_user_id")
    private String operatorUserId;

    @TableField("operator_user_name")
    private String operatorUserName;
}
