# 南大通用 PostgreSQL 候选初始化基线

`init.sql` 从本分支当前 GaussDB PG 业务结构建立，独立维护；覆盖当前实体、索引/唯一约束、注释与种子。仅在已核实产品、兼容模式和驱动的隔离空 schema 执行，首错即停；目标 database/schema/user 由 DBA 准备，不在应用启动时自动执行。现有测试库对象不自动迁移、删除或重建。

标记数据与其他种子统一位于全部 DDL 之后。SQL 使用 `VARCHAR/TEXT/NUMERIC/TIMESTAMP`、`CURRENT_TIMESTAMP` 和表达式唯一索引，空字符串与 NULL、中文长度、日期精度、索引 NULL 及事务语义仍须用目标产品实测。它不是 Informix/gbasedbt 的 `sql/gbase8s` 基线，也不将普通 openGauss 结果视作验收。

住院病历日志基线包含 `c_ai_inpatient_emr_log` 与 `c_ai_inpatient_emr_log_event`，其列、非空约束、外键、注释和 7 个索引与当前 GaussDB PG 基线保持一致；轮次、事件 ID 与事件序号分别由唯一索引约束。该完整初始化基线不自动迁移已存在的测试库对象，也不新增 GBase 现场迁移脚本。

连接与显式验证命令、驱动来源和未完成项目见 [`deploy/gbase-postgresql/README.md`](../../../../../../deploy/gbase-postgresql/README.md)。默认 schema 回归检查实体（含继承列）、定向更新 DDL 覆盖、对象唯一及种子顺序；静态通过不代替真实空库执行。
