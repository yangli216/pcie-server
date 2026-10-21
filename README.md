# 全医慧助服务端（PCIE Server）

**PCIE — Primary Care Intelligent Expert**

全医慧助服务端是“全医慧助（PCIE）”基层医疗智能全科专家工作站的唯一远程业务后端，负责设备接入、配置引导、AI 与语音代理、知识库代理、审计与统计、客户端版本发布，以及统一托管的管理平台。

## 工程组成

- `server/`：Spring Boot 2.7、Java 8、MyBatis-Plus 服务端
- `server/src/main/admin/`：Vue 2 + Element UI 管理平台
- `API.md`：桌面端 `/v1/*` 与管理端 `/admin/api/*` 契约
- `ARCHITECTURE.md`：运行架构、数据模型与部署约束

## 项目标识

- 正式名称：全医慧助服务端（PCIE Server）
- 英文说明：Primary Care Intelligent Expert Server
- GitHub 仓库：`yangli216/pcie-server`
- Maven 构建产物：`pcie-server-<version>.jar`

为保持现场部署和既有客户端兼容，Java 基础包 `com.regionalai.floatingball.server`、`floating-ball.*` 配置键、`FB_*` 环境变量、数据库结构及现有 `/data/floating-ball-server-*` 部署目录继续保留。上述标识属于运行契约，不再作为新增用户界面或仓库品牌使用。

## 验证

```bash
mvn -f server/pom.xml test
```

该命令会同时安装并构建内嵌管理端。

## 药品说明书数据源

PCIE Server 只读查询外部 MySQL 的 `ipharmacare_knowledge.hzsz`，桌面端在“知识库 → 药品说明书”中检索并阅读完整 `summary`。接口和错误码见 [API.md](./API.md) 的药品说明书章节。

在部署环境注入以下配置，账号口令不要写入仓库或命令行参数：

```bash
export FB_DRUG_INSTRUCTIONS_ENABLED=true
export FB_DRUG_INSTRUCTIONS_JDBC_URL='jdbc:mysql://db-host:3306/ipharmacare_knowledge'
export FB_DRUG_INSTRUCTIONS_USERNAME='read_only_user'
# FB_DRUG_INSTRUCTIONS_PASSWORD 由部署环境/密钥配置注入
```

数据源默认关闭。启动时不连接该外部库，外部库故障仅影响说明书查询；主业务仍使用现有 Oracle/GaussDB 数据源。现场账号只需目标视图 SELECT 权限，不要求底表、EXPLAIN 或写权限。连接池只读，查询绑定参数、限制分页并设置超时。

默认测试不依赖现场网络；在能够访问数据库的环境中，可以显式开启生产 JDBC 查询代码的只读集成测试（需已注入上述 JDBC URL、账号及密码）：

```bash
FB_DRUG_INSTRUCTIONS_INTEGRATION=true mvn -f server/pom.xml -Dtest=DrugInstructionMySqlIntegrationTest test
```

本地无法直连时，允许先建立仅监听回环地址的 SSH 隧道，再将 JDBC URL 临时设置为 `jdbc:mysql://127.0.0.1:13306/ipharmacare_knowledge`。联调完成后关闭隧道，正式服务配置使用其实际能访问的数据库地址。萧山发布仍须使用 `scripts/publish-xiaoshan.sh`，本功能代码交付不等于现场已发布或启用。

2026-10-14 验证记录：通过萧山 SSH 隧道对现场 MySQL 5.7.44 执行生产 JDBC 代码的只读集成测试，药名/拼音检索、ID 去重、说明书章节和零命中通过；包括该测试的 Maven 全量 496 项全部通过。接口 MockMvc 使用真实 ECDSA 验签覆盖未鉴权/未签名/请求篡改、参数边界、完整长文、404 和 503。桌面端全量 1146 项单测、类型检查和构建通过，阅读布局调整后的知识库 41 项回归及构建再次通过；浏览器使用明确标记的合成资料检查 378px 窄窗、720px 宽窗、深浅色、原文阅读和章节跳转。真实 Tauri 安装包端到端验收尚未执行，现场发布结果见下文。

## 萧山测试环境发布验证

2026-10-21 已更新 9090 测试环境并在独立 `RBMH_AI_TEST` schema 创建住院病历日志两表。全新构建的 536 项测试无失败（1 项原有跳过），发布脚本 13 项回归通过，readiness、管理端资源及正式环境不变检查通过；详见 [本次发布记录](docs/xiaoshan-testing-deploy-20261021.md)。以下为此前药品说明书发布验证记录。

已通过 `scripts/publish-xiaoshan.sh testing` 将 `0.2.0-SNAPSHOT` 发布至 9090，药品 MySQL 配置在测试环境 `service.env` 中启用。验证结果：

- Maven `clean package`（含管理端安装与构建）成功；496 项测试中 495 项通过、1 项现场 MySQL 集成测试按默认配置跳过；部署脚本 13 项回归全部通过。
- 外部 readiness 返回 HTTP 200 / `UP`；管理端入口及其引用的 4 个静态资源返回 200；未鉴权访问药品检索接口返回 401。
- 在萧山服务器使用已部署 JAR 的实际查询代码和运行进程中的 MySQL 配置执行只读查询：药品检索返回 3 条，详情 `summary` 为 4358 字符，耗时约 1.2 秒。此验证覆盖数据库连接和查询，未替代真实桌面端签名请求的端到端验收。
- 8080 正式环境 PID、JAR 摘要及启动脚本摘要均保持不变；测试环境上一版 JAR 和启动脚本已备份，发布锁正常释放。

## 服务管理与本地运行

萧山测试环境发布：`./scripts/publish-xiaoshan.sh testing`，固定使用 `192.168.204.122:9090` 和 `/data/floating-ball-server-testing`。脚本执行全新 Maven 构建、管理端资源校验、环境独立锁、上传校验及就绪检查；切换失败自动回滚。正式环境需显式指定 `production --confirm-production`。

在目标部署目录维护权限 `600`、归运行用户所有的 `service.env`，使用受信任的 Bash 赋值语法保存 `FB_*` 私有配置。启动脚本会自动加载并导出，重新发布保留此文件；文件不得为符号链接，也不能覆盖固定目录、profile 和端口。药品说明书的四项 `FB_DRUG_INSTRUCTIONS_*` 配置可放在测试环境的该文件中。配置变更前另行备份，JAR 回滚不会回退此文件。脚本回归：`python3 scripts/test_publish_xiaoshan.py`。

项目提供统一管理脚本 [manage-server.sh](file:///Users/yangl/IdeaProjects/floating-ball-server/scripts/manage-server.sh)：

```bash
# 1. 快速构建前端并启动本地开发服务（前台运行，实时看日志，Ctrl+C 停止）
./scripts/manage-server.sh dev --env development

# 2. 构建前端并启动后台守护服务（默认 test 环境，8080 端口）
./scripts/manage-server.sh start -b --env test

# 3. 单独构建前端管理端（毫秒级 Vite 构建）
./scripts/manage-server.sh build frontend

# 4. 启动前端 Vite 热更开发服务器（独立开发页面）
./scripts/manage-server.sh dev-frontend

# 5. 查看运行状态 / 查看日志 / 停止服务
./scripts/manage-server.sh status --env test
./scripts/manage-server.sh logs --env test
./scripts/manage-server.sh stop --env test
```
