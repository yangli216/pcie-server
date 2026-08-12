# 达梦 DM8 定向升级说明

当前仓库不维护达梦全量初始化基线；现场 DM8 仍以 Oracle 兼容模式运行，定向升级脚本需使用应用 schema 账号执行。

HIS 机构统计与医生真实工号字段升级执行：

```sql
@update_his_org_statistics.sql
```

脚本可重复执行；已执行过旧版脚本的存量库需要再次执行，以便为 `c_ai_user_consultation_log` 补充保存 SDK handshake `urt.personCd` 的 `cd_doctor` 列。`id_doctor` 继续表示 HIS 内部主键，不作为工号展示。

## 多节点共享 nonce（显式启用）

单节点继续使用 JVM 本地 nonce 时不需要执行本节。准备多节点部署时，存量库必须先由 DBA 使用当前应用 schema 审核并通过 DIsql 执行；脚本第一条客户端指令为 `WHENEVER SQLERROR EXIT 1 ROLLBACK`，执行器必须检查非零退出码：

```sql
@update_shared_nonce.sql
```

该 Oracle 兼容脚本可重复执行，用于创建新表 `c_ai_request_nonce`、复合主键 `(id_device, nonce_hash)` 和过期索引 `idx_c_ai_request_nonce_exp`；`nonce_hash` 只保存 SHA-256 十六进制哈希，`expires_at` 保存 epoch 毫秒数。对象已经按当前合同存在时重复执行为 no-op；发现同名对象的列、主键或索引定义不一致时必须报错停止，不能静默改写现场对象。脚本本身不会切换应用运行模式；DBA 执行后仍需核对应用账号的查询、插入和删除权限，并让 nonce 深度探针通过。

旧表 `c_security_request_nonce` 不属于新设计：脚本不会读取、迁移或删除该表，仓库也不提供 `DROP TABLE`。nonce 是短期防重放状态，不自动迁移现场数据；如现场遗留旧表，只能由 DBA 在确认所有节点均已切换且观察窗口结束后另行评估清理。

两慢病随访上线前执行：

```sql
@update_chronic_disease_followup.sql
@update_chronic_disease_artifact.sql
```

脚本可重复执行。`c_ai_chronic_followup` 保存原 `TcdVisitForm` 强类型融合随访：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 保存完整 DTO，`request_id` 来自 `X-Request-Id`；`c_ai_chronic_artifact` 保存健康处方和年度评估打印前的患者证据、版本与医生确认快照。两表均包含机构内请求幂等索引和必要查询索引。
