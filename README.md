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

## 部署阶段

当前生产仍默认单节点，不要求现有客户立即建设多节点：

1. `FB_DEPLOYMENT_MODE=standalone` 是默认模式。同一个 Spring Boot 模块化单体以一个 `pcie-server` 节点独立运行；发布包和语音审计使用显式本机持久目录，nonce 使用单 JVM 内存存储。
2. 单节点可以直连，也可以按 [单节点部署基线](./deploy/standalone/README.md) 增加 Nginx 单入口；应用脱离 Nginx 仍能独立启动和恢复。
3. 当可预期的新机构或用户批次将使 AI、SSE 或实时语音长连接超过单机安全容量时，可显式启用 `FB_DEPLOYMENT_MODE=ai-scale-out`，人工增加同构节点。
4. `ai-scale-out` 是“AI/长连接容量池”，不是全站 active-active：Nginx 的 `pcie_primary` 只指向一个主节点，继续承载管理端、普通业务和所有默认路径；`pcie_long` 使用 `least_conn`，只承载 `/v1/ai/chat`、`/v1/ai/speech/transcribe`、`/v1/ai/speech/realtime` 与 `/v1/ai/speech/realtime/ws`。主节点也可以加入 `pcie_long`。
5. 扩展模式要求每个节点设置唯一 `FB_NODE_ID`，统一使用 `FB_NONCE_STORE=database` 与 `FB_STORAGE_MODE=shared-posix`，并通过相同 `FB_SHARED_STORAGE_ID` 确认发布包和语音审计目录确实来自同一份共享 POSIX 存储。
6. 扩容和缩容都由运维在可预期窗口内手工执行，不引入 Redis、MQ、服务发现、微服务拆分或自动扩缩容平台。

该阶段只扩展四条 AI/语音路径的应用节点容量。普通业务仍固定到主节点，因此不宣称普通业务高可用，也不宣称已经解决 PatientMemory、LIS/PACS、推荐偏好聚合、问诊日志和 EMR 缓存等全站跨节点写竞态；这些问题留到未来确需全站多节点时处理。
