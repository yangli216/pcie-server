# 住院病历日志：存量库一次性迁移

交付日期：2026-10-21。按用户明确要求随工程交付，DBA 根据现场连接、schema、权限和维护窗口审核后执行。脚本初次交付时未连接现场数据库；后续按用户要求已在萧山独立测试 schema 完成 Oracle 迁移，见 [测试环境发布记录](xiaoshan-testing-deploy-20261021.md)。正式库及高斯现场库未迁移。

## 文件与适用范围

- Oracle 19c：[update_inpatient_emr_logs.sql](../server/src/main/resources/sql/oracle/update_inpatient_emr_logs.sql)，使用 SQL*Plus / SQLcl 的脚本执行方式。
- GaussDB/openGauss PostgreSQL 兼容模式：[update_inpatient_emr_logs.sql](../server/src/main/resources/sql/gaussdb/update_inpatient_emr_logs.sql)，使用 gsql；不适用于其他兼容模式。

适用于已有 PCIE 业务库、尚未创建住院病历日志对象的场景。每份文件独立包含完整 DDL，无需执行或包含整个 `init.sql`。两套脚本各自的建表、注释和索引定义与对应初始化基线逐字保持一致，由 schema 测试检查。

新建库仍执行对应 `init.sql`，不能再执行本迁移。已执行成功、只建好一部分或存在任何同名目标对象的库，会在建表前报错停止，避免把未知结构当作迁移成功。

## 新增对象

| 对象 | 内容 |
| --- | --- |
| `c_ai_inpatient_emr_log` | 24 列；每次生成最新快照，主键 `id_log` |
| `c_ai_inpatient_emr_log_event` | 11 列；不可变事件快照，主键 `id_event`；`id_log` 外键关联主表 |
| `uk_c_ai_inp_log_round` | 唯一：`id_device, generation_id` |
| `idx_c_ai_inp_log_time` | `generation_time, fg_active` |
| `idx_c_ai_inp_log_org` | `his_org_id, generation_time, fg_active` |
| `idx_c_ai_inp_log_patient` | `patient_id, admission_id, generation_time` |
| `idx_c_ai_inp_log_template` | `template_id, status, generation_time` |
| `uk_c_ai_inp_event_id` | 唯一：`id_device, event_id` |
| `uk_c_ai_inp_event_seq` | 唯一：`id_log, event_sequence` |

除上述七个命名索引，数据库还会为两张表的主键自动建立索引，自动名称无需一致。两张表及所有 35 列均有注释。Oracle 长文本为 CLOB、字符串采用 CHAR 长度语义；高斯长文本为 TEXT。没有种子数据、历史日志回填、现有表 ALTER、DML、删除或重建对象，也不创建数据库、账号、表空间或额外依赖。

## 执行准备

1. 备份现场 schema/业务数据，停止所有应用节点写入并保留客户端待补传队列，记录数据库版本和当前连接。脚本必须在没有待提交业务事务的专用会话中执行。
2. 用实际 `FB_DB_USERNAME` 对应的应用账号连接目标库，确认当前 schema 与应用查询使用的 schema 一致，拥有建表、建索引及注释权限。脚本会输出当前账号/schema，检查当前 schema 已有 `c_ai_device` 和 `c_ai_org`，但这些检查不能替代 DBA 核实正式/测试环境。
3. 检查两表及七个命名索引尚不存在。脚本在 DDL 前检查所有九个名称；同名表、视图、索引等对象都拒绝继续。成功后重复运行也会拒绝，不是幂等补齐脚本。
4. 文件为 UTF-8。Oracle 客户端须正确配置 UTF-8 文件解码和数据库字符集转换，避免中文注释乱码；不修改现场数据库字符集或 NLS 长度语义。

## Oracle 执行

使用 SQL*Plus / SQLcl，以应用用户直接登录；不要只通过 `ALTER SESSION SET CURRENT_SCHEMA` 切换其他用户，脚本会拒绝该情况。

```text
sqlplus -L APP_USER@DB_SERVICE
```

输入密码后，在专用会话中执行，路径替换为现场绝对路径：

```sql
SPOOL /absolute/path/inpatient-emr-logs-oracle.log
@/absolute/path/update_inpatient_emr_logs.sql
```

脚本启用 SQL/操作系统错误立即退出，成功时 `EXIT SUCCESS`，请检查执行器退出码与日志，不能仅凭出现“Table created”判断成功。脚本包含 SQL*Plus 指令和 PL/SQL 的 `/` 分隔符，不能作为单条 JDBC SQL 执行。

Oracle DDL 自动提交。即使错误处理写了 `ROLLBACK`，已经成功的建表、索引和注释也无法撤销，参见 [Oracle 19c DDL 事务说明](https://docs.oracle.com/en/database/oracle/oracle-database/19/tdddg/data-definition-language-ddl-statements.html)。失败后保持应用停止，由 DBA 对照基线及错误位置完成修复；不得直接重跑、吞掉错误或自动删除新表。若确需清理，只能由 DBA 确认这些对象由本次失败创建、完全没有业务数据和依赖后另行处理；本交付不包含自动 DROP 脚本。

## GaussDB/openGauss 执行

以应用账号连接正确 database；不把密码写入脚本或命令行。默认 search_path 的首个有效 schema 就是目标应用 schema 时，可以直接运行：

```bash
gsql -h DB_HOST -p DB_PORT -d DB_NAME -U APP_USER \
  -v ON_ERROR_STOP=1 -f /absolute/path/update_inpatient_emr_logs.sql
```

如果需要显式选择 schema，在 gsql 专用会话中先设置，再包含脚本；将 `app_schema` 替换为实际 schema，不要照抄占位符：

```sql
\set ON_ERROR_STOP on
SET search_path TO app_schema;
\i /absolute/path/update_inpatient_emr_logs.sql
```

脚本输出 `current_database/current_user/current_schema`，DDL 使用 `current_schema()` 对应的首个有效 schema。不要在应用运行期间修改全局 search_path。所有预检、DDL 和结构检查均在 `BEGIN/COMMIT` 内；gsql 遇错停止，未提交事务随断开回滚。交互执行失败后先 `ROLLBACK;`，核对故障并退出会话，不手工提交失败现场。执行器须检查非零退出码；成功时末尾显示迁移完成消息。

## 执行后验收

1. 自动后置检查必须通过：两表合计 35 列、两主键和一外键、七个命名且有效的查询索引；脚本同时输出表、索引及约束详情，DBA 对照上述定义核对。Oracle 检查失败仍可能保留已创建对象；高斯检查发生在 COMMIT 之前。
2. 恢复服务端，确认新表可读取；管理端“住院病历日志”应显示空列表。无需新建菜单权限或插入默认数据。
3. 使用更新客户端生成一份测试病历并回填，核对主表一轮记录、事件递增、重复事件不重复插入、详情原稿/最终稿及真实 HIS 回执。恢复期间允许已持久化客户端事件补传。
4. 发现应用问题时可回退应用版本并保留新增表。启用后已有审计数据，不应通过删除表回退。保留执行日志与备份，不在日志中保存密码或患者正文。

## 验证记录

两套迁移 DDL 与各自初始化基线的一致性、仅新增指定对象、遇错停止及事务边界由 `InpatientLogSchemaTest` 的 3 项测试检查。已执行 `mvn -f server/pom.xml test` 完成项目门禁（含管理端构建）：536 项，0 失败、0 错误、1 项原有跳过。SQL 目录交付白名单已仅新增本次授权脚本，`git diff --check` 通过。本机没有 sqlplus/gsql 或 Docker 环境；后续在萧山测试 Oracle 通过 JDBC 实际执行完整迁移及校验，详情见发布记录。高斯仍需现场实际执行验证。

高斯预检使用的匿名块和元数据列已对照 openGauss 官方文档核对：[DO](https://docs.opengauss.org/en/docs/latest/sql_reference/do.html)、[PG_CONSTRAINT](https://docs.opengauss.org/en/docs/latest/database_reference/pg_constraint.html)、[PG_INDEX](https://docs.opengauss.org/en/docs/latest/database_reference/pg_index.html)。现场账号还需拥有 plpgsql 的 USAGE 权限；版本及厂商兼容差异仍需实际执行验证。
