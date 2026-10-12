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

随访健康处方按[公卫设计](deploy/public-health/PUBLIC_HEALTH_DESIGN.md)接入发起方事实画像、六分区教育草稿、定性潜在风险证据及检查 eligibility。单元测试用 Fake 模型/批准合成规则，运行 `mvn -f server/pom.xml '-Dtest=PublicHealth*Test,WeightMetricsCalculatorTest' test` 验证相关组；报告真实模型工程测试、正式规则批准和 PHIS 保存为独立验收。减重与随访保持独立结果/确认范围。

知识接口交付包含 `deploy/weknora/knowledge-retrieval.openapi.json`，提交前与工作区 `development-harness/contracts/knowledge-retrieval.openapi.json` 比较原始字节；它是部署契约副本，跨端字段仍先在工作区维护。

## 公卫知识索引工具

`PublicHealthAssetTool build --input <候选JSON> --output <新目录>`构建真实分段索引；`retrieve --recipes <索引JSON> --queries <查询JSON> --output <新目录>`复用生产召回链记录技术验收。独立工具以`FB_PH_INDEX_BASE_URL`和`FB_PH_INDEX_API_KEY`提供模型连接，不启动数据库，不写线上配置，不创建APPROVED manifest。新增工具与批量解析执行对应JUnit及Maven检查；真实模型调用单独记录，默认测试保持Fake。

入口全名为`com.regionalai.floatingball.server.modules.publichealth.tool.PublicHealthAssetTool`，使用打包后的Spring Boot loader运行（不是启动主服务）。从本仓库目录执行：

```sh
mvn -f server/pom.xml package
java -Dloader.main=com.regionalai.floatingball.server.modules.publichealth.tool.PublicHealthAssetTool -cp server/target/pcie-server-0.2.0-SNAPSHOT.jar org.springframework.boot.loader.PropertiesLauncher --help
```

JAR版本取`server/pom.xml`，不要依赖过期产物。把`--help`替换为`build --input <候选JSON> --output <新目录>`或`retrieve --recipes <索引目录>/recipes.json --queries <查询JSON> --output <新目录>`。`--batch-size`默认16，范围1–64；`--candidates`默认20，范围4–100。查询文件为`[{"id":"唯一标识","query":"地区/季节/合成BMR查询"}]`，1–60项；每条两次模型调用，保持在现有120次/分钟出站限额内。

`FB_PH_INDEX_BASE_URL`须为实际支持`/embeddings`和`/rerank`的根地址（必要时含`/v1`），两项环境变量均无默认值；密钥不能作为CLI参数。工具只访问该显式host，连接超时10秒、读取超时60秒、禁用重定向，无自动重试；不修改常驻服务的出站配置。成功退出0，失败退出1且不发布完成目录；详细结果在`build-report.json`或`retrieval-report.json`，均不含密钥。`recipes.json`是技术索引，生产仍需绑定机构和批准manifest。
