# PCIE Server 项目 Harness

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
