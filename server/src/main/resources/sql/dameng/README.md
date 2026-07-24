# 达梦 DM8 定向升级说明

当前仓库不维护达梦全量初始化基线；现场 DM8 仍以 Oracle 兼容模式运行，定向升级脚本需使用应用 schema 账号执行。

两慢病随访上线前执行：

```sql
@update_chronic_disease_followup.sql
@update_chronic_disease_artifact.sql
```

脚本可重复执行。`c_ai_chronic_followup` 保存原 `TcdVisitForm` 强类型融合随访：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 保存完整 DTO，`request_id` 来自 `X-Request-Id`；`c_ai_chronic_artifact` 保存健康处方和年度评估打印前的患者证据、版本与医生确认快照。两表均包含机构内请求幂等索引和必要查询索引。
