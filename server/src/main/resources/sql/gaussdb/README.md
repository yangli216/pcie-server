# GaussDB/openGauss 初始化说明

本目录用于华为高斯数据库 PostgreSQL 兼容模式，优先面向 `org.opengauss.Driver`。

## 运行配置

推荐使用 `gaussdb` profile：

```bash
SPRING_PROFILES_ACTIVE=gaussdb
FB_DB_URL=jdbc:opengauss://<host>:<port>/<database>
FB_DB_USERNAME=rbmh_ai
FB_DB_PASSWORD=******
FB_LOG_PATH=/opt/floating-ball-server/logs
java -jar pcie-server.jar
```

默认驱动为 `org.opengauss.Driver`，默认 MyBatis-Plus 方言为 `opengauss`。如现场使用兼容 PostgreSQL 协议的 GaussDB 实例，优先保持该驱动；不要在同一运行包中再额外混入 PostgreSQL JDBC 驱动，避免 openGauss 驱动与 PostgreSQL 驱动的类名空间冲突。

`gaussdb` profile 默认日志目录为 `/opt/floating-ball-server/logs`，管理端上传的客户端安装包与语音审计文件默认写入 `/opt/floating-ball-server/data/*`。服务器部署时应通过环境变量或 systemd `EnvironmentFile` 注入 `FB_DB_PASSWORD`、`FB_AES_KEY` 等敏感配置，并按现场规范覆盖日志目录和文件存储目录。

## 初始化顺序

1. DBA 先创建目标 database / schema / user，并授予建表、建索引、注释、DML 权限。
2. 使用与 `FB_DB_USERNAME` 一致的账号连接目标库和 schema。
3. 执行：

```sql
\i init.sql
```

`init.sql` 是 GaussDB 业务 schema 的初始化基线，结构与 Oracle `sql/oracle/init.sql` 对齐，包含业务表、索引、注释和默认种子数据。当前基线也包含 `c_ai_chronic_followup` 原 `TcdVisitForm` 两慢病融合随访表：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 无损保存强类型 DTO，`request_id` 来自 `X-Request-Id`；旧通用列暂留作未发布实验表兼容，不再作为业务请求结构。`c_ai_chronic_artifact` 固化健康处方与年度评估打印前的患者证据、版本和医生确认快照，两表都以平台机构 + `request_id` 保证激活记录幂等。`c_ai_request_nonce` 以 `(id_device, nonce_hash)` 复合主键保存已验签请求 nonce 的 SHA-256 哈希，并用 epoch 毫秒 `expires_at` 支撑所有应用节点共享防重放状态和过期清理。同时包含第三方 ODS 检验检查申请单 `hi_ods_apply`、检验常规报告 `hi_ods_apply_lis_report` 与检查报告 `hi_ods_apply_pacs_report`，用于管理端手工模拟第三方结果回写；未提供结构的 `hi_ods_lis_result` 不作为常驻工程资产创建。实时语音 WebSocket 上游独立保存在 `speech_realtime_url`，自建 FunASR 使用 `speech_provider=funasr-websocket`。问诊日志 `cd_doctor` 保存 SDK handshake `urt.personCd` 对应的真实工号，`id_doctor` 仍是 HIS 内部主键；问诊日志唯一索引只约束尚未结束的 `generated` 记录，同一就诊回写或放弃后再次问诊会保留为新的日志轮次；`c_ai_user_consultation_log.id_his_org` 单独记录桌面端从 HIS 握手上报的机构 ID，不覆盖后台机构 `id_org`。

## 本地 Docker 验证

商业版华为高斯 GaussDB 镜像通常由现场环境提供，本地可使用 openGauss PostgreSQL 兼容模式做 schema 与运行时烟测。macOS / Docker Desktop / ARM64 环境优先使用 lite 镜像，普通 openGauss 企业版镜像可能因 cgroup 运行时配置启动失败。

```bash
docker run -d --name floating-ball-opengauss-test \
  -p 15432:5432 \
  -e GS_PASSWORD='Rbmh_ai@123' \
  -e GS_DB=floating_ball \
  -e GS_USERNAME=rbmh_ai \
  enmotech/opengauss-lite:5.0.3

docker exec floating-ball-opengauss-test bash -lc \
  "export GAUSSHOME=/usr/local/opengauss PATH=/usr/local/opengauss/bin:\$PATH LD_LIBRARY_PATH=/usr/local/opengauss/lib:\$LD_LIBRARY_PATH; \
   gsql -h 127.0.0.1 -p 5432 -d floating_ball -U omm -W 'Rbmh_ai@123' \
   -c \"ALTER DATABASE floating_ball OWNER TO rbmh_ai; ALTER SCHEMA public OWNER TO rbmh_ai; GRANT ALL ON SCHEMA public TO rbmh_ai;\""

docker cp init.sql floating-ball-opengauss-test:/tmp/floating-ball-gaussdb-init.sql
docker exec floating-ball-opengauss-test bash -lc \
  "export GAUSSHOME=/usr/local/opengauss PATH=/usr/local/opengauss/bin:\$PATH LD_LIBRARY_PATH=/usr/local/opengauss/lib:\$LD_LIBRARY_PATH; \
   gsql -h 127.0.0.1 -p 5432 -d floating_ball -U rbmh_ai -W 'Rbmh_ai@123' \
   -v ON_ERROR_STOP=1 -f /tmp/floating-ball-gaussdb-init.sql"
```

应用烟测配置：

```bash
SPRING_PROFILES_ACTIVE=gaussdb
FB_DB_URL=jdbc:opengauss://127.0.0.1:15432/floating_ball
FB_DB_USERNAME=rbmh_ai
FB_DB_PASSWORD=Rbmh_ai@123
```

## 注意事项

1. GaussDB 脚本不提供 Oracle 风格的 `bootstrap.sql`；数据库、schema、用户和表空间通常由 DBA 按现场规范预先创建。
2. 激活记录唯一性使用表达式唯一索引实现，语义与 Oracle 基线一致；问诊日志只对激活且尚未结束的 `generated` 轮次做唯一约束。
3. 现场旧库不能重建时，由 DBA 基于当前 `init.sql` 与现场结构生成一次性迁移脚本；迁移脚本不作为常驻工程资产提交。
4. 若需要普通 PostgreSQL 运行，优先复用本目录结构作为 PG 兼容基线，再结合现场版本验证 JSON、表达式索引和时间函数兼容性。
5. 本次保留 `update_his_org_statistics.sql`、`update_chronic_disease_followup.sql` 与 `update_chronic_disease_artifact.sql`。存量库使用当前应用账号分别执行三个脚本：第一个补齐可能遗漏的 `c_ai_config.speech_realtime_url`、`c_ai_user_consultation_log.id_his_org`、`consultation_round_id`、真实工号列 `cd_doctor`、问诊轮次索引，以及操作日志和功能事件 HIS 机构字段、索引与可确定关联的数据回填；已执行过旧版脚本的存量库需要再次执行以补充 `cd_doctor`。后两个创建两慢病随访强类型表和打印留痕快照表及幂等/查询索引。新建库仍只执行 `init.sql`。若现场已存在重复的激活 `generated` 轮次，须先清理重复数据再创建轮次唯一索引。
6. `init.sql` 默认 AI 配置 `CFG001` 使用 DashScope `qwen-audio-3.0-asr-flash-streaming` 作为实时模型；存量库需在管理端修改对应配置，不通过常驻升级脚本覆盖现场模型选择。
## 多节点共享 nonce（显式启用）

`FB_DEPLOYMENT_MODE=standalone` 与 JVM 本地 nonce 不需要执行本节。存量库准备启用 `ai-scale-out` 时，仓库交付定向脚本 `update_shared_nonce.sql`，由现场 DBA 先审核脚本和目标 database/schema，再使用与应用一致的账号直接执行并检查非零退出码：

```bash
gsql -v ON_ERROR_STOP=1 -f update_shared_nonce.sql
```

交付边界固定如下：

1. 脚本只负责幂等创建 `c_ai_request_nonce`、复合主键 `(id_device, nonce_hash)` 和过期索引 `idx_c_ai_request_nonce_exp`；`nonce_hash` 保存 SHA-256 十六进制哈希，`expires_at` 保存 epoch 毫秒数。
2. 对象已经按当前合同存在时重复执行为 no-op；发现同名对象结构、主键或索引定义不一致时必须报错停止，由 DBA 先处理漂移，脚本不得静默改列、重建约束或覆盖现场对象。索引核对使用 openGauss 兼容的 `pg_index.indkey[0]` 元数据查询，已在 `openGauss-lite 5.0.3` 验证。
3. 脚本不包含数据库地址、账号或口令，不执行 `\connect`，应用启动和部署脚本也不会连接数据库代为执行。执行动作、目标 schema、备份和结果确认都由 DBA 负责。
4. 脚本不读取、不迁移、不删除旧 `c_security_request_nonce`，也不包含任何 `DROP TABLE`、`DROP INDEX` 或历史 nonce 数据搬迁。nonce 是短期防重放状态，旧表如需清理由 DBA 在独立变更中另行评估。
5. 执行脚本不会自动切换应用模式。DBA 还必须确认应用账号对新表具备实际查询、插入和删除权限，并在节点入池前让 nonce 深度探针完整通过；深探发现权限或唯一约束漂移时，HTTP/WS 必须持续 `SECURITY-503`，直到后续深探恢复。
