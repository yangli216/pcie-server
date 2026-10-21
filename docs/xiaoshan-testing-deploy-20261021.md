# 萧山测试环境发布记录：住院病历日志

日期：2026-10-21（America/Los_Angeles）。本次仅更新服务器 `192.168.204.122` 上的 9090 测试服务及其独立 Oracle schema；没有发布桌面端安装包，没有切换 8080 正式服务。

## 发布结果

- 入口：[测试管理端](http://192.168.204.122:9090/admin/)，菜单“住院病历日志”。
- 版本：`0.2.0-SNAPSHOT`，通过 `scripts/publish-xiaoshan.sh testing` 重新执行 Maven `clean package`，没有跳过测试或复用旧 JAR。
- 服务目录：`/data/floating-ball-server-testing`；profile `xiaoshan-test`，端口 `9090`，新 PID `232786`。
- 部署 JAR SHA-256：`6a47533f2559723beccf0bddc710219a6823050a28d48a356332d7bd040d13cf`，本地构建、上传及最终部署文件一致。
- 原有 `service.env` 内容摘要保持一致，权限仍为 `600`；未新增或覆盖现场私有配置。

## 数据库迁移

执行前通过运行进程配置确认测试数据库账号为 `RBMH_AI_TEST`，测试/正式连接的 schema 不同。查询确认两张住院日志表原先不存在。

先保存测试库原有 26 张应用表的结构，再仅停止 9090 测试服务，执行 Oracle `update_inpatient_emr_logs.sql` 的完整 DDL、前置 PL/SQL 和后置校验。远端没有 SQL*Plus，使用现有 JAR 的 Oracle JDBC 驱动和 `orai18n`，临时 Java 执行器按语句执行，发生 SQL 错误立即停止；SQL*Plus 客户端指令由执行器处理，不拆分 PL/SQL 块，不跳过预检或验证。连接凭据仅从运行进程环境及原有 profile 读取，没有写进交付文件或日志。

迁移执行 52 条语句，检查通过：

- `c_ai_inpatient_emr_log`：24 列。
- `c_ai_inpatient_emr_log_event`：11 列。
- 两主键、一外键，以及七个指定命名索引均有效；Oracle 自动生成的主键和 LOB 索引另外保留。
- 两表及所有字段注释来自当前初始化基线，没有回填历史代理日志或修改原有业务记录。

迁移留档目录（目录名称使用远端服务器时钟）：

```text
/data/floating-ball-server-testing/backups/inpatient-logs-20261021152055/
  schema-before-tables.sql
  update_inpatient_emr_logs.sql
  migration.log
```

`schema-before-tables.sql` 为表结构留档，不是全库业务数据备份。本次仅新增日志对象；没有对现有表做 DML、ALTER 或 DROP。迁移执行器与依赖解压目录已从远端临时目录清理。高斯脚本本次未在现场执行。

## 验收与正式环境保护

1. Maven 全新构建：536 项测试，0 失败、0 错误、1 项原有跳过；包括管理端 npm ci 和生产构建。
2. 发布脚本回归：`python3 scripts/test_publish_xiaoshan.py`，13 项全部通过。
3. JAR 校验包含管理端入口、入口引用的 4 个静态资源、住院日志服务端 controller 和两套迁移脚本。
4. 发布后从外部访问测试 readiness 返回 HTTP 200 / `UP`；`/admin/` 和入口引用的 4 个静态资源返回 200。住院日志页面单独的 JS/CSS 资源与本地构建逐字节一致。
5. 匿名访问住院日志列表、筛选选项，以及未携带设备身份上报日志均返回 401，鉴权仍生效。该检查不替代实际登录后的完整功能验收。
6. 部署前后正式服务 PID 始终为 `124341`，JAR SHA-256 始终为 `553c31d84623f06bf83950d7c7d77ec5d557c7ad38e637977ffd57d16a803a7d`，启动脚本 SHA-256 始终为 `899ac66a383f736da54ffaca85971fea6e3c6ae6b4cade3b3e9ac6ebf3b5c9b3`；正式 readiness 仍为 `UP`。
7. 测试环境发布锁已释放。工作区原有其他修改保留，未自动创建 Git 提交、标签或正式版本。

## 应用备份及后续验证

发布脚本保留的上一版：

```text
/data/floating-ball-server-testing/floating-ball-server.jar.backup-20261021002505-21878
/data/floating-ball-server-testing/start.sh.backup-20261021002505-21878
/data/floating-ball-server-testing/logs/archive/20261021002505-21878
```

后续仍需使用新版桌面端执行真实住院病历生成、医生修改、HIS 成功/失败回执及断网补传，核对管理端的原稿、最终稿和事件闭环。本次没有冒充医生登录或写入模拟临床日志。应用回退时保留新增审计表，不通过删除日志表回退。
