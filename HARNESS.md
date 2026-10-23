# PCIE Server 项目 Harness

## 南大通用 PostgreSQL

本机功能联调按“readiness/登录 -> 合成 CRUD 与签名 -> JSON/统计与导出 -> 浏览器展示 -> 合成对象停用及剩余审计清单”执行；详细结果见 `deploy/gbase-postgresql/README.md`。逻辑删除回归包括真实全局配置下生成的 Mapper SQL，用户角色替换和设备封禁/重置另有真实 API 复测。Fake 上游只验证工程链路，真实 AI/ASR/BBP/知识库及 PHIS 不借此记为通过。

构建输出位于 `server/target/gbase-postgresql/`，以该目录测试报告和 JAR 为准；检查包内厂商 JDBC SHA-256、无 openGauss/PostgreSQL JDBC 混包及管理端入口/资源完整。

当前安装用户 5.0.0 JAR 时使用 `cn.gbase:gbase-jdbc:5.0.0` 的本机别名，保留原件摘要及包内 `org.opengauss:opengauss-jdbc:5.0.3` 元数据差异；不覆写官方 openGauss 缓存。实库已确认 `GBase8sV8.8 S5.0.0B77`、兼容模式 A，后续隔离 schema 验证须保留该模式，不自动切换为 PG 或执行既有 schema 初始化。

先核对用户提供 JAR 的 SHA-256、SPI 驱动类、JDBC URL 与测试库 `version()`/元数据，再使用 `gbase-postgresql` Maven/Spring profile。默认 `mvn -f server/pom.xml test` 验证方言、分页、配置/schema 与诊断 JSON 统计，不需要厂商介质或网络；显式 JDBC 验证见 `deploy/gbase-postgresql/README.md`。真实初始化只对明确隔离的空 schema 执行，不重建或迁移用户测试库现有表。厂商驱动和 openGauss 驱动分别构建与验证，不以普通 PostgreSQL/openGauss 成功代替南大通用产品验收。

## WeKnora 知识检索

先维护跨仓知识设计与机器契约，再实现批准清单/Provider/签名API及桌面消费。执行KnowledgeCatalogServiceTest、WeKnoraKnowledgeProviderTest、KnowledgeRetrievalIntegrationTest及默认Maven测试；不跳过管理端构建。部署步骤与DRAFT清单位于deploy/weknora；资料批准、原库对照和真实调用独立验收，禁止以Fake测试通过启用正式生成。

供应商协议回归以 v0.8.2 的整数 `match_type` 为默认 Fake 报文，覆盖官方枚举转字符串、旧文本标识兼容和非法类型拒绝，再重跑实际签名检索与桌面来源展示；不把 Fake 兼容等同于实际召回。

本机 WeKnora 使用 `deploy/weknora/compose.local.yaml` 与 `.local/runtime.env`，项目名固定 `pcie-weknora-local`。配置生成、校验、启动和停止命令见部署 README；不使用全局清理命令，不停止其他项目或绑定既有服务端端口。

真实本机接入使用该目录 `.local/pcie-connection.properties` 与 `.local/pcie-catalog.json`，通过 Spring additional-location 加载；先核对 IDE 实例的 profile 和连接来源。仅以明确标注的非医学合成资料验收实际索引、片段哈希、设备签名及页面原文。运行模型与登录未完成时保留准备结果；默认单测仍用 Fake，上游凭据不进入日志或桌面端。


继承工作区与本项目 AGENTS.md；架构和接口以 ARCHITECTURE.md、API.md 为准。跨仓协作遵循 ../development-harness/HARNESS.md。

## 执行顺序

1. 检查工作树并保留现有修改；阅读涉及模块的规则、架构、真实调用及字段契约。
2. 先更新文档，再实现接口、服务和对应测试；涉及数据库则同步各方言初始化基线。
3. 默认执行 `mvn -f server/pom.xml test`，包含管理端安装/构建和 Java 测试；阻塞时区分环境、既有失败和本次变更，报告替代验证及未完成项。
4. 按“契约 -> 实现 -> 验证 -> 未完成的部署/联调”交付；不自动发布或修改生产配置。

知识接口交付包含 `deploy/weknora/knowledge-retrieval.openapi.json`，提交前与工作区 `development-harness/contracts/knowledge-retrieval.openapi.json` 比较原始字节；它是部署契约副本，跨端字段仍先在工作区维护。

## 分段 ASR

新增语音模式/时长保留在既有 JSON 配置中，DTO、列表与 bootstrap 分别验证；后台回归仍执行标准 Maven package，不连接真实库。管理端保留 Vue 2 Options API，将语音模式字段拆为 SpeechTranscriptionFields；真实保存、合成组件预览与麦克风/模型验证分别留证，配置值预览不算实库保存。生产配置组件的临时预览位于私有部署目录，不作为后台鉴权替代入口。
