-- Oracle business schema initialization script.
-- Run this file after bootstrap.sql.
-- Before execution, switch to the target schema/user and make sure
-- the default tablespace has been prepared by DBA or execution context.
--
-- This file only contains object DDL and seed data.
-- It does not declare TABLESPACE clauses explicitly.
-- Connect with the same schema as FB_DB_USERNAME before running this file.
-- Current application default schema is RBMH_AI.
-- This file intentionally keeps only plain Oracle DDL/DML so it can run in
-- SQL Developer, Navicat, DBeaver and other generic SQL clients.
--
-- Writing convention:
-- 1. CREATE TABLE
-- 2. COMMENT ON TABLE / COMMENT ON COLUMN
-- 3. CREATE INDEX
-- 4. Seed data at the end

CREATE TABLE c_ai_region (
    id_region            VARCHAR2(32) PRIMARY KEY,
    cd_region            VARCHAR2(64),
    na_region            VARCHAR2(128) NOT NULL,
    id_parent            VARCHAR2(32),
    sd_region_type       VARCHAR2(32),
    sd_status            VARCHAR2(2) DEFAULT '1' NOT NULL,
    sort_order           NUMBER(10) DEFAULT 0,
    des_region           VARCHAR2(500),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_region IS '区域表';
COMMENT ON COLUMN c_ai_region.id_region IS '区域主键ID';
COMMENT ON COLUMN c_ai_region.cd_region IS '区域编码';
COMMENT ON COLUMN c_ai_region.na_region IS '区域名称';
COMMENT ON COLUMN c_ai_region.id_parent IS '上级区域ID';
COMMENT ON COLUMN c_ai_region.sd_region_type IS '区域类型';
COMMENT ON COLUMN c_ai_region.sd_status IS '启停状态：1启用 0停用';
COMMENT ON COLUMN c_ai_region.sort_order IS '排序号';
COMMENT ON COLUMN c_ai_region.des_region IS '区域说明';
COMMENT ON COLUMN c_ai_region.fg_active IS '逻辑删除标记，不用于启停状态';
COMMENT ON COLUMN c_ai_region.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_region.update_time IS '更新时间';

CREATE INDEX idx_c_ai_region_active ON c_ai_region (fg_active, sd_status);


CREATE TABLE c_ai_org (
    id_org               VARCHAR2(32) PRIMARY KEY,
    cd_org               VARCHAR2(64) NOT NULL,
    na_org               VARCHAR2(128) NOT NULL,
    id_parent            VARCHAR2(32),
    id_region            VARCHAR2(32),
    sd_org_type          VARCHAR2(32),
    sd_status            VARCHAR2(2) DEFAULT '1' NOT NULL,
    sort_order           NUMBER(10) DEFAULT 0,
    des_org              VARCHAR2(500),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_org IS '机构表';
COMMENT ON COLUMN c_ai_org.id_org IS '机构主键ID';
COMMENT ON COLUMN c_ai_org.cd_org IS '机构编码，客户端注册使用，激活记录内唯一';
COMMENT ON COLUMN c_ai_org.na_org IS '机构名称';
COMMENT ON COLUMN c_ai_org.id_parent IS '上级机构ID';
COMMENT ON COLUMN c_ai_org.id_region IS '所属区域ID';
COMMENT ON COLUMN c_ai_org.sd_org_type IS '机构类型';
COMMENT ON COLUMN c_ai_org.sd_status IS '启停状态：1启用 0停用';
COMMENT ON COLUMN c_ai_org.sort_order IS '排序号';
COMMENT ON COLUMN c_ai_org.des_org IS '机构说明';
COMMENT ON COLUMN c_ai_org.fg_active IS '逻辑删除标记，不用于启停状态';
COMMENT ON COLUMN c_ai_org.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_org.update_time IS '更新时间';

CREATE INDEX idx_c_ai_org_active ON c_ai_org (fg_active, sd_status);
CREATE INDEX idx_c_ai_org_code ON c_ai_org (cd_org, fg_active);
CREATE UNIQUE INDEX uk_c_ai_org_code_active ON c_ai_org (
    CASE WHEN fg_active = '1' THEN cd_org END
);


CREATE TABLE c_ai_device (
    id_device            VARCHAR2(32) PRIMARY KEY,
    cd_device            VARCHAR2(128) NOT NULL,
    na_device            VARCHAR2(128),
    id_org               VARCHAR2(32) NOT NULL,
    id_region            VARCHAR2(32),
    id_bind_user         VARCHAR2(32),
    device_token         VARCHAR2(64) NOT NULL,
    device_public_key    VARCHAR2(1000),
    sd_status            VARCHAR2(2) DEFAULT '0' NOT NULL,
    dt_last_heartbeat    TIMESTAMP,
    dt_registered        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    client_version       VARCHAR2(64),
    os_info              VARCHAR2(500),
    register_ip          VARCHAR2(64),
    last_seen_ip         VARCHAR2(64),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_device IS '设备表';
COMMENT ON COLUMN c_ai_device.id_device IS '设备主键ID';
COMMENT ON COLUMN c_ai_device.cd_device IS '设备编码';
COMMENT ON COLUMN c_ai_device.na_device IS '设备名称';
COMMENT ON COLUMN c_ai_device.id_org IS '所属机构ID';
COMMENT ON COLUMN c_ai_device.id_region IS '所属区域ID';
COMMENT ON COLUMN c_ai_device.id_bind_user IS '绑定用户ID';
COMMENT ON COLUMN c_ai_device.device_token IS '设备令牌';
COMMENT ON COLUMN c_ai_device.device_public_key IS '设备ECDSA P-256公钥（SPKI DER base64）';
COMMENT ON COLUMN c_ai_device.sd_status IS '设备状态';
COMMENT ON COLUMN c_ai_device.dt_last_heartbeat IS '最后心跳时间';
COMMENT ON COLUMN c_ai_device.dt_registered IS '注册时间';
COMMENT ON COLUMN c_ai_device.client_version IS '客户端版本';
COMMENT ON COLUMN c_ai_device.os_info IS '操作系统信息';
COMMENT ON COLUMN c_ai_device.register_ip IS '注册来源IP';
COMMENT ON COLUMN c_ai_device.last_seen_ip IS '最近访问来源IP';
COMMENT ON COLUMN c_ai_device.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_device.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_device.update_time IS '更新时间';

CREATE INDEX idx_c_ai_device_org ON c_ai_device (id_org, fg_active);
CREATE INDEX idx_c_ai_device_token ON c_ai_device (device_token, fg_active);
CREATE INDEX idx_c_ai_device_register_ip ON c_ai_device (register_ip, fg_active);
CREATE INDEX idx_c_ai_device_last_seen_ip ON c_ai_device (last_seen_ip, fg_active);
CREATE UNIQUE INDEX uk_c_ai_device_code_org_active ON c_ai_device (
    CASE WHEN fg_active = '1' THEN id_org END,
    CASE WHEN fg_active = '1' THEN cd_device END
);
CREATE UNIQUE INDEX uk_c_ai_device_token_active ON c_ai_device (
    CASE WHEN fg_active = '1' THEN device_token END
);


CREATE TABLE c_ai_config (
    id_config                VARCHAR2(32) PRIMARY KEY,
    cd_config                VARCHAR2(64),
    na_config                VARCHAR2(128) NOT NULL,
    provider                 VARCHAR2(32),
    api_base_url             VARCHAR2(500),
    api_key_encrypted        VARCHAR2(1000),
    model_name               VARCHAR2(128),
    fast_model_name          VARCHAR2(128),
    enable_thinking          CHAR(1) DEFAULT '0' NOT NULL,
    audio_api_key_encrypted  VARCHAR2(1000),
    audio_base_url           VARCHAR2(500),
    audio_model              VARCHAR2(128),
    speech_provider          VARCHAR2(64),
    speech_realtime_url      VARCHAR2(500),
    speech_model             VARCHAR2(128),
    knowledge_base_enabled   CHAR(1) DEFAULT '0' NOT NULL,
    knowledge_base_base_url  VARCHAR2(500),
    pmphai_enabled           CHAR(1) DEFAULT '0' NOT NULL,
    pmphai_base_url          VARCHAR2(500),
    pmphai_app_key_encrypted VARCHAR2(1000),
    pmphai_app_secret_encrypted VARCHAR2(1000),
    reviewer_enabled         CHAR(1) DEFAULT '0' NOT NULL,
    reviewer_base_url        VARCHAR2(500),
    reviewer_api_key_encrypted VARCHAR2(1000),
    reviewer_model           VARCHAR2(128),
    reviewer_check_examination_enabled CHAR(1) DEFAULT '1' NOT NULL,
    features_json            CLOB,
    id_org                   VARCHAR2(32),
    id_region                VARCHAR2(32),
    sd_status                VARCHAR2(2) DEFAULT '1' NOT NULL,
    fg_active                CHAR(1) DEFAULT '1' NOT NULL,
    insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_config IS 'AI配置表';
COMMENT ON COLUMN c_ai_config.id_config IS '配置主键ID';
COMMENT ON COLUMN c_ai_config.cd_config IS '配置编码';
COMMENT ON COLUMN c_ai_config.na_config IS '配置名称';
COMMENT ON COLUMN c_ai_config.provider IS '服务提供商';
COMMENT ON COLUMN c_ai_config.api_base_url IS '模型接口基础地址';
COMMENT ON COLUMN c_ai_config.api_key_encrypted IS '加密后的接口密钥';
COMMENT ON COLUMN c_ai_config.model_name IS '模型名称';
COMMENT ON COLUMN c_ai_config.fast_model_name IS 'chatFast 独立模型名称';
COMMENT ON COLUMN c_ai_config.enable_thinking IS '是否启用思考模式';
COMMENT ON COLUMN c_ai_config.audio_api_key_encrypted IS '加密后的语音接口密钥，为空时复用主模型密钥';
COMMENT ON COLUMN c_ai_config.audio_base_url IS '语音批量转写 HTTP(S) 基础地址';
COMMENT ON COLUMN c_ai_config.audio_model IS '语音模型名称';
COMMENT ON COLUMN c_ai_config.speech_provider IS '语音服务提供商';
COMMENT ON COLUMN c_ai_config.speech_realtime_url IS '实时语音识别 WebSocket 上游地址';
COMMENT ON COLUMN c_ai_config.speech_model IS '实时语音识别模型';
COMMENT ON COLUMN c_ai_config.knowledge_base_enabled IS '知识库开关';
COMMENT ON COLUMN c_ai_config.knowledge_base_base_url IS '知识库服务地址';
COMMENT ON COLUMN c_ai_config.pmphai_enabled IS '人卫知识库开关';
COMMENT ON COLUMN c_ai_config.pmphai_base_url IS '人卫知识库服务地址';
COMMENT ON COLUMN c_ai_config.pmphai_app_key_encrypted IS '加密后的人卫知识库App Key';
COMMENT ON COLUMN c_ai_config.pmphai_app_secret_encrypted IS '加密后的人卫知识库App Secret';
COMMENT ON COLUMN c_ai_config.reviewer_enabled IS '审查模型开关';
COMMENT ON COLUMN c_ai_config.reviewer_base_url IS '审查模型服务地址';
COMMENT ON COLUMN c_ai_config.reviewer_api_key_encrypted IS '加密后的审查模型密钥';
COMMENT ON COLUMN c_ai_config.reviewer_model IS '审查模型名称';
COMMENT ON COLUMN c_ai_config.reviewer_check_examination_enabled IS '是否启用检查项目独立审查';
COMMENT ON COLUMN c_ai_config.features_json IS '功能开关配置JSON';
COMMENT ON COLUMN c_ai_config.id_org IS '机构级配置范围ID';
COMMENT ON COLUMN c_ai_config.id_region IS '区域级配置范围ID';
COMMENT ON COLUMN c_ai_config.sd_status IS '状态';
COMMENT ON COLUMN c_ai_config.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_config.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_config.update_time IS '更新时间';

CREATE INDEX idx_c_ai_config_scope ON c_ai_config (id_org, id_region, fg_active, sd_status);


CREATE TABLE c_ai_prompt (
    id_prompt            VARCHAR2(32) PRIMARY KEY,
    cd_prompt            VARCHAR2(128) NOT NULL,
    na_prompt            VARCHAR2(128) NOT NULL,
    sys_prompt           CLOB,
    user_template        CLOB,
    version_num          VARCHAR2(64),
    sd_prompt_type       VARCHAR2(64),
    sd_status            VARCHAR2(2) DEFAULT '0' NOT NULL,
    id_org               VARCHAR2(32),
    id_region            VARCHAR2(32),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_prompt IS 'Prompt模板表';
COMMENT ON COLUMN c_ai_prompt.id_prompt IS 'Prompt主键ID';
COMMENT ON COLUMN c_ai_prompt.cd_prompt IS 'Prompt编码';
COMMENT ON COLUMN c_ai_prompt.na_prompt IS 'Prompt名称';
COMMENT ON COLUMN c_ai_prompt.sys_prompt IS '系统提示词';
COMMENT ON COLUMN c_ai_prompt.user_template IS '用户提示词模板';
COMMENT ON COLUMN c_ai_prompt.version_num IS '版本号';
COMMENT ON COLUMN c_ai_prompt.sd_prompt_type IS 'Prompt类型';
COMMENT ON COLUMN c_ai_prompt.sd_status IS '状态';
COMMENT ON COLUMN c_ai_prompt.id_org IS '机构级作用范围ID';
COMMENT ON COLUMN c_ai_prompt.id_region IS '区域级作用范围ID';
COMMENT ON COLUMN c_ai_prompt.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_prompt.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_prompt.update_time IS '更新时间';

CREATE INDEX idx_c_ai_prompt_scope ON c_ai_prompt (cd_prompt, id_org, id_region, fg_active, sd_status);


CREATE TABLE c_ai_data_package (
    id_package           VARCHAR2(32) PRIMARY KEY,
    cd_package           VARCHAR2(128),
    na_package           VARCHAR2(128) NOT NULL,
    sd_package_type      VARCHAR2(32) NOT NULL,
    version_num          VARCHAR2(64),
    content_json         CLOB,
    sd_status            VARCHAR2(2) DEFAULT '0' NOT NULL,
    id_org               VARCHAR2(32),
    id_region            VARCHAR2(32),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_data_package IS '数据包表';
COMMENT ON COLUMN c_ai_data_package.id_package IS '数据包主键ID';
COMMENT ON COLUMN c_ai_data_package.cd_package IS '数据包编码';
COMMENT ON COLUMN c_ai_data_package.na_package IS '数据包名称';
COMMENT ON COLUMN c_ai_data_package.sd_package_type IS '数据包类型';
COMMENT ON COLUMN c_ai_data_package.version_num IS '版本号';
COMMENT ON COLUMN c_ai_data_package.content_json IS '数据包内容JSON';
COMMENT ON COLUMN c_ai_data_package.sd_status IS '状态';
COMMENT ON COLUMN c_ai_data_package.id_org IS '机构级作用范围ID';
COMMENT ON COLUMN c_ai_data_package.id_region IS '区域级作用范围ID';
COMMENT ON COLUMN c_ai_data_package.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_data_package.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_data_package.update_time IS '更新时间';

CREATE INDEX idx_c_ai_package_scope ON c_ai_data_package (sd_package_type, id_org, id_region, fg_active, sd_status);


CREATE TABLE c_ai_symptom_template (
    id_template              VARCHAR2(32) PRIMARY KEY,
    cd_symptom               VARCHAR2(128) NOT NULL,
    na_symptom               VARCHAR2(200) NOT NULL,
    sd_medical_mode          VARCHAR2(16) NOT NULL,
    des_symptom              VARCHAR2(1000),
    fg_common                CHAR(1) DEFAULT '0' NOT NULL,
    sort_order               NUMBER(10) DEFAULT 0,
    system_category_json     CLOB,
    system_category_tokens   VARCHAR2(1000),
    body_parts_json          CLOB,
    body_parts_tokens        VARCHAR2(1000),
    custom_script            CLOB,
    applicable_population_json CLOB,
    config_json              CLOB,
    tcm_metadata_json        CLOB,
    id_org                   VARCHAR2(32),
    id_region                VARCHAR2(32),
    sd_status                VARCHAR2(2) DEFAULT '1' NOT NULL,
    fg_active                CHAR(1) DEFAULT '1' NOT NULL,
    insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_symptom_template IS '症状模板表';
COMMENT ON COLUMN c_ai_symptom_template.id_template IS '症状模板主键ID';
COMMENT ON COLUMN c_ai_symptom_template.cd_symptom IS '症状Key/编码';
COMMENT ON COLUMN c_ai_symptom_template.na_symptom IS '症状名称';
COMMENT ON COLUMN c_ai_symptom_template.sd_medical_mode IS '医学模式（western/tcm）';
COMMENT ON COLUMN c_ai_symptom_template.des_symptom IS '症状描述';
COMMENT ON COLUMN c_ai_symptom_template.fg_common IS '是否常用症状';
COMMENT ON COLUMN c_ai_symptom_template.sort_order IS '排序号';
COMMENT ON COLUMN c_ai_symptom_template.system_category_json IS '系统分类JSON数组';
COMMENT ON COLUMN c_ai_symptom_template.system_category_tokens IS '系统分类检索token';
COMMENT ON COLUMN c_ai_symptom_template.body_parts_json IS '部位JSON数组';
COMMENT ON COLUMN c_ai_symptom_template.body_parts_tokens IS '部位检索token';
COMMENT ON COLUMN c_ai_symptom_template.custom_script IS '自定义脚本';
COMMENT ON COLUMN c_ai_symptom_template.applicable_population_json IS '适用人群JSON';
COMMENT ON COLUMN c_ai_symptom_template.config_json IS '问诊配置JSON';
COMMENT ON COLUMN c_ai_symptom_template.tcm_metadata_json IS '中医扩展元数据JSON';
COMMENT ON COLUMN c_ai_symptom_template.id_org IS '机构级作用范围ID';
COMMENT ON COLUMN c_ai_symptom_template.id_region IS '区域级作用范围ID';
COMMENT ON COLUMN c_ai_symptom_template.sd_status IS '状态（1启用 0停用）';
COMMENT ON COLUMN c_ai_symptom_template.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_symptom_template.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_symptom_template.update_time IS '更新时间';

CREATE INDEX idx_c_ai_symptom_scope ON c_ai_symptom_template (sd_medical_mode, id_org, id_region, fg_active, sd_status);
CREATE INDEX idx_c_ai_symptom_code ON c_ai_symptom_template (cd_symptom, sd_medical_mode, id_org, id_region, fg_active);
CREATE INDEX idx_c_ai_symptom_sort ON c_ai_symptom_template (sd_medical_mode, sort_order, fg_active);


CREATE TABLE c_ai_inpatient_emr_tpl_cache (
    id_cache             VARCHAR2(32) PRIMARY KEY,
    template_id          VARCHAR2(128) NOT NULL,
    template_hash        VARCHAR2(128) NOT NULL,
    template_name        VARCHAR2(200),
    html_content         CLOB,
    fields_json          CLOB,
    field_count          NUMBER(10) DEFAULT 0,
    sd_status            VARCHAR2(2) DEFAULT '1' NOT NULL,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_inpatient_emr_tpl_cache IS '住院病历HTML模板解析缓存表';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.id_cache IS '模板缓存主键ID';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.template_id IS 'HIS病历模板主键';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.template_hash IS 'HTML模板内容HASH';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.template_name IS '模板名称';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.html_content IS '模板HTML原文';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.fields_json IS 'data-id字段解析结果JSON';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.field_count IS '字段数量';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.sd_status IS '状态（1启用 0停用）';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_inpatient_emr_tpl_cache.update_time IS '更新时间';

CREATE INDEX idx_c_ai_inemr_tpl_id ON c_ai_inpatient_emr_tpl_cache (template_id, fg_active, sd_status);
CREATE INDEX idx_c_ai_inemr_tpl_hash ON c_ai_inpatient_emr_tpl_cache (template_hash, fg_active, sd_status);
CREATE INDEX idx_c_ai_inemr_tpl_status ON c_ai_inpatient_emr_tpl_cache (fg_active, sd_status, update_time);


CREATE TABLE c_ai_symptom_template_change_log (
    id_log                  VARCHAR2(32) PRIMARY KEY,
    id_template             VARCHAR2(32),
    cd_symptom              VARCHAR2(128),
    na_symptom              VARCHAR2(200),
    sd_medical_mode         VARCHAR2(16),
    id_org                  VARCHAR2(32),
    id_region               VARCHAR2(32),
    operation_type          VARCHAR2(32) NOT NULL,
    id_operator             VARCHAR2(32),
    cd_operator             VARCHAR2(64),
    na_operator             VARCHAR2(128),
    change_summary          VARCHAR2(1000),
    before_json             CLOB,
    after_json              CLOB,
    diff_json               CLOB,
    operation_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fg_active               CHAR(1) DEFAULT '1' NOT NULL,
    insert_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_symptom_template_change_log IS '症状模板修改日志表';
COMMENT ON COLUMN c_ai_symptom_template_change_log.id_log IS '修改日志主键ID';
COMMENT ON COLUMN c_ai_symptom_template_change_log.id_template IS '症状模板ID';
COMMENT ON COLUMN c_ai_symptom_template_change_log.cd_symptom IS '症状Key/编码';
COMMENT ON COLUMN c_ai_symptom_template_change_log.na_symptom IS '症状名称';
COMMENT ON COLUMN c_ai_symptom_template_change_log.sd_medical_mode IS '医学模式（western/tcm）';
COMMENT ON COLUMN c_ai_symptom_template_change_log.id_org IS '机构级作用范围ID';
COMMENT ON COLUMN c_ai_symptom_template_change_log.id_region IS '区域级作用范围ID';
COMMENT ON COLUMN c_ai_symptom_template_change_log.operation_type IS '操作类型（create/update/delete/import_builtin/import_json）';
COMMENT ON COLUMN c_ai_symptom_template_change_log.id_operator IS '操作者用户ID';
COMMENT ON COLUMN c_ai_symptom_template_change_log.cd_operator IS '操作者账号';
COMMENT ON COLUMN c_ai_symptom_template_change_log.na_operator IS '操作者姓名';
COMMENT ON COLUMN c_ai_symptom_template_change_log.change_summary IS '变更摘要';
COMMENT ON COLUMN c_ai_symptom_template_change_log.before_json IS '变更前模板快照';
COMMENT ON COLUMN c_ai_symptom_template_change_log.after_json IS '变更后模板快照';
COMMENT ON COLUMN c_ai_symptom_template_change_log.diff_json IS '字段级差异JSON';
COMMENT ON COLUMN c_ai_symptom_template_change_log.operation_time IS '操作时间';
COMMENT ON COLUMN c_ai_symptom_template_change_log.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_symptom_template_change_log.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_symptom_template_change_log.update_time IS '更新时间';

CREATE INDEX idx_c_ai_symptom_log_template ON c_ai_symptom_template_change_log (id_template, operation_time, fg_active);
CREATE INDEX idx_c_ai_symptom_log_operator ON c_ai_symptom_template_change_log (id_operator, operation_time, fg_active);
CREATE INDEX idx_c_ai_symptom_log_action ON c_ai_symptom_template_change_log (operation_type, operation_time, fg_active);
CREATE INDEX idx_c_ai_symptom_log_scope ON c_ai_symptom_template_change_log (sd_medical_mode, id_org, id_region, fg_active);


CREATE TABLE c_ai_op_log (
    id_log               VARCHAR2(32) PRIMARY KEY,
    id_device            VARCHAR2(32),
    id_org               VARCHAR2(32),
    id_his_org           VARCHAR2(64),
    na_his_org           VARCHAR2(255),
    sd_log_type          VARCHAR2(64),
    na_module            VARCHAR2(128),
    op_action            VARCHAR2(256),
    op_title             VARCHAR2(500),
    source_module        VARCHAR2(128),
    scene_code           VARCHAR2(256),
    trace_id             VARCHAR2(64),
    des_op               VARCHAR2(500),
    payload_json         CLOB,
    audio_file_path      VARCHAR2(1000),
    consultation_id      VARCHAR2(64),
    op_result            VARCHAR2(8),
    operation_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_op_log IS '操作日志表';
COMMENT ON COLUMN c_ai_op_log.id_log IS '日志主键ID';
COMMENT ON COLUMN c_ai_op_log.id_device IS '设备ID';
COMMENT ON COLUMN c_ai_op_log.id_org IS '机构ID';
COMMENT ON COLUMN c_ai_op_log.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_op_log.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_op_log.sd_log_type IS '日志类型';
COMMENT ON COLUMN c_ai_op_log.na_module IS '业务模块名称';
COMMENT ON COLUMN c_ai_op_log.op_action IS '业务动作编码';
COMMENT ON COLUMN c_ai_op_log.op_title IS '业务标题';
COMMENT ON COLUMN c_ai_op_log.source_module IS '来源模块';
COMMENT ON COLUMN c_ai_op_log.scene_code IS '业务场景编码';
COMMENT ON COLUMN c_ai_op_log.trace_id IS '调用链traceId';
COMMENT ON COLUMN c_ai_op_log.des_op IS '操作描述';
COMMENT ON COLUMN c_ai_op_log.payload_json IS '日志负载JSON';
COMMENT ON COLUMN c_ai_op_log.audio_file_path IS '语音代理录音文件路径';
COMMENT ON COLUMN c_ai_op_log.consultation_id IS '关联问诊ID（语音问诊场景）';
COMMENT ON COLUMN c_ai_op_log.op_result IS '操作结果';
COMMENT ON COLUMN c_ai_op_log.operation_time IS '操作时间';
COMMENT ON COLUMN c_ai_op_log.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_op_log.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_op_log.update_time IS '更新时间';

CREATE INDEX idx_c_ai_log_time ON c_ai_op_log (operation_time, fg_active);
CREATE INDEX idx_c_ai_op_log_trace ON c_ai_op_log (trace_id, operation_time, fg_active);
CREATE INDEX idx_c_ai_op_log_consultation ON c_ai_op_log (consultation_id, operation_time, fg_active);
CREATE INDEX idx_c_ai_op_log_scene ON c_ai_op_log (source_module, scene_code, operation_time, fg_active);
CREATE INDEX idx_c_ai_op_log_his_org ON c_ai_op_log (id_his_org, operation_time, fg_active);



CREATE TABLE c_ai_user_consultation_log (
    id_log               VARCHAR2(32) PRIMARY KEY,
    consultation_round_id VARCHAR2(64),
    consultation_id      VARCHAR2(64) NOT NULL,
    id_device            VARCHAR2(32),
    id_org               VARCHAR2(32),
    id_his_org           VARCHAR2(64),
    na_org               VARCHAR2(255),
    id_doctor            VARCHAR2(64),
    na_doctor            VARCHAR2(128),
    id_dept              VARCHAR2(64),
    na_dept              VARCHAR2(128),
    consultation_type    VARCHAR2(32) NOT NULL,
    consultation_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    patient_id           VARCHAR2(64),
    patient_name         VARCHAR2(128),
    patient_gender       VARCHAR2(32),
    patient_age          VARCHAR2(32),
    speech_text          CLOB,
    audio_file_path      VARCHAR2(1000),
    audio_file_name      VARCHAR2(255),
    audio_mime_type      VARCHAR2(128),
    audio_size           NUMBER(12),
    first_snapshot_json  CLOB,
    final_snapshot_json  CLOB,
    selection_json       CLOB,
    change_summary_json  CLOB,
    total_changes        NUMBER(5),
    status               VARCHAR2(32) DEFAULT 'generated',
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_user_consultation_log IS '运维用户日志-问诊聚合表';
COMMENT ON COLUMN c_ai_user_consultation_log.id_log IS '用户日志主键ID';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_round_id IS '问诊轮次ID（客户端生成UUID，每轮问诊一个，贯穿该轮所有提交）';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_id IS '问诊ID（就诊锚点，同一患者多次问诊共用，仅用于聚合展示）';
COMMENT ON COLUMN c_ai_user_consultation_log.id_device IS '设备ID';
COMMENT ON COLUMN c_ai_user_consultation_log.id_org IS '后台机构ID（来自设备鉴权）';
COMMENT ON COLUMN c_ai_user_consultation_log.id_his_org IS 'HIS端机构ID（来自桌面端问诊上下文）';
COMMENT ON COLUMN c_ai_user_consultation_log.na_org IS '机构名称';
COMMENT ON COLUMN c_ai_user_consultation_log.id_doctor IS '医生ID';
COMMENT ON COLUMN c_ai_user_consultation_log.na_doctor IS '医生姓名';
COMMENT ON COLUMN c_ai_user_consultation_log.id_dept IS '科室ID';
COMMENT ON COLUMN c_ai_user_consultation_log.na_dept IS '科室名称';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_type IS '问诊类型：voice语音问诊 smart智能问诊';
COMMENT ON COLUMN c_ai_user_consultation_log.consultation_time IS '问诊时间';
COMMENT ON COLUMN c_ai_user_consultation_log.patient_id IS '患者ID';
COMMENT ON COLUMN c_ai_user_consultation_log.patient_name IS '患者姓名';
COMMENT ON COLUMN c_ai_user_consultation_log.patient_gender IS '患者性别';
COMMENT ON COLUMN c_ai_user_consultation_log.patient_age IS '患者年龄';
COMMENT ON COLUMN c_ai_user_consultation_log.speech_text IS '语音问诊ASR识别文字';
COMMENT ON COLUMN c_ai_user_consultation_log.audio_file_path IS '语音问诊录音文件路径';
COMMENT ON COLUMN c_ai_user_consultation_log.audio_file_name IS '语音问诊录音原文件名';
COMMENT ON COLUMN c_ai_user_consultation_log.audio_mime_type IS '语音问诊录音MIME类型';
COMMENT ON COLUMN c_ai_user_consultation_log.audio_size IS '语音问诊录音字节数';
COMMENT ON COLUMN c_ai_user_consultation_log.first_snapshot_json IS '首次AI生成内容JSON';
COMMENT ON COLUMN c_ai_user_consultation_log.final_snapshot_json IS '医生最终修改内容JSON';
COMMENT ON COLUMN c_ai_user_consultation_log.selection_json IS '最终选中状态JSON';
COMMENT ON COLUMN c_ai_user_consultation_log.change_summary_json IS '变更汇总JSON（含各类别变更数）';
COMMENT ON COLUMN c_ai_user_consultation_log.total_changes IS '变更总项数';
COMMENT ON COLUMN c_ai_user_consultation_log.status IS '状态：generated已生成 completed已完成 abandoned已放弃';
COMMENT ON COLUMN c_ai_user_consultation_log.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_user_consultation_log.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_user_consultation_log.update_time IS '更新时间';

CREATE INDEX idx_c_ai_user_log_time ON c_ai_user_consultation_log (consultation_time, fg_active);
CREATE INDEX idx_c_ai_user_log_patient ON c_ai_user_consultation_log (patient_id, consultation_time, fg_active);
CREATE INDEX idx_c_ai_user_log_doctor ON c_ai_user_consultation_log (id_doctor, consultation_time, fg_active);
CREATE INDEX idx_c_ai_user_log_consultation ON c_ai_user_consultation_log (consultation_id, consultation_type, id_device, fg_active);
CREATE INDEX idx_c_ai_user_log_round ON c_ai_user_consultation_log (consultation_round_id, fg_active);
CREATE INDEX idx_c_ai_user_log_his_org ON c_ai_user_consultation_log (id_his_org, consultation_time, fg_active);
CREATE UNIQUE INDEX uk_c_ai_user_log_round_active ON c_ai_user_consultation_log (
    CASE WHEN fg_active = '1' AND status = 'generated' THEN consultation_round_id END
);


CREATE TABLE c_ai_schema_migration (
    migration_key       VARCHAR2(128) PRIMARY KEY,
    applied_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE c_ai_schema_migration IS '应用业务Schema迁移完成标记';
COMMENT ON COLUMN c_ai_schema_migration.migration_key IS '稳定迁移标识';
COMMENT ON COLUMN c_ai_schema_migration.applied_at IS '迁移完成时间';


CREATE TABLE c_ai_feature_event (
    id_event             VARCHAR2(64) PRIMARY KEY,
    id_device            VARCHAR2(32),
    id_org               VARCHAR2(32),
    id_region            VARCHAR2(32),
    id_his_org           VARCHAR2(64),
    na_his_org           VARCHAR2(255),
    feature_code         VARCHAR2(64) NOT NULL,
    feature_name         VARCHAR2(128) NOT NULL,
    event_action         VARCHAR2(128),
    idempotency_key      VARCHAR2(255) NOT NULL,
    trace_id             VARCHAR2(64),
    consultation_id      VARCHAR2(64),
    session_id           VARCHAR2(64),
    source_module        VARCHAR2(128),
    scene_code           VARCHAR2(256),
    id_doctor            VARCHAR2(64),
    cd_doctor            VARCHAR2(64),
    na_doctor            VARCHAR2(128),
    id_dept              VARCHAR2(64),
    na_dept              VARCHAR2(128),
    event_status         VARCHAR2(32) DEFAULT 'success',
    client_version       VARCHAR2(64),
    payload_json         CLOB,
    event_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_feature_event IS '辅诊功能调用事件表';
COMMENT ON COLUMN c_ai_feature_event.id_event IS '客户端生成的UUID事件主键（入库时规范化为32位小写十六进制）';
COMMENT ON COLUMN c_ai_feature_event.id_device IS '设备ID';
COMMENT ON COLUMN c_ai_feature_event.id_org IS '机构ID';
COMMENT ON COLUMN c_ai_feature_event.id_region IS '区域ID';
COMMENT ON COLUMN c_ai_feature_event.id_his_org IS 'HIS端机构ID（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.na_his_org IS 'HIS端机构名称（来自桌面端事件上下文）';
COMMENT ON COLUMN c_ai_feature_event.feature_code IS '功能编码';
COMMENT ON COLUMN c_ai_feature_event.feature_name IS '功能展示名称';
COMMENT ON COLUMN c_ai_feature_event.event_action IS '功能动作编码';
COMMENT ON COLUMN c_ai_feature_event.idempotency_key IS '服务端按功能编码与UUID事件ID派生的版本化幂等键，同一设备内唯一';
COMMENT ON COLUMN c_ai_feature_event.trace_id IS '兼容保留列；功能统计固定不保存AI调用关联';
COMMENT ON COLUMN c_ai_feature_event.consultation_id IS '兼容保留列；功能统计固定不保存问诊关联';
COMMENT ON COLUMN c_ai_feature_event.session_id IS '兼容保留列；功能统计固定不保存会话关联';
COMMENT ON COLUMN c_ai_feature_event.source_module IS '来源模块';
COMMENT ON COLUMN c_ai_feature_event.scene_code IS '场景编码';
COMMENT ON COLUMN c_ai_feature_event.id_doctor IS '医生ID';
COMMENT ON COLUMN c_ai_feature_event.cd_doctor IS '医生真实工号（来自SDK握手urt.personCd）';
COMMENT ON COLUMN c_ai_feature_event.na_doctor IS '医生姓名';
COMMENT ON COLUMN c_ai_feature_event.id_dept IS '科室ID';
COMMENT ON COLUMN c_ai_feature_event.na_dept IS '科室名称';
COMMENT ON COLUMN c_ai_feature_event.event_status IS '事件状态：success/failure';
COMMENT ON COLUMN c_ai_feature_event.client_version IS '事件产生时的客户端版本';
COMMENT ON COLUMN c_ai_feature_event.payload_json IS '兼容保留列；功能统计固定保存空对象';
COMMENT ON COLUMN c_ai_feature_event.event_time IS '事件发生时间';
COMMENT ON COLUMN c_ai_feature_event.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_feature_event.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_feature_event.update_time IS '更新时间';

CREATE UNIQUE INDEX uk_c_ai_feature_event_idem ON c_ai_feature_event (id_device, idempotency_key);
CREATE INDEX idx_c_ai_feature_event_time ON c_ai_feature_event (event_time, fg_active);
CREATE INDEX idx_c_ai_feature_event_feature ON c_ai_feature_event (feature_name, event_time, fg_active);
CREATE INDEX idx_c_ai_feature_event_doctor ON c_ai_feature_event (id_doctor, event_time, fg_active);
CREATE INDEX idx_c_ai_feature_event_org ON c_ai_feature_event (id_org, id_region, event_time, fg_active);
CREATE INDEX idx_c_ai_feature_event_his_org ON c_ai_feature_event (id_his_org, event_time, fg_active);
CREATE INDEX idx_c_ai_feature_event_usage ON c_ai_feature_event (id_org, id_his_org, cd_doctor, client_version, event_time, fg_active);

INSERT INTO c_ai_schema_migration (migration_key)
VALUES ('feature_event_minimization_v1');


CREATE TABLE c_ai_rec_pref_event (
    id_event             VARCHAR2(64) PRIMARY KEY,
    id_device            VARCHAR2(32),
    id_org               VARCHAR2(32),
    id_region            VARCHAR2(32),
    recommendation_type  VARCHAR2(32) NOT NULL,
    action_code          VARCHAR2(32) NOT NULL,
    idempotency_key      VARCHAR2(255) NOT NULL,
    item_key             VARCHAR2(255) NOT NULL,
    item_id              VARCHAR2(128),
    item_code            VARCHAR2(128),
    item_name            VARCHAR2(255),
    fg_selected          CHAR(1) DEFAULT '1' NOT NULL,
    fg_primary           CHAR(1) DEFAULT '0' NOT NULL,
    trace_id             VARCHAR2(64),
    consultation_id      VARCHAR2(64),
    session_id           VARCHAR2(64),
    source_module        VARCHAR2(128),
    scene_code           VARCHAR2(256),
    id_doctor            VARCHAR2(64),
    na_doctor            VARCHAR2(128),
    id_dept              VARCHAR2(64),
    na_dept              VARCHAR2(128),
    prompt_version       VARCHAR2(128),
    template_version     VARCHAR2(128),
    model_version        VARCHAR2(128),
    payload_json         CLOB,
    event_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_rec_pref_event IS '推荐偏好原始事件表';
COMMENT ON COLUMN c_ai_rec_pref_event.id_event IS '事件主键ID';
COMMENT ON COLUMN c_ai_rec_pref_event.id_device IS '设备ID';
COMMENT ON COLUMN c_ai_rec_pref_event.id_org IS '机构ID';
COMMENT ON COLUMN c_ai_rec_pref_event.id_region IS '区域ID';
COMMENT ON COLUMN c_ai_rec_pref_event.recommendation_type IS '推荐类型：diagnosis/medicine/exam/lab_test/procedure';
COMMENT ON COLUMN c_ai_rec_pref_event.action_code IS '医生动作：final_select/manual_match/confirm_match';
COMMENT ON COLUMN c_ai_rec_pref_event.idempotency_key IS '幂等键，同一设备内唯一';
COMMENT ON COLUMN c_ai_rec_pref_event.item_key IS '标准候选项稳定身份';
COMMENT ON COLUMN c_ai_rec_pref_event.item_id IS '标准候选项ID';
COMMENT ON COLUMN c_ai_rec_pref_event.item_code IS '标准候选项编码';
COMMENT ON COLUMN c_ai_rec_pref_event.item_name IS '标准候选项名称';
COMMENT ON COLUMN c_ai_rec_pref_event.fg_selected IS '是否最终选中';
COMMENT ON COLUMN c_ai_rec_pref_event.fg_primary IS '是否主诊断';
COMMENT ON COLUMN c_ai_rec_pref_event.trace_id IS '关联AI调用traceId';
COMMENT ON COLUMN c_ai_rec_pref_event.consultation_id IS '关联问诊ID';
COMMENT ON COLUMN c_ai_rec_pref_event.session_id IS '关联会话ID';
COMMENT ON COLUMN c_ai_rec_pref_event.source_module IS '来源模块';
COMMENT ON COLUMN c_ai_rec_pref_event.scene_code IS '场景编码';
COMMENT ON COLUMN c_ai_rec_pref_event.id_doctor IS '医生ID';
COMMENT ON COLUMN c_ai_rec_pref_event.na_doctor IS '医生姓名';
COMMENT ON COLUMN c_ai_rec_pref_event.id_dept IS '科室ID';
COMMENT ON COLUMN c_ai_rec_pref_event.na_dept IS '科室名称';
COMMENT ON COLUMN c_ai_rec_pref_event.prompt_version IS 'Prompt版本';
COMMENT ON COLUMN c_ai_rec_pref_event.template_version IS '模板版本';
COMMENT ON COLUMN c_ai_rec_pref_event.model_version IS '模型版本';
COMMENT ON COLUMN c_ai_rec_pref_event.payload_json IS '事件扩展负载JSON';
COMMENT ON COLUMN c_ai_rec_pref_event.event_time IS '事件发生时间';
COMMENT ON COLUMN c_ai_rec_pref_event.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_rec_pref_event.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_rec_pref_event.update_time IS '更新时间';

CREATE UNIQUE INDEX uk_c_ai_rec_pref_event_idem ON c_ai_rec_pref_event (id_device, idempotency_key);
CREATE INDEX idx_c_ai_rec_pref_event_item ON c_ai_rec_pref_event (id_org, recommendation_type, item_key, event_time, fg_active);
CREATE INDEX idx_c_ai_rec_pref_event_doctor ON c_ai_rec_pref_event (id_doctor, recommendation_type, event_time, fg_active);
CREATE INDEX idx_c_ai_rec_pref_event_dept ON c_ai_rec_pref_event (id_dept, recommendation_type, event_time, fg_active);


CREATE TABLE c_ai_rec_pref_agg (
    id_agg               VARCHAR2(64) PRIMARY KEY,
    id_org               VARCHAR2(32),
    id_region            VARCHAR2(32),
    id_dept              VARCHAR2(64),
    id_doctor            VARCHAR2(64),
    recommendation_type  VARCHAR2(32) NOT NULL,
    item_key             VARCHAR2(255) NOT NULL,
    item_id              VARCHAR2(128),
    item_code            VARCHAR2(128),
    item_name            VARCHAR2(255),
    selected_count       NUMBER(10) DEFAULT 0 NOT NULL,
    confirm_count        NUMBER(10) DEFAULT 0 NOT NULL,
    manual_match_count   NUMBER(10) DEFAULT 0 NOT NULL,
    preference_score     NUMBER(8,4) DEFAULT 0 NOT NULL,
    last_event_time      TIMESTAMP,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_rec_pref_agg IS '推荐偏好聚合表';
COMMENT ON COLUMN c_ai_rec_pref_agg.id_agg IS '聚合主键ID';
COMMENT ON COLUMN c_ai_rec_pref_agg.id_org IS '机构ID';
COMMENT ON COLUMN c_ai_rec_pref_agg.id_region IS '区域ID';
COMMENT ON COLUMN c_ai_rec_pref_agg.id_dept IS '科室ID，空表示机构级';
COMMENT ON COLUMN c_ai_rec_pref_agg.id_doctor IS '医生ID，空表示机构或科室级';
COMMENT ON COLUMN c_ai_rec_pref_agg.recommendation_type IS '推荐类型';
COMMENT ON COLUMN c_ai_rec_pref_agg.item_key IS '标准候选项稳定身份';
COMMENT ON COLUMN c_ai_rec_pref_agg.item_id IS '标准候选项ID';
COMMENT ON COLUMN c_ai_rec_pref_agg.item_code IS '标准候选项编码';
COMMENT ON COLUMN c_ai_rec_pref_agg.item_name IS '标准候选项名称';
COMMENT ON COLUMN c_ai_rec_pref_agg.selected_count IS '最终选择次数';
COMMENT ON COLUMN c_ai_rec_pref_agg.confirm_count IS '确认匹配次数';
COMMENT ON COLUMN c_ai_rec_pref_agg.manual_match_count IS '手动匹配次数';
COMMENT ON COLUMN c_ai_rec_pref_agg.preference_score IS '偏好分';
COMMENT ON COLUMN c_ai_rec_pref_agg.last_event_time IS '最近事件时间';
COMMENT ON COLUMN c_ai_rec_pref_agg.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_rec_pref_agg.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_rec_pref_agg.update_time IS '更新时间';

CREATE UNIQUE INDEX uk_c_ai_rec_pref_agg_scope ON c_ai_rec_pref_agg (
    CASE WHEN fg_active = '1' THEN NVL(id_org, '-') END,
    CASE WHEN fg_active = '1' THEN NVL(id_dept, '-') END,
    CASE WHEN fg_active = '1' THEN NVL(id_doctor, '-') END,
    CASE WHEN fg_active = '1' THEN recommendation_type END,
    CASE WHEN fg_active = '1' THEN item_key END
);
CREATE INDEX idx_c_ai_rec_pref_agg_item ON c_ai_rec_pref_agg (id_org, recommendation_type, item_key, fg_active);
CREATE INDEX idx_c_ai_rec_pref_agg_doctor ON c_ai_rec_pref_agg (id_doctor, recommendation_type, fg_active);


CREATE TABLE c_security_rejection_log (
    id_log               VARCHAR2(32) PRIMARY KEY,
    rejection_type       VARCHAR2(64) NOT NULL,
    request_method       VARCHAR2(16),
    request_path         VARCHAR2(512),
    client_ip            VARCHAR2(64),
    id_device            VARCHAR2(32),
    cd_device            VARCHAR2(128),
    id_org               VARCHAR2(32),
    request_id           VARCHAR2(64),
    reject_reason        VARCHAR2(255),
    reject_detail        VARCHAR2(500),
    has_signature        CHAR(1) DEFAULT '0',
    timestamp_header     VARCHAR2(32),
    nonce_header         VARCHAR2(64),
    client_version       VARCHAR2(32),
    update_channel       VARCHAR2(32),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_security_rejection_log IS '安全拒绝日志表';
COMMENT ON COLUMN c_security_rejection_log.id_log IS '日志主键ID';
COMMENT ON COLUMN c_security_rejection_log.rejection_type IS '拒绝类型';
COMMENT ON COLUMN c_security_rejection_log.request_method IS '请求方法';
COMMENT ON COLUMN c_security_rejection_log.request_path IS '请求路径';
COMMENT ON COLUMN c_security_rejection_log.client_ip IS '客户端IP';
COMMENT ON COLUMN c_security_rejection_log.id_device IS '设备ID';
COMMENT ON COLUMN c_security_rejection_log.cd_device IS '设备编码';
COMMENT ON COLUMN c_security_rejection_log.id_org IS '机构ID';
COMMENT ON COLUMN c_security_rejection_log.request_id IS '请求ID';
COMMENT ON COLUMN c_security_rejection_log.reject_reason IS '拒绝原因';
COMMENT ON COLUMN c_security_rejection_log.reject_detail IS '拒绝详情';
COMMENT ON COLUMN c_security_rejection_log.has_signature IS '是否携带签名';
COMMENT ON COLUMN c_security_rejection_log.timestamp_header IS '请求时间戳头';
COMMENT ON COLUMN c_security_rejection_log.nonce_header IS '请求nonce头';
COMMENT ON COLUMN c_security_rejection_log.client_version IS '客户端版本';
COMMENT ON COLUMN c_security_rejection_log.update_channel IS '更新通道';
COMMENT ON COLUMN c_security_rejection_log.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_security_rejection_log.insert_time IS '创建时间';
COMMENT ON COLUMN c_security_rejection_log.update_time IS '更新时间';

CREATE INDEX idx_c_security_rej_time ON c_security_rejection_log (insert_time, fg_active);
CREATE INDEX idx_c_security_rej_type ON c_security_rejection_log (rejection_type, insert_time, fg_active);
CREATE INDEX idx_c_security_rej_ip ON c_security_rejection_log (client_ip, insert_time, fg_active);
CREATE INDEX idx_c_security_rej_device ON c_security_rejection_log (id_device, insert_time, fg_active);
CREATE INDEX idx_c_security_rej_path ON c_security_rejection_log (request_path, insert_time, fg_active);


CREATE TABLE c_ai_feedback (
    id_feedback           VARCHAR2(32) PRIMARY KEY,
    id_device             VARCHAR2(32),
    id_org                VARCHAR2(32),
    na_org                VARCHAR2(255),
    id_doctor             VARCHAR2(64),
    na_doctor             VARCHAR2(128),
    id_dept               VARCHAR2(64),
    na_dept               VARCHAR2(128),
    session_id            VARCHAR2(64),
    trace_id              VARCHAR2(64),
    source_module         VARCHAR2(128),
    kind                  VARCHAR2(32) DEFAULT 'general',
    severity              VARCHAR2(16) DEFAULT 'medium',
    tags_json             VARCHAR2(1000),
    has_correction        CHAR(1) DEFAULT '0',
    has_trace             CHAR(1) DEFAULT '0',
    score                 NUMBER(2) NOT NULL,
    comment_text          VARCHAR2(2000) NOT NULL,
    screenshot_file_name  VARCHAR2(255),
    screenshot_mime_type  VARCHAR2(128),
    screenshot_data_url   CLOB,
    feedback_scope_key    VARCHAR2(255),
    id_feedback_root      VARCHAR2(32),
    previous_feedback_id  VARCHAR2(32),
    revision_no           NUMBER(10) DEFAULT 1,
    fg_latest             CHAR(1) DEFAULT '1' NOT NULL,
    chain_context_json    CLOB,
    feedback_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fg_active             CHAR(1) DEFAULT '1' NOT NULL,
    insert_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_feedback IS '用户反馈表';
COMMENT ON COLUMN c_ai_feedback.id_feedback IS '反馈主键ID';
COMMENT ON COLUMN c_ai_feedback.id_device IS '设备ID';
COMMENT ON COLUMN c_ai_feedback.id_org IS '机构ID';
COMMENT ON COLUMN c_ai_feedback.na_org IS '机构名称';
COMMENT ON COLUMN c_ai_feedback.id_doctor IS '反馈医生ID';
COMMENT ON COLUMN c_ai_feedback.na_doctor IS '反馈医生姓名';
COMMENT ON COLUMN c_ai_feedback.id_dept IS '反馈科室ID';
COMMENT ON COLUMN c_ai_feedback.na_dept IS '反馈科室名称';
COMMENT ON COLUMN c_ai_feedback.session_id IS '会话ID';
COMMENT ON COLUMN c_ai_feedback.trace_id IS '关联的 AI 调用 traceId';
COMMENT ON COLUMN c_ai_feedback.source_module IS '反馈来源模块';
COMMENT ON COLUMN c_ai_feedback.kind IS '反馈类型：general/recommendation/record_field/session';
COMMENT ON COLUMN c_ai_feedback.severity IS '严重度：low/medium/high';
COMMENT ON COLUMN c_ai_feedback.tags_json IS '问题标签 JSON 数组';
COMMENT ON COLUMN c_ai_feedback.has_correction IS '是否包含医生修正';
COMMENT ON COLUMN c_ai_feedback.has_trace IS '是否包含 AI traceId';
COMMENT ON COLUMN c_ai_feedback.score IS '反馈评分';
COMMENT ON COLUMN c_ai_feedback.comment_text IS '反馈说明';
COMMENT ON COLUMN c_ai_feedback.screenshot_file_name IS '截图文件名';
COMMENT ON COLUMN c_ai_feedback.screenshot_mime_type IS '截图 MIME 类型';
COMMENT ON COLUMN c_ai_feedback.screenshot_data_url IS '截图 Data URL';
COMMENT ON COLUMN c_ai_feedback.feedback_scope_key IS '反馈槽位唯一键（同问诊+模块）';
COMMENT ON COLUMN c_ai_feedback.id_feedback_root IS '反馈修订链根记录 ID';
COMMENT ON COLUMN c_ai_feedback.previous_feedback_id IS '上一版反馈 ID';
COMMENT ON COLUMN c_ai_feedback.revision_no IS '反馈修订版本号';
COMMENT ON COLUMN c_ai_feedback.fg_latest IS '是否最新版本';
COMMENT ON COLUMN c_ai_feedback.chain_context_json IS '前端上传的链路上下文快照';
COMMENT ON COLUMN c_ai_feedback.feedback_time IS '反馈时间';
COMMENT ON COLUMN c_ai_feedback.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_feedback.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_feedback.update_time IS '更新时间';

CREATE INDEX idx_c_ai_feedback_time ON c_ai_feedback (feedback_time, fg_active);
CREATE INDEX idx_c_ai_feedback_trace ON c_ai_feedback (trace_id, fg_active);
CREATE INDEX idx_c_ai_feedback_device ON c_ai_feedback (id_device, fg_active);
CREATE INDEX idx_c_ai_feedback_kind ON c_ai_feedback (kind, fg_active);
CREATE INDEX idx_c_ai_feedback_doctor ON c_ai_feedback (id_doctor, fg_active);
CREATE INDEX idx_c_ai_feedback_dept ON c_ai_feedback (id_dept, fg_active);
CREATE INDEX idx_c_ai_feedback_scope ON c_ai_feedback (id_device, feedback_scope_key, fg_latest, fg_active);
CREATE UNIQUE INDEX uk_c_ai_feedback_latest_scope ON c_ai_feedback (
    CASE WHEN fg_active = '1' AND fg_latest = '1' AND feedback_scope_key IS NOT NULL THEN NVL(id_device, '-') END,
    CASE WHEN fg_active = '1' AND fg_latest = '1' AND feedback_scope_key IS NOT NULL THEN feedback_scope_key END
);


CREATE TABLE c_ai_patient_memory (
    id_memory            VARCHAR2(32) PRIMARY KEY,
    id_org               VARCHAR2(32) NOT NULL,
    id_region            VARCHAR2(32),
    id_his_org           VARCHAR2(64) NOT NULL,
    patient_id           VARCHAR2(128) NOT NULL,
    patient_name         VARCHAR2(128),
    patient_gender       VARCHAR2(16),
    patient_age          VARCHAR2(32),
    patient_birth_date   VARCHAR2(32),
    memory_version       NUMBER(19) DEFAULT 0 NOT NULL,
    summary_json         CLOB,
    conflict_count       NUMBER(10) DEFAULT 0 NOT NULL,
    quality_status       VARCHAR2(16) DEFAULT 'partial' NOT NULL,
    last_sync_time       TIMESTAMP,
    last_source_time     TIMESTAMP,
    sd_status            VARCHAR2(16) DEFAULT 'active' NOT NULL,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_patient_memory IS '患者纵向记忆聚合根表';
COMMENT ON COLUMN c_ai_patient_memory.id_memory IS '患者记忆主键ID';
COMMENT ON COLUMN c_ai_patient_memory.id_org IS '后台机构ID，来自设备鉴权';
COMMENT ON COLUMN c_ai_patient_memory.id_his_org IS 'HIS机构ID，患者标识的机构作用域';
COMMENT ON COLUMN c_ai_patient_memory.patient_id IS 'HIS患者主键，不跨机构合并';
COMMENT ON COLUMN c_ai_patient_memory.memory_version IS '患者记忆单调递增版本';
COMMENT ON COLUMN c_ai_patient_memory.summary_json IS '当前患者记忆摘要快照JSON';
COMMENT ON COLUMN c_ai_patient_memory.conflict_count IS '尚未消解的事实冲突数量';
COMMENT ON COLUMN c_ai_patient_memory.quality_status IS '记忆质量状态：fresh/partial/conflicted';
COMMENT ON COLUMN c_ai_patient_memory.last_sync_time IS '桌面端最近一次同步时间';
COMMENT ON COLUMN c_ai_patient_memory.last_source_time IS '当前已接收来源中的最近业务时间';

CREATE INDEX idx_c_ai_patient_memory_sync ON c_ai_patient_memory (id_org, last_sync_time, fg_active);
CREATE INDEX idx_c_ai_patient_memory_patient ON c_ai_patient_memory (id_org, patient_id, fg_active);
CREATE UNIQUE INDEX uk_c_ai_patient_memory_scope ON c_ai_patient_memory (
    (CASE WHEN fg_active = '1' THEN id_org END),
    (CASE WHEN fg_active = '1' THEN id_his_org END),
    (CASE WHEN fg_active = '1' THEN patient_id END)
);


CREATE TABLE c_ai_patient_memory_obs (
    id_observation       VARCHAR2(32) PRIMARY KEY,
    id_memory            VARCHAR2(32) NOT NULL,
    id_device            VARCHAR2(32),
    source_key           VARCHAR2(256) NOT NULL,
    source_key_hash      VARCHAR2(64) NOT NULL,
    source_type          VARCHAR2(32) NOT NULL,
    source_version       VARCHAR2(128),
    operation_code       VARCHAR2(16) DEFAULT 'upsert' NOT NULL,
    payload_hash         VARCHAR2(64) NOT NULL,
    visit_id             VARCHAR2(128),
    occurred_time        TIMESTAMP,
    payload_json         CLOB,
    facts_json           CLOB,
    fg_latest            CHAR(1) DEFAULT '1' NOT NULL,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_patient_memory_obs IS '患者记忆来源观察版本表';
COMMENT ON COLUMN c_ai_patient_memory_obs.source_key IS '来源稳定键，例如一次就诊或一份报告';
COMMENT ON COLUMN c_ai_patient_memory_obs.source_key_hash IS '来源稳定键SHA-256，用于跨数据库索引';
COMMENT ON COLUMN c_ai_patient_memory_obs.source_type IS 'patient_profile/allergy_snapshot/visit_summary/outpatient_record/lab_report/exam_report/doctor_confirmation';
COMMENT ON COLUMN c_ai_patient_memory_obs.source_version IS '上游来源版本或内容版本';
COMMENT ON COLUMN c_ai_patient_memory_obs.operation_code IS '增量操作：upsert/tombstone';
COMMENT ON COLUMN c_ai_patient_memory_obs.payload_hash IS '规范化观察内容SHA-256，用于幂等去重';
COMMENT ON COLUMN c_ai_patient_memory_obs.fg_latest IS '同一来源稳定键的最新版本标记';

CREATE INDEX idx_c_ai_patient_memory_obs_time ON c_ai_patient_memory_obs (id_memory, occurred_time, fg_active);
CREATE INDEX idx_c_ai_patient_memory_obs_latest ON c_ai_patient_memory_obs (id_memory, source_key_hash, fg_latest, fg_active);
CREATE UNIQUE INDEX uk_c_ai_patient_memory_obs_idem ON c_ai_patient_memory_obs (
    (CASE WHEN fg_active = '1' THEN id_memory END),
    (CASE WHEN fg_active = '1' THEN source_key_hash END),
    (CASE WHEN fg_active = '1' THEN payload_hash END)
);


CREATE TABLE c_ai_patient_memory_fact (
    id_fact              VARCHAR2(32) PRIMARY KEY,
    id_memory            VARCHAR2(32) NOT NULL,
    fact_key             VARCHAR2(256) NOT NULL,
    fact_key_hash        VARCHAR2(64) NOT NULL,
    fact_type            VARCHAR2(32) NOT NULL,
    fact_code            VARCHAR2(128),
    fact_name            VARCHAR2(256),
    value_text           VARCHAR2(1000),
    fact_status          VARCHAR2(32) DEFAULT 'historical' NOT NULL,
    confidence_level     VARCHAR2(32) DEFAULT 'structured' NOT NULL,
    evidence_text        VARCHAR2(1000),
    source_type          VARCHAR2(32),
    source_key           VARCHAR2(256),
    latest_observation_id VARCHAR2(32),
    origin_code          VARCHAR2(32) DEFAULT 'his' NOT NULL,
    fg_suppressed        CHAR(1) DEFAULT '0' NOT NULL,
    revision_no          NUMBER(10) DEFAULT 1 NOT NULL,
    first_observed_time  TIMESTAMP,
    last_observed_time   TIMESTAMP,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_patient_memory_fact IS '患者记忆当前临床事实投影表';
COMMENT ON COLUMN c_ai_patient_memory_fact.fact_key IS '临床事实稳定键';
COMMENT ON COLUMN c_ai_patient_memory_fact.fact_type IS 'allergy/chronic_condition/diagnosis/medication/procedure/lab_result/exam_result/vital/history/reminder';
COMMENT ON COLUMN c_ai_patient_memory_fact.fact_status IS 'active/historical/inactive/unknown/disputed';
COMMENT ON COLUMN c_ai_patient_memory_fact.confidence_level IS 'confirmed/structured/extracted/low';
COMMENT ON COLUMN c_ai_patient_memory_fact.origin_code IS '事实当前权威来源：his/doctor/admin';
COMMENT ON COLUMN c_ai_patient_memory_fact.fg_suppressed IS '管理员屏蔽标记，屏蔽后不进入医生摘要';
COMMENT ON COLUMN c_ai_patient_memory_fact.revision_no IS '事实投影修订版本';

CREATE INDEX idx_c_ai_patient_memory_fact_type ON c_ai_patient_memory_fact (id_memory, fact_type, last_observed_time, fg_active);
CREATE INDEX idx_c_ai_patient_memory_fact_status ON c_ai_patient_memory_fact (id_memory, fact_status, fg_suppressed, fg_active);
CREATE UNIQUE INDEX uk_c_ai_patient_memory_fact_key ON c_ai_patient_memory_fact (
    (CASE WHEN fg_active = '1' THEN id_memory END),
    (CASE WHEN fg_active = '1' THEN fact_key_hash END)
);


CREATE TABLE c_ai_patient_memory_audit (
    id_audit             VARCHAR2(32) PRIMARY KEY,
    id_memory            VARCHAR2(32) NOT NULL,
    id_fact              VARCHAR2(32),
    action_code          VARCHAR2(32) NOT NULL,
    before_json          CLOB,
    after_json           CLOB,
    note_text            VARCHAR2(1000),
    id_operator          VARCHAR2(32),
    na_operator          VARCHAR2(128),
    operation_time       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_patient_memory_audit IS '患者记忆人工治理审计表';
COMMENT ON COLUMN c_ai_patient_memory_audit.action_code IS '治理动作：correct/suppress/restore';
COMMENT ON COLUMN c_ai_patient_memory_audit.before_json IS '治理前事实快照JSON';
COMMENT ON COLUMN c_ai_patient_memory_audit.after_json IS '治理后事实快照JSON';
COMMENT ON COLUMN c_ai_patient_memory_audit.note_text IS '治理原因';
COMMENT ON COLUMN c_ai_patient_memory_audit.id_operator IS '后台操作人ID';
COMMENT ON COLUMN c_ai_patient_memory_audit.operation_time IS '治理操作时间';

CREATE INDEX idx_c_ai_patient_memory_audit_time ON c_ai_patient_memory_audit (id_memory, operation_time, fg_active);
CREATE INDEX idx_c_ai_patient_memory_audit_fact ON c_ai_patient_memory_audit (id_fact, operation_time, fg_active);

CREATE TABLE c_ai_chronic_followup (
    id_followup              VARCHAR2(32) PRIMARY KEY,
    request_id               VARCHAR2(64) NOT NULL,
    id_device                VARCHAR2(32),
    id_org                   VARCHAR2(32) NOT NULL,
    id_phr                   VARCHAR2(64) NOT NULL,
    id_record                VARCHAR2(64) NOT NULL,
    source_form_id           VARCHAR2(64),
    visit_status             VARCHAR2(2) NOT NULL,
    sd_visit_kind            VARCHAR2(8) NOT NULL,
    dt_hy_plan               VARCHAR2(10),
    dt_dbs_plan              VARCHAR2(10),
    input_user               VARCHAR2(64),
    id_user                  VARCHAR2(64),
    form_data_json           CLOB NOT NULL,
    id_his_org               VARCHAR2(64),
    na_his_org               VARCHAR2(255),
    patient_id               VARCHAR2(64) NOT NULL,
    visit_id                 VARCHAR2(64),
    patient_name             VARCHAR2(128) NOT NULL,
    disease_type             VARCHAR2(32) NOT NULL,
    management_source        VARCHAR2(32) NOT NULL,
    management_evidence      VARCHAR2(2000) NOT NULL,
    template_version         VARCHAR2(64) NOT NULL,
    path_version             VARCHAR2(64) NOT NULL,
    evidence_version         VARCHAR2(128) NOT NULL,
    rule_version             VARCHAR2(64) NOT NULL,
    followup_date            DATE NOT NULL,
    followup_method          VARCHAR2(32) NOT NULL,
    symptom_codes            VARCHAR2(1000) NOT NULL,
    systolic_pressure        NUMBER(4),
    diastolic_pressure       NUMBER(4),
    fasting_glucose          NUMBER(8,2),
    postprandial_glucose     NUMBER(8,2),
    height_cm                NUMBER(8,2),
    weight_kg                NUMBER(8,2),
    bmi                      NUMBER(6,2),
    waist_cm                 NUMBER(8,2),
    daily_cigarettes         NUMBER(4),
    daily_alcohol_units      NUMBER(8,2),
    weekly_exercise_sessions NUMBER(3),
    exercise_minutes         NUMBER(4),
    salt_intake_level        VARCHAR2(32) NOT NULL,
    psychological_status     VARCHAR2(32) NOT NULL,
    medication_adherence     VARCHAR2(32) NOT NULL,
    adverse_reaction         CHAR(1) DEFAULT '0' NOT NULL,
    adverse_reaction_text    VARCHAR2(1000),
    medication_summary       VARCHAR2(2000),
    followup_classification  VARCHAR2(32) NOT NULL,
    referral_required        CHAR(1) DEFAULT '0' NOT NULL,
    referral_reason          VARCHAR2(1000),
    referral_organization    VARCHAR2(255),
    next_followup_date       DATE NOT NULL,
    id_doctor                VARCHAR2(64),
    na_doctor                VARCHAR2(128) NOT NULL,
    notes                    VARCHAR2(2000),
    save_status              VARCHAR2(16) DEFAULT 'saved' NOT NULL,
    fg_active                CHAR(1) DEFAULT '1' NOT NULL,
    insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_chronic_followup IS '原TcdVisitForm结构的高血压与糖尿病融合随访记录';
COMMENT ON COLUMN c_ai_chronic_followup.request_id IS 'X-Request-Id，在平台机构激活记录内唯一';
COMMENT ON COLUMN c_ai_chronic_followup.id_phr IS '原慢病系统人员主键idPhr';
COMMENT ON COLUMN c_ai_chronic_followup.id_record IS '原慢病系统登记表主键idRecord';
COMMENT ON COLUMN c_ai_chronic_followup.visit_status IS '原实例阶段：1诊前/2诊中/3诊后';
COMMENT ON COLUMN c_ai_chronic_followup.sd_visit_kind IS '原实例随访类型：1高血压/2糖尿病/1,2联合';
COMMENT ON COLUMN c_ai_chronic_followup.form_data_json IS '强类型TcdVisitForm.getFormData完整快照';
COMMENT ON COLUMN c_ai_chronic_followup.disease_type IS '兼容检索列：hypertension/type2_diabetes/combined';
COMMENT ON COLUMN c_ai_chronic_followup.management_source IS '正式随访固定为public_health';
COMMENT ON COLUMN c_ai_chronic_followup.management_evidence IS '公卫明确在管的来源证据';
COMMENT ON COLUMN c_ai_chronic_followup.template_version IS '保存时使用的受控公卫随访模板版本';
COMMENT ON COLUMN c_ai_chronic_followup.path_version IS '保存时绑定的受控临床路径版本';
COMMENT ON COLUMN c_ai_chronic_followup.evidence_version IS '保存时绑定的规范或指南依据版本';
COMMENT ON COLUMN c_ai_chronic_followup.rule_version IS '保存时使用的条件和值域规则版本';
COMMENT ON COLUMN c_ai_chronic_followup.symptom_codes IS '受控症状编码，逗号分隔；不是无类型业务载荷';
COMMENT ON COLUMN c_ai_chronic_followup.followup_classification IS 'stable/uncontrolled/adverse_reaction/complication';
COMMENT ON COLUMN c_ai_chronic_followup.referral_required IS '是否需要转诊：1是/0否';
COMMENT ON COLUMN c_ai_chronic_followup.save_status IS '保存状态，当前固定saved';

CREATE UNIQUE INDEX uk_c_ai_chronic_fu_req ON c_ai_chronic_followup (
    id_org,
    (CASE WHEN fg_active = '1' THEN request_id END)
);
CREATE INDEX idx_c_ai_chronic_fu_patient ON c_ai_chronic_followup (
    id_org, id_his_org, patient_id, followup_date, fg_active
);
CREATE INDEX idx_c_ai_chronic_fu_disease ON c_ai_chronic_followup (
    id_org, disease_type, followup_date, fg_active
);
CREATE INDEX idx_c_ai_chronic_fu_tcd ON c_ai_chronic_followup (
    id_org, id_phr, id_record, sd_visit_kind, fg_active
);

CREATE TABLE c_ai_chronic_artifact (
    id_snapshot              VARCHAR2(32) PRIMARY KEY,
    request_id               VARCHAR2(64) NOT NULL,
    artifact_type            VARCHAR2(32) NOT NULL,
    id_device                VARCHAR2(32),
    id_org                   VARCHAR2(32) NOT NULL,
    id_his_org               VARCHAR2(64),
    na_his_org               VARCHAR2(255),
    patient_id               VARCHAR2(64) NOT NULL,
    visit_id                 VARCHAR2(64),
    patient_name             VARCHAR2(128) NOT NULL,
    disease_types_json       VARCHAR2(1000) NOT NULL,
    data_as_of               TIMESTAMP NOT NULL,
    assessment_year          NUMBER(4),
    template_versions_json   VARCHAR2(1000) NOT NULL,
    path_versions_json       VARCHAR2(1000) NOT NULL,
    evidence_versions_json   VARCHAR2(2000) NOT NULL,
    rule_version             VARCHAR2(64) NOT NULL,
    summary_text             VARCHAR2(4000) NOT NULL,
    systolic_pressure        NUMBER(4),
    diastolic_pressure       NUMBER(4),
    blood_glucose            NUMBER(8,2),
    bp_record_count          NUMBER(8) NOT NULL,
    glucose_record_count     NUMBER(8) NOT NULL,
    accepted_items_json      CLOB NOT NULL,
    doctor_notes             VARCHAR2(2000),
    id_doctor                VARCHAR2(64),
    na_doctor                VARCHAR2(128) NOT NULL,
    save_status              VARCHAR2(16) DEFAULT 'saved' NOT NULL,
    fg_active                CHAR(1) DEFAULT '1' NOT NULL,
    insert_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_chronic_artifact IS '双慢病健康处方与年度评估打印留痕快照';
COMMENT ON COLUMN c_ai_chronic_artifact.request_id IS '客户端幂等请求ID，在平台机构激活记录内唯一';
COMMENT ON COLUMN c_ai_chronic_artifact.artifact_type IS 'health_prescription/annual_assessment';
COMMENT ON COLUMN c_ai_chronic_artifact.disease_types_json IS '快照覆盖病种的强类型JSON数组';
COMMENT ON COLUMN c_ai_chronic_artifact.data_as_of IS '快照使用的患者证据截至时间';
COMMENT ON COLUMN c_ai_chronic_artifact.accepted_items_json IS '医生确认建议的强类型JSON数组';
COMMENT ON COLUMN c_ai_chronic_artifact.save_status IS '保存状态，当前固定saved';

CREATE UNIQUE INDEX uk_c_ai_chronic_art_req ON c_ai_chronic_artifact (
    id_org,
    (CASE WHEN fg_active = '1' THEN request_id END)
);
CREATE INDEX idx_c_ai_chronic_art_pat ON c_ai_chronic_artifact (
    id_org, id_his_org, patient_id, insert_time, fg_active
);
CREATE INDEX idx_c_ai_chronic_art_type ON c_ai_chronic_artifact (
    id_org, artifact_type, assessment_year, insert_time, fg_active
);


CREATE TABLE c_ai_user (
    id_user              VARCHAR2(32) PRIMARY KEY,
    cd_user              VARCHAR2(64) NOT NULL,
    na_user              VARCHAR2(128) NOT NULL,
    password_hash        VARCHAR2(128) NOT NULL,
    id_org               VARCHAR2(32),
    sd_status            VARCHAR2(2) DEFAULT '1' NOT NULL,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_user IS '用户表';
COMMENT ON COLUMN c_ai_user.id_user IS '用户主键ID';
COMMENT ON COLUMN c_ai_user.cd_user IS '用户账号';
COMMENT ON COLUMN c_ai_user.na_user IS '用户姓名';
COMMENT ON COLUMN c_ai_user.password_hash IS '密码摘要';
COMMENT ON COLUMN c_ai_user.id_org IS '所属机构ID';
COMMENT ON COLUMN c_ai_user.sd_status IS '状态';
COMMENT ON COLUMN c_ai_user.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_user.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_user.update_time IS '更新时间';

CREATE INDEX idx_c_ai_user_code ON c_ai_user (cd_user, fg_active);
CREATE INDEX idx_c_ai_user_org ON c_ai_user (id_org, fg_active);
CREATE UNIQUE INDEX uk_c_ai_user_code_active ON c_ai_user (
    CASE WHEN fg_active = '1' THEN cd_user END
);


CREATE TABLE c_ai_role (
    id_role              VARCHAR2(32) PRIMARY KEY,
    cd_role              VARCHAR2(64) NOT NULL,
    na_role              VARCHAR2(128) NOT NULL,
    des_role             VARCHAR2(500),
    sd_status            VARCHAR2(2) DEFAULT '1' NOT NULL,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_role IS '角色表';
COMMENT ON COLUMN c_ai_role.id_role IS '角色主键ID';
COMMENT ON COLUMN c_ai_role.cd_role IS '角色编码';
COMMENT ON COLUMN c_ai_role.na_role IS '角色名称';
COMMENT ON COLUMN c_ai_role.des_role IS '角色说明';
COMMENT ON COLUMN c_ai_role.sd_status IS '状态';
COMMENT ON COLUMN c_ai_role.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_role.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_role.update_time IS '更新时间';

CREATE INDEX idx_c_ai_role_code ON c_ai_role (cd_role, fg_active);
CREATE UNIQUE INDEX uk_c_ai_role_code_active ON c_ai_role (
    CASE WHEN fg_active = '1' THEN cd_role END
);


CREATE TABLE c_ai_user_role (
    id_user_role         VARCHAR2(32) PRIMARY KEY,
    id_user              VARCHAR2(32) NOT NULL,
    id_role              VARCHAR2(32) NOT NULL,
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_user_role IS '用户角色关联表';
COMMENT ON COLUMN c_ai_user_role.id_user_role IS '关联主键ID';
COMMENT ON COLUMN c_ai_user_role.id_user IS '用户ID';
COMMENT ON COLUMN c_ai_user_role.id_role IS '角色ID';
COMMENT ON COLUMN c_ai_user_role.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_user_role.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_user_role.update_time IS '更新时间';

CREATE INDEX idx_c_ai_user_role_user ON c_ai_user_role (id_user, fg_active);
CREATE INDEX idx_c_ai_user_role_role ON c_ai_user_role (id_role, fg_active);
CREATE UNIQUE INDEX uk_c_ai_user_role_active ON c_ai_user_role (
    CASE WHEN fg_active = '1' THEN id_user END,
    CASE WHEN fg_active = '1' THEN id_role END
);


CREATE TABLE c_ai_bbp_admin_grant (
    id_grant             VARCHAR2(32) PRIMARY KEY,
    tenant_id            VARCHAR2(64) NOT NULL,
    org_id               VARCHAR2(64) NOT NULL,
    org_name             VARCHAR2(128),
    bbp_user_id          VARCHAR2(64) NOT NULL,
    person_id            VARCHAR2(64),
    login_name           VARCHAR2(128),
    person_name          VARCHAR2(128),
    role_code            VARCHAR2(64) NOT NULL,
    sd_status            VARCHAR2(2) DEFAULT '1' NOT NULL,
    operator_user_id     VARCHAR2(64),
    operator_user_name   VARCHAR2(128),
    fg_active            CHAR(1) DEFAULT '1' NOT NULL,
    insert_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_bbp_admin_grant IS 'BBP人员PCIE后台访问授权表';
COMMENT ON COLUMN c_ai_bbp_admin_grant.id_grant IS '授权主键ID';
COMMENT ON COLUMN c_ai_bbp_admin_grant.tenant_id IS 'BBP租户ID';
COMMENT ON COLUMN c_ai_bbp_admin_grant.org_id IS 'BBP机构ID';
COMMENT ON COLUMN c_ai_bbp_admin_grant.org_name IS '机构名称快照';
COMMENT ON COLUMN c_ai_bbp_admin_grant.bbp_user_id IS 'BBP稳定用户ID';
COMMENT ON COLUMN c_ai_bbp_admin_grant.person_id IS 'BBP人员ID快照';
COMMENT ON COLUMN c_ai_bbp_admin_grant.login_name IS 'BBP登录名快照';
COMMENT ON COLUMN c_ai_bbp_admin_grant.person_name IS '人员姓名快照';
COMMENT ON COLUMN c_ai_bbp_admin_grant.role_code IS 'PCIE后台角色编码';
COMMENT ON COLUMN c_ai_bbp_admin_grant.sd_status IS '授权状态';
COMMENT ON COLUMN c_ai_bbp_admin_grant.operator_user_id IS '最近操作人ID';
COMMENT ON COLUMN c_ai_bbp_admin_grant.operator_user_name IS '最近操作人名称';
COMMENT ON COLUMN c_ai_bbp_admin_grant.fg_active IS '逻辑删除标记';
COMMENT ON COLUMN c_ai_bbp_admin_grant.insert_time IS '创建时间';
COMMENT ON COLUMN c_ai_bbp_admin_grant.update_time IS '更新时间';

CREATE INDEX idx_c_ai_bbp_admin_org ON c_ai_bbp_admin_grant (tenant_id, org_id, sd_status, fg_active);
CREATE INDEX idx_c_ai_bbp_admin_user ON c_ai_bbp_admin_grant (tenant_id, bbp_user_id, fg_active);
CREATE UNIQUE INDEX uk_c_ai_bbp_admin_active ON c_ai_bbp_admin_grant (
    CASE WHEN fg_active = '1' THEN tenant_id END,
    CASE WHEN fg_active = '1' THEN org_id END,
    CASE WHEN fg_active = '1' THEN bbp_user_id END,
    CASE WHEN fg_active = '1' THEN role_code END
);


CREATE TABLE c_ai_user_ai_permission (
    id_permission       VARCHAR2(32) PRIMARY KEY,
    tenant_id           VARCHAR2(64) NOT NULL,
    org_id              VARCHAR2(64) NOT NULL,
    org_name            VARCHAR2(128),
    person_id           VARCHAR2(64),
    user_id             VARCHAR2(64),
    person_cd           VARCHAR2(64),
    person_name         VARCHAR2(128),
    dept_id             VARCHAR2(64),
    dept_name           VARCHAR2(128),
    subject_key         VARCHAR2(128) NOT NULL,
    sd_status           VARCHAR2(2) DEFAULT '1' NOT NULL,
    operator_user_id    VARCHAR2(64),
    operator_user_name  VARCHAR2(128),
    fg_active           CHAR(1) DEFAULT '1' NOT NULL,
    insert_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE c_ai_user_ai_permission IS 'PCIE人员AI使用权限表';
COMMENT ON COLUMN c_ai_user_ai_permission.tenant_id IS 'BBP/PHIS租户ID';
COMMENT ON COLUMN c_ai_user_ai_permission.org_id IS 'BBP/PHIS机构ID，判权必填作用域';
COMMENT ON COLUMN c_ai_user_ai_permission.org_name IS '授权时机构名称快照';
COMMENT ON COLUMN c_ai_user_ai_permission.person_id IS 'BBP人员ID';
COMMENT ON COLUMN c_ai_user_ai_permission.user_id IS 'BBP人员对应用户ID';
COMMENT ON COLUMN c_ai_user_ai_permission.person_cd IS '人员真实编码/工号';
COMMENT ON COLUMN c_ai_user_ai_permission.person_name IS '人员姓名快照';
COMMENT ON COLUMN c_ai_user_ai_permission.dept_id IS '部门ID快照';
COMMENT ON COLUMN c_ai_user_ai_permission.dept_name IS '部门名称快照';
COMMENT ON COLUMN c_ai_user_ai_permission.subject_key IS '人员稳定唯一键，优先personId、其次userId、最后工号';
COMMENT ON COLUMN c_ai_user_ai_permission.sd_status IS 'AI使用权限状态：1允许 0撤销';
COMMENT ON COLUMN c_ai_user_ai_permission.operator_user_id IS '最近操作PCIE管理员ID';
COMMENT ON COLUMN c_ai_user_ai_permission.operator_user_name IS '最近操作PCIE管理员名称';
COMMENT ON COLUMN c_ai_user_ai_permission.fg_active IS '逻辑删除标记';

CREATE UNIQUE INDEX uk_c_ai_user_ai_perm_active ON c_ai_user_ai_permission (
    CASE WHEN fg_active = '1' THEN tenant_id END,
    CASE WHEN fg_active = '1' THEN org_id END,
    CASE WHEN fg_active = '1' THEN subject_key END
);
CREATE INDEX idx_c_ai_user_ai_perm_person ON c_ai_user_ai_permission (tenant_id, org_id, person_id, fg_active, sd_status);
CREATE INDEX idx_c_ai_user_ai_perm_user ON c_ai_user_ai_permission (tenant_id, org_id, user_id, fg_active, sd_status);
CREATE INDEX idx_c_ai_user_ai_perm_cd ON c_ai_user_ai_permission (tenant_id, org_id, person_cd, fg_active, sd_status);


CREATE TABLE hi_ods_apply (
    id_apply            VARCHAR2(32) PRIMARY KEY,
    na_apply            VARCHAR2(256),
    sd_disp             VARCHAR2(2),
    cd_apply            VARCHAR2(128),
    sd_business         VARCHAR2(2),
    id_apply_sim        VARCHAR2(64),
    na_apply_sim        VARCHAR2(500),
    na_apply_group      VARCHAR2(256),
    id_vis              VARCHAR2(64),
    id_reg              VARCHAR2(64),
    id_pi               VARCHAR2(64),
    ids_diag            VARCHAR2(1000),
    nas_diag            VARCHAR2(1000),
    disease             VARCHAR2(128),
    na_disease          VARCHAR2(256),
    purpose             VARCHAR2(1000),
    remark              VARCHAR2(1000),
    id_doc_exec         VARCHAR2(64),
    na_doc_exec         VARCHAR2(128),
    id_dept_exec        VARCHAR2(64),
    na_dept_exec        VARCHAR2(128),
    id_part             VARCHAR2(64),
    na_part             VARCHAR2(256),
    id_cli              VARCHAR2(64),
    id_result           VARCHAR2(24),
    sd_apply            VARCHAR2(2) DEFAULT '0',
    fg_urgent           CHAR(1) DEFAULT '0',
    id_register         VARCHAR2(128),
    id_org              VARCHAR2(64),
    id_tet              VARCHAR2(64),
    revision            NUMBER(10) DEFAULT 1,
    insert_user         VARCHAR2(128),
    insert_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_user         VARCHAR2(128),
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    dt_exec             TIMESTAMP,
    is_poct             CHAR(1),
    stipulate           CHAR(1),
    des_prob            VARCHAR2(1000),
    des_cur_die         CLOB,
    complete_check      CLOB,
    fg_digital          CHAR(1),
    fg_ct_reduct        CHAR(1),
    fg_day_first        CHAR(1)
);

COMMENT ON TABLE hi_ods_apply IS '申请单记录,传给第三方数据';
COMMENT ON COLUMN hi_ods_apply.id_apply IS '主键';
COMMENT ON COLUMN hi_ods_apply.na_apply IS '申请单名称;诊疗项目名称';
COMMENT ON COLUMN hi_ods_apply.sd_disp IS '申请单类别;phis.ods.dispType,1.检验,2检查,3.治疗,4手术,9其他';
COMMENT ON COLUMN hi_ods_apply.cd_apply IS '申请单号';
COMMENT ON COLUMN hi_ods_apply.sd_business IS '申请类别;phis.ods.businessType1.门诊,2住院';
COMMENT ON COLUMN hi_ods_apply.id_apply_sim IS '同单据标识;用于标识哪些申请单是同时开的';
COMMENT ON COLUMN hi_ods_apply.na_apply_sim IS '同单据名称;标识那些同时开的单据名称合集';
COMMENT ON COLUMN hi_ods_apply.na_apply_group IS '组套名称;用于标识组套项目';
COMMENT ON COLUMN hi_ods_apply.id_vis IS '就诊主键;住院存住院唯一号';
COMMENT ON COLUMN hi_ods_apply.id_reg IS '挂号主键;住院存住院唯一号';
COMMENT ON COLUMN hi_ods_apply.id_pi IS '患者主键';
COMMENT ON COLUMN hi_ods_apply.ids_diag IS '关联诊断串';
COMMENT ON COLUMN hi_ods_apply.nas_diag IS '关联诊断串名称';
COMMENT ON COLUMN hi_ods_apply.disease IS '病种编码';
COMMENT ON COLUMN hi_ods_apply.na_disease IS '病种名称';
COMMENT ON COLUMN hi_ods_apply.purpose IS '检查目的';
COMMENT ON COLUMN hi_ods_apply.remark IS '备注';
COMMENT ON COLUMN hi_ods_apply.id_doc_exec IS '执行医生';
COMMENT ON COLUMN hi_ods_apply.na_doc_exec IS '执行医生名称';
COMMENT ON COLUMN hi_ods_apply.id_dept_exec IS '执行科室';
COMMENT ON COLUMN hi_ods_apply.na_dept_exec IS '执行科室名称';
COMMENT ON COLUMN hi_ods_apply.id_part IS '部位;检查关联hi_bd_cli_pacs_part表,检验预留后期扩展,其他诊疗项目后续扩展';
COMMENT ON COLUMN hi_ods_apply.na_part IS '部位名称;检查是部位+方式,检验是部位';
COMMENT ON COLUMN hi_ods_apply.id_cli IS '诊疗项目';
COMMENT ON COLUMN hi_ods_apply.id_result IS '报告ID';
COMMENT ON COLUMN hi_ods_apply.sd_apply IS '申请单状态;只记录申请单状态.是否已收费需要关联医嘱表phis.ods.applyStatus,0新建,1,提交,2已执行,3已报告.9已作废';
COMMENT ON COLUMN hi_ods_apply.fg_urgent IS '加急标志;sys.sd.yesOrNo 1是0否,默认0';
COMMENT ON COLUMN hi_ods_apply.id_register IS '登记号;第三方系统接收后返回的唯一号';
COMMENT ON COLUMN hi_ods_apply.id_org IS '机构编号';
COMMENT ON COLUMN hi_ods_apply.id_tet IS '租户号';
COMMENT ON COLUMN hi_ods_apply.revision IS '乐观锁';
COMMENT ON COLUMN hi_ods_apply.insert_user IS '创建人';
COMMENT ON COLUMN hi_ods_apply.insert_time IS '创建时间';
COMMENT ON COLUMN hi_ods_apply.update_user IS '更新人';
COMMENT ON COLUMN hi_ods_apply.update_time IS '更新时间';
COMMENT ON COLUMN hi_ods_apply.dt_exec IS '执行日期';
COMMENT ON COLUMN hi_ods_apply.is_poct IS 'poct标志';
COMMENT ON COLUMN hi_ods_apply.stipulate IS '规定病标志';
COMMENT ON COLUMN hi_ods_apply.des_prob IS '主诉';
COMMENT ON COLUMN hi_ods_apply.des_cur_die IS '现病史';
COMMENT ON COLUMN hi_ods_apply.complete_check IS '体格检查';
COMMENT ON COLUMN hi_ods_apply.fg_digital IS '数字影像费标志';
COMMENT ON COLUMN hi_ods_apply.fg_ct_reduct IS '是否有检查CT减免标识';
COMMENT ON COLUMN hi_ods_apply.fg_day_first IS '当日该项目第一条标识';

CREATE INDEX idx_hi_ods_apply_wait ON hi_ods_apply (sd_disp, sd_apply, id_result, insert_time);
CREATE INDEX idx_hi_ods_apply_cd ON hi_ods_apply (cd_apply);
CREATE INDEX idx_hi_ods_apply_org ON hi_ods_apply (id_org, insert_time);


CREATE TABLE hi_ods_apply_lis_report (
    id_report           VARCHAR2(24) PRIMARY KEY,
    id_apply            VARCHAR2(32),
    id_result           VARCHAR2(24),
    resultid            VARCHAR2(24),
    na_result           VARCHAR2(256),
    test_result         VARCHAR2(128),
    result_qualitative  VARCHAR2(256),
    reference_range     VARCHAR2(128),
    reference_low       VARCHAR2(64),
    reference_high      VARCHAR2(64),
    result_unit         VARCHAR2(64),
    result_hint         VARCHAR2(64),
    cd_result           VARCHAR2(128),
    instrument_code     VARCHAR2(128),
    instrument_name     VARCHAR2(256),
    id_org              VARCHAR2(64),
    id_tet              VARCHAR2(64),
    revision            NUMBER(10) DEFAULT 1,
    insert_user         VARCHAR2(128),
    insert_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_user         VARCHAR2(128),
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ctr                 CLOB,
    id_report_group     VARCHAR2(24)
);

COMMENT ON TABLE hi_ods_apply_lis_report IS '检验常规报告';
COMMENT ON COLUMN hi_ods_apply_lis_report.id_report IS '主键';
COMMENT ON COLUMN hi_ods_apply_lis_report.id_apply IS '申请单主键;hi_ods_apply,废弃,下个版本去除';
COMMENT ON COLUMN hi_ods_apply_lis_report.id_result IS '结果集主键;hi_ods_lis_result';
COMMENT ON COLUMN hi_ods_apply_lis_report.resultid IS '第三方结果集主键;跟id_result不会同时存在';
COMMENT ON COLUMN hi_ods_apply_lis_report.na_result IS '检验项目名称';
COMMENT ON COLUMN hi_ods_apply_lis_report.test_result IS '检验定量结果';
COMMENT ON COLUMN hi_ods_apply_lis_report.result_qualitative IS '检验定性结果';
COMMENT ON COLUMN hi_ods_apply_lis_report.reference_range IS '参考范围';
COMMENT ON COLUMN hi_ods_apply_lis_report.reference_low IS '参考值下限';
COMMENT ON COLUMN hi_ods_apply_lis_report.reference_high IS '参考值上限';
COMMENT ON COLUMN hi_ods_apply_lis_report.result_unit IS '单位';
COMMENT ON COLUMN hi_ods_apply_lis_report.result_hint IS '结果异常提示';
COMMENT ON COLUMN hi_ods_apply_lis_report.cd_result IS '结果编码';
COMMENT ON COLUMN hi_ods_apply_lis_report.instrument_code IS '仪器编号';
COMMENT ON COLUMN hi_ods_apply_lis_report.instrument_name IS '仪器名称';
COMMENT ON COLUMN hi_ods_apply_lis_report.id_org IS '机构编号';
COMMENT ON COLUMN hi_ods_apply_lis_report.id_tet IS '租户号';
COMMENT ON COLUMN hi_ods_apply_lis_report.revision IS '乐观锁';
COMMENT ON COLUMN hi_ods_apply_lis_report.insert_user IS '报告医生';
COMMENT ON COLUMN hi_ods_apply_lis_report.insert_time IS '报告时间';
COMMENT ON COLUMN hi_ods_apply_lis_report.update_user IS '审核医生';
COMMENT ON COLUMN hi_ods_apply_lis_report.update_time IS '审核时间';
COMMENT ON COLUMN hi_ods_apply_lis_report.ctr IS '扩展字段';
COMMENT ON COLUMN hi_ods_apply_lis_report.id_report_group IS '报告id;一份报告的唯一标识,hi_ods_apply中的id_result';

CREATE INDEX idx_hi_lis_report_apply ON hi_ods_apply_lis_report (id_apply);
CREATE INDEX idx_hi_lis_report_group ON hi_ods_apply_lis_report (id_report_group);


CREATE TABLE hi_ods_apply_pacs_report (
    id_report             VARCHAR2(24) PRIMARY KEY,
    id_apply              VARCHAR2(32),
    "RESULT"              CLOB,
    remark                VARCHAR2(1000),
    clinical_impression   VARCHAR2(1000),
    negative_positive     VARCHAR2(32),
    diagnostic_imaging    CLOB,
    na_update_user        VARCHAR2(128),
    na_insert_user        VARCHAR2(128),
    cd_study              VARCHAR2(128),
    id_dept               VARCHAR2(64),
    na_dept               VARCHAR2(128),
    id_org                VARCHAR2(64),
    id_tet                VARCHAR2(64),
    revision              NUMBER(10) DEFAULT 1,
    insert_user           VARCHAR2(128),
    insert_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_user           VARCHAR2(128),
    update_time           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE hi_ods_apply_pacs_report IS '检查报告';
COMMENT ON COLUMN hi_ods_apply_pacs_report.id_report IS '主键';
COMMENT ON COLUMN hi_ods_apply_pacs_report.id_apply IS '申请id;hi_ods_apply主键';
COMMENT ON COLUMN hi_ods_apply_pacs_report."RESULT" IS '检查结果';
COMMENT ON COLUMN hi_ods_apply_pacs_report.remark IS '备注信息';
COMMENT ON COLUMN hi_ods_apply_pacs_report.clinical_impression IS '临床印象';
COMMENT ON COLUMN hi_ods_apply_pacs_report.negative_positive IS '阴阳性';
COMMENT ON COLUMN hi_ods_apply_pacs_report.diagnostic_imaging IS '影像诊断';
COMMENT ON COLUMN hi_ods_apply_pacs_report.na_update_user IS '审核医生姓名;有些第三方接口只传中文';
COMMENT ON COLUMN hi_ods_apply_pacs_report.na_insert_user IS '报告医生姓名;有些第三方接口只传中文';
COMMENT ON COLUMN hi_ods_apply_pacs_report.cd_study IS '影像号';
COMMENT ON COLUMN hi_ods_apply_pacs_report.id_dept IS '报告科室;phis自行增加结果有该字段 放射科-A1 超声科-A2 内镜中心-A3 心电室-A4';
COMMENT ON COLUMN hi_ods_apply_pacs_report.na_dept IS '报告科室名称';
COMMENT ON COLUMN hi_ods_apply_pacs_report.id_org IS '机构编号';
COMMENT ON COLUMN hi_ods_apply_pacs_report.id_tet IS '租户号';
COMMENT ON COLUMN hi_ods_apply_pacs_report.revision IS '乐观锁';
COMMENT ON COLUMN hi_ods_apply_pacs_report.insert_user IS '报告医生';
COMMENT ON COLUMN hi_ods_apply_pacs_report.insert_time IS '报告时间';
COMMENT ON COLUMN hi_ods_apply_pacs_report.update_user IS '审核医生';
COMMENT ON COLUMN hi_ods_apply_pacs_report.update_time IS '审核时间';

CREATE INDEX idx_hi_pacs_report_apply ON hi_ods_apply_pacs_report (id_apply);
CREATE INDEX idx_hi_pacs_report_study ON hi_ods_apply_pacs_report (cd_study);


INSERT INTO c_ai_region (id_region, cd_region, na_region, sd_region_type, sd_status, fg_active)
VALUES ('REGION001', 'REG001', '默认区域', 'district', '1', '1');

INSERT INTO c_ai_org (id_org, cd_org, na_org, id_region, sd_org_type, sd_status, fg_active)
VALUES ('ORG001', 'ORG001', '默认机构', 'REGION001', 'community', '1', '1');

INSERT INTO c_ai_config (
    id_config,
    cd_config,
    na_config,
    provider,
    api_base_url,
    model_name,
    fast_model_name,
    enable_thinking,
    audio_base_url,
    audio_model,
    speech_provider,
    speech_model,
    knowledge_base_enabled,
    pmphai_enabled,
    reviewer_enabled,
    reviewer_model,
    reviewer_check_examination_enabled,
    features_json,
    id_org,
    sd_status,
    fg_active
) VALUES (
    'CFG001',
    'DEFAULT',
    '默认AI配置',
    'openai-compatible',
    'http://127.0.0.1:65535/v1',
    'gpt-4o-mini',
    'gpt-4o-mini',
    '0',
    'https://dashscope.aliyuncs.com/compatible-mode/v1',
    'qwen3-asr-flash',
    'aliyun-dashscope',
    'qwen-audio-3.0-asr-flash-streaming',
    '0',
    '0',
    '0',
    'gpt-4o-mini',
    '1',
    '{"regionalMode":true,"aiProxyEnabled":true,"auditEnabled":true}',
    'ORG001',
    '1',
    '1'
);

INSERT INTO c_ai_role (id_role, cd_role, na_role, des_role, sd_status, fg_active)
VALUES ('ROLE001', 'SYSTEM_ADMIN', '系统管理员', '拥有全部后台权限', '1', '1');

INSERT INTO c_ai_role (id_role, cd_role, na_role, des_role, sd_status, fg_active)
VALUES ('ROLE002', 'ORG_ADMIN', '机构管理员', '管理所属 BBP 机构的 AI 使用权限', '1', '1');

INSERT INTO c_ai_role (id_role, cd_role, na_role, des_role, sd_status, fg_active)
VALUES ('ROLE003', 'ORG_ANALYST', '机构统计员', '只读查看所属 BBP 机构的统计分析、辅诊功能和用户活跃度', '1', '1');

INSERT INTO c_ai_user (id_user, cd_user, na_user, password_hash, id_org, sd_status, fg_active)
VALUES ('USER001', 'admin', '系统管理员', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', 'ORG001', '1', '1');

INSERT INTO c_ai_user_role (id_user_role, id_user, id_role, fg_active)
VALUES ('USERROLE001', 'USER001', 'ROLE001', '1');

COMMIT;
