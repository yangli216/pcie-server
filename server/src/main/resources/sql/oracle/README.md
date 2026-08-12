# Oracle 初始化说明

本目录仅用于 Oracle 19c。华为高斯 GaussDB/openGauss PostgreSQL 兼容模式使用 `../gaussdb/init.sql`，修改业务 schema 时需要同步维护两套初始化基线。

Oracle 初始化分两层执行：

1. `bootstrap.sql`
   - 使用 `SYSTEM` 或具备 DBA 权限的账号执行
   - 默认会准备应用 schema：`RBMH_AI / RBMH_AI`
   - 作用：创建业务表空间；在使用独立 schema 时创建业务 schema/user，并授予建表所需权限
2. `init.sql`
   - 必须使用当前应用连接账号登录后执行
   - 当前仓库已切换到安全模式，`application.yml` 不再内置 `FB_DB_URL / FB_DB_USERNAME / FB_DB_PASSWORD` 默认值
   - 作用：创建业务表、索引和默认种子数据
   - 说明：不显式声明 `TABLESPACE`，由当前登录用户/默认表空间决定对象落点；脚本只保留标准 Oracle DDL/DML，避免 `SET/DEFINE` 之类 SQL*Plus 指令在通用客户端里报 `ORA-00922`

## 为什么没有 `CREATE DATABASE`

Oracle 通常不会像 MySQL 一样在应用脚本里直接执行 `CREATE DATABASE`。
在当前项目里：

- 数据库实例 / SID / Service 由 DBA 预先准备
- 应用侧只负责 schema(user) 级初始化

## 推荐执行顺序

### 1. DBA 账号执行

```sql
@bootstrap.sql
```

当前默认目标用户：

- schema/user: `RBMH_AI`
- password: `RBMH_AI`
- tablespace: `FLOATING_BALL_TS`

说明：以上仅是 `bootstrap.sql` 的初始化模板，不代表仓库中的运行时默认连接信息。

如果后续要改回独立业务 schema，再修改 `bootstrap.sql` 中的：

- `v_tablespace_name`
- `v_tablespace_file`
- `v_target_user`
- `v_target_password`

默认模式下，`bootstrap.sql` 会：

1. 创建表空间
2. 创建或复用 `RBMH_AI`
3. 为 `RBMH_AI` 授权并设置默认表空间
4. 由执行人员切换到与 `FB_DB_USERNAME` 一致的 schema 后，再执行 `init.sql`

### 2. 使用当前应用账号执行

```sql
@init.sql
```

`init.sql` 当前是工程交付的权威基线，已包含：

1. `c_ai_config` 的语音独立密钥、PMPHAI / Reviewer 服务端托管字段、思考模式、fast model 和检查项目独立审查开关
2. `c_ai_device.device_public_key` 请求签名公钥字段，以及 `register_ip` / `last_seen_ip` 注册与最近访问来源字段
3. 症状模板、住院病历模板字段缓存、模板变更日志、辅诊功能事件、安全拒绝日志等业务表；功能事件的 `cd_doctor` 与 `client_version` 固化医生真实工号和事件发生时客户端版本，用于医生客户端使用情况统计
4. 操作日志、问诊日志、反馈日志、推荐偏好事件和推荐偏好聚合的结构化查询列、语音复盘字段、变更摘要字段和并发唯一索引；其中问诊日志 `cd_doctor` 保存 SDK handshake `urt.personCd` 对应的真实工号，`id_doctor` 仍是 HIS 内部主键；问诊日志唯一索引只约束尚未结束的 `generated` 记录，同一就诊回写或放弃后再次问诊会保留为新的日志轮次
5. 第三方 ODS 检验检查申请单 `hi_ods_apply`、检验常规报告 `hi_ods_apply_lis_report` 与检查报告 `hi_ods_apply_pacs_report`，用于管理端手工模拟第三方结果回写；不包含未提供结构的 `hi_ods_lis_result` 主表
6. 默认区域 `REGION001`
7. 默认机构 `ORG001`；`c_ai_org.cd_org` 必填，并通过 `uk_c_ai_org_code_active` 保证激活机构编码唯一
8. 默认管理员 `admin`
9. 默认 AI 配置 `CFG001`；DashScope 默认实时模型为 `qwen-audio-3.0-asr-flash-streaming`，实时语音 WebSocket 上游独立保存在 `speech_realtime_url`，自建 FunASR 使用 `speech_provider=funasr-websocket`
10. 脚本末尾显式 `COMMIT`
11. `c_ai_chronic_followup` 保存原 `TcdVisitForm` 高血压/糖尿病融合随访：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 无损保存强类型 DTO；`request_id` 来自 `X-Request-Id`，在平台机构激活记录内幂等。旧通用列暂留作未发布实验表兼容，不再作为业务请求结构
12. `c_ai_chronic_artifact` 健康处方与年度评估打印留痕快照，固化患者证据截止时间、病种及版本、年度指标、医生确认项和打印医生
13. `c_ai_request_nonce` 以 `(id_device, nonce_hash)` 复合主键保存已验签请求 nonce 的 SHA-256 哈希，并用 epoch 毫秒 `expires_at` 支撑所有应用节点共享防重放状态和过期清理
说明：

1. 默认 AI 配置仅用于打通 `register -> bootstrap -> audit` 的启动联调链路
2. 真正的上游 AI 地址、密钥、模型请在删库重建后再通过管理端修改；HTTP 批量转写地址与 `speech_realtime_url` 实时 WebSocket 地址必须分开配置
3. 新建库仍采用“目标 schema 初始化/重建 + 重跑 `init.sql`”；定向保留 `update_his_org_statistics.sql`、`update_chronic_disease_followup.sql` 与 `update_chronic_disease_artifact.sql`，分别用于补齐 HIS 统计契约、两慢病随访表和打印留痕快照表，不作为通用升级脚本目录
4. 执行 `init.sql` 前请确认当前登录 schema 就是 `RBMH_AI`；脚本本身不再依赖 SQL*Plus 变量做前置校验
5. 区域与机构的 `sd_status` 是启用/停用状态；`fg_active` 只表示逻辑删除/无效记录。管理端统计筛选只统计 `fg_active='1' AND sd_status='1'` 的区域和机构。

## 存量库处理

当前仓库不保留通用 `upgrade_*.sql` 补丁链。各历史补丁仍折叠进 `init.sql`；本次用户明确要求交付的 `update_his_org_statistics.sql` 是一次性、可重复执行的定向升级文件，包含现场库可能遗漏的 `c_ai_config.speech_realtime_url`、`c_ai_user_consultation_log.id_his_org`、`consultation_round_id` 及问诊轮次索引，以及操作日志和功能事件新增的 HIS 机构字段、功能事件 `cd_doctor/client_version` 与使用情况索引、可确定关联的数据回填。存量功能事件中缺失的 `cd_doctor/client_version` 保持 `NULL`：不得用 `id_doctor`、后台账号或设备当前版本猜测回填。脚本还会清空全部历史功能事件的 `consultation_id/trace_id/session_id/payload_json`，并按服务端同一 `feature_code:minimized:v1:event:规范化 id_event` 规则重建全表幂等键；历史 `idempotency_key` 即使为 `NULL` 也会作为待重建数据处理。脚本会在任何 DDL/DML 前拒绝空 `feature_code`、非 UUID 历史 `id_event` 或目标唯一键碰撞；清理和键改写均带 no-op 条件，且只有全部步骤成功后才写入 `c_ai_schema_migration.feature_event_minimization_v1` 标记，重复执行不会重写。执行前必须备份、停止全部服务节点写入、统计候选行数、确认仓库外 SQL/BI 不依赖功能事件 payload，并由 DBA 在维护窗口审核执行这一数据最小化 DML。

如果现场库已经存在旧版本业务表，处理原则如下：

1. 能重建的开发/联调环境，先备份必要数据，再清理目标 schema 并执行 `init.sql`
2. 不能重建的生产/准生产环境，由 DBA 基于当前 `init.sql` 与现场库结构生成一次性迁移脚本
3. 一次性迁移脚本必须先清理重复激活数据，再添加唯一索引，例如机构编码、设备编码、设备令牌、反馈最新版、问诊日志未结束轮次等约束
4. 迁移完成后，需要确认 `c_ai_config.speech_realtime_url`、`c_ai_device.device_public_key`、`c_ai_device.register_ip`、`c_ai_device.last_seen_ip`、`c_ai_user_consultation_log.id_his_org`、`c_ai_user_consultation_log.consultation_round_id`、`c_ai_feature_event.cd_doctor`、`c_ai_feature_event.client_version`、`idx_c_ai_feature_event_usage`、`idx_c_ai_user_log_round`、`uk_c_ai_user_log_round_active`、`c_ai_user_consultation_log.change_summary_json`、`c_ai_user_consultation_log.total_changes` 与 `c_security_rejection_log` 均已存在

本次 HIS 机构统计与医生工号字段升级必须使用 SQL*Plus 或 SQLcl 以当前应用 schema 执行；脚本第一条客户端指令为 `WHENEVER SQLERROR EXIT 1 ROLLBACK`，预检失败会停止后续语句并返回非零。脚本可重复执行，已执行过旧版脚本的存量库需要再次执行，以补充功能事件 `cd_doctor/client_version` 和 `idx_c_ai_feature_event_usage`；脚本不猜测回填历史空值。执行器必须检查进程退出码，不能用会吞掉错误的包装命令：

```sql
@update_his_org_statistics.sql
```

服务启动与 Actuator `featureEventSchema` readiness 会使用当前应用连接执行只读零行查询，确认 `c_ai_feature_event` 完整写入列及医生使用情况所需列可查询，并验证 `c_ai_schema_migration.feature_event_minimization_v1` 已存在；缺表、缺列、缺标记或权限不足时拒绝启动，并提示执行本目录的 `update_his_org_statistics.sql`。探针默认通过 `floating-ball.feature-event.schema-validation.enabled=true` 启用，不执行 DDL/DML，也不验证 `idx_c_ai_feature_event_usage`；该索引仍须由 DBA 在节点入池前确认存在，缺失时不得上线医生客户端使用情况功能。

两慢病随访上线到存量库前执行：

```sql
@update_chronic_disease_followup.sql
@update_chronic_disease_artifact.sql
```

## 多节点共享 nonce（显式启用）

`FB_DEPLOYMENT_MODE=standalone` 与 JVM 本地 nonce 不需要执行本节。存量库准备启用 `ai-scale-out` 时，仓库交付定向脚本 `update_shared_nonce.sql`，由现场 DBA 先审核脚本和目标 schema，再使用与应用一致的 schema 连接直接执行：

```sql
@update_shared_nonce.sql
```

交付边界固定如下：

1. 脚本只负责幂等创建 `c_ai_request_nonce`、复合主键 `(id_device, nonce_hash)` 和过期索引 `idx_c_ai_request_nonce_exp`；`nonce_hash` 保存 SHA-256 十六进制哈希，`expires_at` 保存 epoch 毫秒数。
2. 对象已经按当前合同存在时重复执行为 no-op；发现同名对象结构、主键或索引定义不一致时必须报错停止，由 DBA 先处理漂移，脚本不得静默改列、重建约束或覆盖现场对象。
3. 脚本不包含数据库地址、账号或口令，不执行 `CONNECT`，应用启动和部署脚本也不会连接数据库代为执行。执行动作、目标 schema、备份和结果确认都由 DBA 负责。
4. 脚本不读取、不迁移、不删除旧 `c_security_request_nonce`，也不包含任何 `DROP TABLE`、`DROP INDEX` 或历史 nonce 数据搬迁。nonce 是短期防重放状态，旧表如需清理由 DBA 在独立变更中另行评估。
5. 执行脚本不会自动切换应用模式。DBA 还必须确认应用账号对新表具备实际查询、插入和删除权限，并在节点入池前让 nonce 深度探针完整通过；深探发现权限或唯一约束漂移时，HTTP/WS 必须持续 `SECURITY-503`，直到后续深探恢复。

## 如果暂时继续使用 `SYSTEM`

如果你刻意要直接用 `SYSTEM` 账号连应用：

1. 同步把运行环境变量中的 `FB_DB_USERNAME / FB_DB_PASSWORD` 改成 `SYSTEM`
2. 把 `bootstrap.sql` 里的 `v_target_user / v_target_password` 改成 `SYSTEM`
3. `bootstrap.sql` 先创建表空间，再自动跳过建用户
4. 直接用 `SYSTEM` 执行 `init.sql`

但这只适合临时开发联调，不建议作为正式部署方案。
