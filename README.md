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
