# 达梦 DM8 定向升级说明

当前仓库不维护达梦全量初始化基线；现场 DM8 仍以 Oracle 兼容模式运行，定向升级脚本需使用应用 schema 账号执行。

已存在门诊模板快照表的库执行 `@update_outpatient_emr_template_snapshot.sql`，把唯一键调整为 `id_org + template_id + template_hash`。脚本只重建索引，不插入、更新或删除业务记录；执行期间须停止模板快照写入并核验退出码。

HIS 机构统计与客户端使用情况字段升级必须使用 DIsql 执行，并检查非零退出码：

```sql
@update_his_org_statistics.sql
```

脚本会补齐功能事件 `cd_doctor/client_version` 与 `idx_c_ai_feature_event_usage`，清空历史功能事件的临床关联与 payload，并重建幂等键。历史空工号和空版本不得猜测回填。执行前必须备份、停止写入、核查外部报表依赖并由 DBA 在维护窗口审核；达梦 DDL 可能隐式提交，遇错即停不等于整份脚本可原子回滚。

服务启动与 Actuator `featureEventSchema` readiness 会只读验证功能事件完整列和 `feature_event_minimization_v1` 标记；缺表、缺列、缺标记或权限不足时拒绝启动。使用情况索引仍须由 DBA 单独确认。

两慢病随访上线前执行：

```sql
@update_chronic_disease_followup.sql
@update_chronic_disease_artifact.sql
```

脚本可重复执行。`c_ai_chronic_followup` 保存原 `TcdVisitForm` 强类型融合随访：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 保存完整 DTO，`request_id` 来自 `X-Request-Id`；`c_ai_chronic_artifact` 保存健康处方和年度评估打印前的患者证据、版本与医生确认快照。两表均包含机构内请求幂等索引和必要查询索引。
