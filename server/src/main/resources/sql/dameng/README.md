# 达梦 DM8 定向升级说明

当前仓库不维护达梦全量初始化基线；现场 DM8 仍以 Oracle 兼容模式运行，定向升级脚本需使用应用 schema 账号执行。

HIS 机构统计与医生真实工号字段升级执行：

```sql
@update_his_org_statistics.sql
```

脚本可重复执行；已执行过旧版脚本的存量库需要再次执行，以便为 `c_ai_user_consultation_log` 补充保存 SDK handshake `urt.personCd` 的 `cd_doctor` 列。`id_doctor` 继续表示 HIS 内部主键，不作为工号展示。

启用集群模式前执行：

```sql
@update_cluster_nonce.sql
```

该脚本可重复执行，创建 `c_security_request_nonce`、`(id_device, nonce_value)` 主键和过期时间索引。集群节点只在 ECDSA 验签成功后登记 nonce；表缺失或数据库不可用时 HTTP 返回 `SECURITY-503`、WebSocket 握手返回 503，不会回退 JVM 本地缓存。

集群所有节点必须通过 NTP 或 chrony 持续校时并对节点时钟偏差告警；`floating-ball.security.nonce-cleanup-grace-ms` 默认保留 300000 毫秒清理宽限，只用于避免时钟微小偏差导致过早删除，不能替代可靠校时。

`update_cluster_nonce.sql` 不仅创建缺失表，也会检查存量表是否存在精确覆盖 `(id_device, nonce_value)` 的主键或唯一约束；缺失时先拒绝重复数据，再补建唯一约束。集群启动与 `nonceStore` readiness 随后通过 JDBC 元数据和可回滚双次插入验证约束及 INSERT 权限，探针不会留下持久化数据；任一步失败都按不可就绪处理。

两慢病随访上线前执行：

```sql
@update_chronic_disease_followup.sql
@update_chronic_disease_artifact.sql
```

脚本可重复执行。`c_ai_chronic_followup` 保存原 `TcdVisitForm` 强类型融合随访：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 保存完整 DTO，`request_id` 来自 `X-Request-Id`；`c_ai_chronic_artifact` 保存健康处方和年度评估打印前的患者证据、版本与医生确认快照。两表均包含机构内请求幂等索引和必要查询索引。
