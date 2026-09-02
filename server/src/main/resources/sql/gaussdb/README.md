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

`init.sql` 是 GaussDB 业务 schema 的初始化基线，结构与 Oracle `sql/oracle/init.sql` 对齐，包含业务表、索引、注释和默认种子数据。当前基线也包含 `c_ai_chronic_followup` 原 `TcdVisitForm` 两慢病融合随访表：`id_phr/id_record/sd_visit_kind` 独立检索，`form_data_json` 无损保存强类型 DTO，`request_id` 来自 `X-Request-Id`；旧通用列暂留作未发布实验表兼容，不再作为业务请求结构。`c_ai_chronic_artifact` 固化健康处方与年度评估打印前的患者证据、版本和医生确认快照，两表都以平台机构 + `request_id` 保证激活记录幂等。`c_ai_user_ai_permission` 由 PCIE 保存机构人员 AI 使用权限，BBP/PHIS 只提供人员身份，PHIS 通过独立服务接口判权；`c_ai_bbp_admin_grant` 则以租户、机构、BBP 稳定用户 ID 和角色编码保存 PCIE 后台 `ORG_ADMIN` / `ORG_ANALYST` 访问授权，两类权限相互独立。同时包含第三方 ODS 检验检查申请单 `hi_ods_apply`、检验常规报告 `hi_ods_apply_lis_report` 与检查报告 `hi_ods_apply_pacs_report`，用于管理端手工模拟第三方结果回写；未提供结构的 `hi_ods_lis_result` 不作为常驻工程资产创建。实时语音 WebSocket 上游独立保存在 `speech_realtime_url`，自建 FunASR 使用 `speech_provider=funasr-websocket`。问诊日志唯一索引只约束尚未结束的 `generated` 记录，同一就诊回写或放弃后再次问诊会保留为新的日志轮次；`c_ai_user_consultation_log.id_his_org` 单独记录桌面端从 HIS 握手上报的机构 ID，不覆盖后台机构 `id_org`。

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
2. 激活记录唯一性使用表达式唯一索引实现，语义与 Oracle 基线一致；问诊日志只对激活且尚未结束的 `generated` 轮次做唯一约束。门诊模板快照只接收正式 HIS Bridge/SDK 分析链路中同一模板的渲染 HTML 与结构定义 JSON，按机构、模板 ID 与模板对 hash 标识版本，只保存两份原文和确定性合并解析结果，不保存患者、病历上下文或生成值；`c_ai_outpatient_emr_tpl_mapping` 以模板快照和字段 ID 保存独立人工覆盖，不修改不可变快照。
3. 现场旧库不能重建时，由 DBA 基于当前 `init.sql` 与现场结构生成一次性迁移脚本；客户端使用情况上线前需确认 `c_ai_feature_event.cd_doctor`、`client_version` 与 `idx_c_ai_feature_event_usage` 已补齐。历史空值不得猜测回填。
4. 若需要普通 PostgreSQL 运行，优先复用本目录结构作为 PG 兼容基线，再结合现场版本验证 JSON、表达式索引和时间函数兼容性。
5. 存量库使用当前应用账号执行 `gsql -v ON_ERROR_STOP=1 -f update_his_org_statistics.sql`。该脚本补齐客户端使用情况字段和索引，清空历史功能事件的临床关联与 payload，并按稳定 UUID 重建幂等键；执行前必须备份、停止写入、核查外部报表依赖并由 DBA 在维护窗口审核。后续慢病脚本职责保持不变。
6. `init.sql` 默认 AI 配置 `CFG001` 使用 DashScope `qwen-audio-3.0-asr-flash-streaming` 作为实时模型；存量库需在管理端修改对应配置，不通过常驻升级脚本覆盖现场模型选择。
7. 服务启动与 Actuator `featureEventSchema` readiness 会只读验证功能事件完整列和 `feature_event_minimization_v1` 标记；缺表、缺列、缺标记或权限不足时拒绝启动。使用情况索引仍须由 DBA 单独确认。
8. `init.sql` 默认角色包含 `SYSTEM_ADMIN`、仅管理所属 BBP 机构 AI 使用权限的 `ORG_ADMIN`，以及只读查看本机构统计的 `ORG_ANALYST`；新增 BBP 用户授权通过 `c_ai_bbp_admin_grant` 维护，既有本地 PCIE 用户角色映射继续作为兼容路径。
9. 已存在门诊模板快照表的库执行 `gsql -v ON_ERROR_STOP=1 -f update_outpatient_emr_template_snapshot.sql`，把唯一键调整为 `id_org + template_id + template_hash`；脚本仅重建索引，不改变任何业务记录，执行期间须停止模板快照写入。
