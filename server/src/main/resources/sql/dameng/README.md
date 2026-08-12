# 达梦 DM8 定向升级说明

当前仓库不维护达梦全量初始化基线；现场 DM8 仍以 Oracle 兼容模式运行，定向升级脚本需使用应用 schema 账号执行。

HIS 机构统计与医生真实工号字段升级必须使用 DIsql 执行；脚本第一条客户端指令为 `WHENEVER SQLERROR EXIT 1 ROLLBACK`，预检失败会停止后续语句并返回非零。执行器必须检查进程退出码，不能用会吞掉错误的包装命令：

```sql
@update_his_org_statistics.sql
```

脚本可重复执行；已执行过旧版脚本的存量库需要再次执行，以便为 `c_ai_user_consultation_log` 补充保存 SDK handshake `urt.personCd` 的 `cd_doctor` 列，并为 `c_ai_feature_event` 补充 `cd_doctor/client_version` 与 `idx_c_ai_feature_event_usage`。`id_doctor` 继续表示 HIS 内部主键，不作为工号展示；存量功能事件缺失的 `cd_doctor/client_version` 保持 `NULL`，不得用 `id_doctor`、后台账号或设备当前版本猜测回填。脚本还会清空全部历史功能事件的 `consultation_id/trace_id/session_id/payload_json`，按服务端同一 `feature_code:minimized:v1:event:规范化 id_event` 规则重建全表幂等键；历史 `idempotency_key` 即使为 `NULL` 也会作为待重建数据处理。预检会在任何 DDL/DML 前拒绝空 `feature_code`、非 UUID 历史 `id_event` 或目标唯一键碰撞；清理和键改写均带 no-op 条件，且只有全部步骤成功后才写入 `c_ai_schema_migration.feature_event_minimization_v1` 标记，重复执行不会重写。执行前必须备份、停止全部服务节点写入、统计候选行数、确认仓库外 SQL/BI 不依赖功能事件 payload，并由 DBA 在维护窗口审核执行这一数据最小化 DML。达梦 DDL 可能隐式提交，遇错即停不代表整份脚本具备原子回滚能力。

服务启动与 Actuator `featureEventSchema` readiness 会使用当前应用连接执行只读零行查询，确认 `c_ai_feature_event` 完整写入列及医生使用情况所需列可查询，并验证 `c_ai_schema_migration.feature_event_minimization_v1` 已存在；缺表、缺列、缺标记或权限不足时拒绝启动，并提示执行本目录的 `update_his_org_statistics.sql`。探针默认通过 `floating-ball.feature-event.schema-validation.enabled=true` 启用，不执行 DDL/DML，也不验证 `idx_c_ai_feature_event_usage`；该索引仍须由 DBA 在节点入池前确认存在，缺失时不得上线医生客户端使用情况功能。

两慢病随访上线前执行：

```sql
@update_chronic_disease_followup.sql
@update_chronic_disease_artifact.sql
```

脚本可重复执行。`c_ai_chronic_followup` 保存原 `TcdVisitForm` 强类型融合随访：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 保存完整 DTO，`request_id` 来自 `X-Request-Id`；`c_ai_chronic_artifact` 保存健康处方和年度评估打印前的患者证据、版本与医生确认快照。两表均包含机构内请求幂等索引和必要查询索引。

## 多节点共享 nonce（显式启用）

单节点继续使用 JVM 本地 nonce 时不需要执行本节。准备多节点部署时，存量库必须先由 DBA 使用当前应用 schema 审核并通过 DIsql 执行；脚本第一条客户端指令为 `WHENEVER SQLERROR EXIT 1 ROLLBACK`，执行器必须检查非零退出码：

```sql
@update_shared_nonce.sql
```

该 Oracle 兼容脚本可重复执行，用于创建新表 `c_ai_request_nonce`、复合主键 `(id_device, nonce_hash)` 和过期索引 `idx_c_ai_request_nonce_exp`；`nonce_hash` 只保存 SHA-256 十六进制哈希，`expires_at` 保存 epoch 毫秒数。对象已经按当前合同存在时重复执行为 no-op；发现同名对象的列、主键或索引定义不一致时必须报错停止，不能静默改写现场对象。脚本本身不会切换应用运行模式；DBA 执行后仍需核对应用账号的查询、插入和删除权限，并让 nonce 深度探针通过。

旧表 `c_security_request_nonce` 不属于新设计：脚本不会读取、迁移或删除该表，仓库也不提供 `DROP TABLE`。nonce 是短期防重放状态，不自动迁移现场数据；如现场遗留旧表，只能由 DBA 在确认所有节点均已切换且观察窗口结束后另行评估清理。
