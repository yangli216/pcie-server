# WeKnora 与全医慧助知识检索接入

本目录交付接入配置和候选发布清单；不包含第三方服务代码或真实凭据。双端设计见 [知识服务设计](../../../development-harness/KNOWLEDGE_BASE_DESIGN.md)，正式 API 见 [API.md](../../API.md)。

随部署交付的机器契约为 [knowledge-retrieval.openapi.json](knowledge-retrieval.openapi.json)，与工作区 `development-harness/contracts/knowledge-retrieval.openapi.json` 逐字节同步。独立克隆服务端仓库也可读取该契约；跨端设计与字段变更仍先维护工作区版本。

## 本机环境

2026-10-08 用户另授权公开资料实际测试。公开资料存入私有 `.local/public-sources/`，保留官方发布页、原始 PDF、文号/版次、文件 SHA-256、选用页码和逐页抽取记录；不将 LLM 总结作为原文。建立独立「公开资料测试」知识库，关闭摘要和 AI 问题生成，导出全部启用文本分块后逐项核对。测试清单只授予本机测试机构检索权限，APPROVED 仅表示出处/原文/哈希的检索工程核验；医学适用性另记待审核，公卫生成和 HIS 写回仍不使用本集合。

`compose.local.yaml` 采用官方 v0.8.2 的默认五个服务：WeKnora UI/App、文档解析器、ParadeDB 和 Redis。仅将 UI `127.0.0.1:8091` 和 App `127.0.0.1:8092` 发布到本机；数据库、Redis 和解析器只在容器网络中可见。数据卷属于独立项目 `pcie-weknora-local`，不复用 PCIE Server 数据库。可选 Agent 沙箱、联网搜索、Neo4j、Langfuse 和本地模型下载默认不启用。

从 `pcie-server` 目录执行：

```sh
python3 deploy/weknora/prepare-local.py
docker compose --project-name pcie-weknora-local --env-file deploy/weknora/.local/runtime.env -f deploy/weknora/compose.local.yaml config --quiet
docker compose --project-name pcie-weknora-local --env-file deploy/weknora/.local/runtime.env -f deploy/weknora/compose.local.yaml up -d --no-build
docker compose --project-name pcie-weknora-local --env-file deploy/weknora/.local/runtime.env -f deploy/weknora/compose.local.yaml ps
```

生成工具只在第一次写入 `.local/runtime.env`，权限为 0600；重复执行保留已有密钥和端口。该文件使用独立随机数据库/Redis 密码、JWT 和 32 字节 AES 密钥，默认时区 Asia/Shanghai、语言 zh-CN、日志级别 info。`.local/` 不进入 Git。镜像统一固定为 Docker Hub 官方实际发布的 `v0.8.2` 标签（带 v），基础镜像使用上游 Compose 的版本。配置采用镜像内置配置，不挂载未经维护的配置副本。

访问 `http://127.0.0.1:8091/` 创建本机账户并配置嵌入模型。服务健康检查通过只说明基础设施可用；模型及知识库配置完成前不能验证真实向量召回。PCIE 的机构清单、retrieve-only Key 和批准发布仍按下述步骤完成，生成本机环境不会自动开启当前服务端知识检索。

2026-09-30 本机启动已验证：五个服务运行；App、Docreader、ParadeDB 和 Redis 为 healthy；管理页实际显示中文登录页面，App 的 `/health` 返回 HTTP 200 / `status=ok`。默认端口的模型与资料尚未配置，现有 PCIE Server 未被重启或改写。详细无凭据运行记录保存在 `.local/runtime-verification.json`。

本机实际接入使用 `.local/pcie-connection.properties` 与 `.local/pcie-catalog.json` 保存服务端连接和测试集合清单。已有 IDE 运行实例须先核对 profile 与配置来源；通过 Spring 的 `spring.config.additional-location` 加载私有连接文件，并保留既有数据库/AES/AI 配置。用于工程验证的资料须标为合成资料且不含患者或医学决策内容；只有实际索引、片段导出和正文哈希校验完成后，才可启用该测试集合。真实医学资料仍按下述批准流程处理。

如果现有 profile 开启管理员启动密码重置，本次接入的私有配置必须覆盖 `floating-ball.admin.bootstrap-reset.enabled=false`，再重启已有实例，避免知识连接配置引发账号密码重置。IDE 使用环境变量 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/绝对路径/.local/pcie-connection.properties`；该文件不得加入 Git。工程说明导入时关闭文档摘要和 AI 问题生成，批准清单只绑定实际解析出的原文分块。

本机嵌入服务若使用 Ollama，安装官方固定版本并核对发布文件 SHA-256，模型目录放在 `.local/ollama-models`；服务仅监听环回地址，WeKnora 通过 `host.docker.internal` 访问。模型实际版本、维度、索引与召回结果分别写入无密钥的本机验收记录。Ollama 不是 PCIE Server 默认启动依赖；没有配置模型时不声称召回已完成。

同机 Ollama 接入需要在私有 `.local/runtime.env` 中显式设置 `SSRF_WHITELIST_EXTRA=host.docker.internal`，再使用上述 Compose `up -d --no-build app` 重建 App 容器。该变量默认不配置，只扩展 WeKnora 的出站主机白名单；本机部署仅加入这个准确主机名，禁止填写通配域名或整个私网网段。UI 的模型测试通过后才保存模型配置；本机 OpenAI 兼容 Embedding Base URL 使用 `http://host.docker.internal:11434/v1`，API Key 留空。

2026-10-08 本机嵌入准备已验证：官方 Ollama v0.40.1 发布包通过 SHA-256 校验，`bge-m3:latest` 已安装（模型摘要 `7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`）。宿主机 OpenAI 兼容 `/v1/embeddings` 实际返回两条 1024 维向量，WeKnora App 容器通过 `host.docker.internal:11434` 调用同一接口实际返回一条 1024 维向量。无密钥记录在 `.local/embedding-verification.json`；本机重复启动脚本为 `.local/start-ollama.sh`，使用本机现有代理下载模型，不改全局网络配置。

同日登录后已在 WeKnora 登记本机 `bge-m3:latest`（1024 维）和 `qwen2.5:0.5b`（仅作工程摘要配置），两者 UI 连接测试均成功。已创建「PCIE 知识工程验收（合成资料）」并导入非医学工程说明，解析状态 `completed`、分块索引 `ready`；摘要及 AI 问题生成关闭。实际得到 1 个启用的 `text` 分块，批准清单使用其原始 UTF-8 正文 SHA-256，已核对解析内容与源文件文字一致。IDE 原有运行配置已增加私有文件的 additional-location。

初次准备阶段知识开关关闭，设备签名集合接口曾返回 HTTP 200 / `enabled=false`。2026-10-10 已接入用户创建的 retrieve-only Key，仅允许合成工程验收和公开资料测试两个 KB；凭据保存在服务端私有 0600 文件中。已有 IDE Oracle 实例加载私有连接配置后，实际集合接口返回 `enabled=true` 和两个本机测试集合，真实合成资料检索返回 `provider=weknora`。`.local/pcie-signed-verification.json` 保留 12 项真实签名/机构/批准/版本/哈希边界检查，全部通过；临时拒绝场景结束后已恢复原批准清单。

2026-10-08 公开资料实际测试准备：从卫健委原始 PDF 获取《国家基本公共卫生服务规范（第三版）》（国卫基层发〔2017〕13号），与绩溪县政府的同名附件逐字节核对一致，SHA-256 为 `5e61460188040be01605e8294561f8d82f29c09720dca40ff8c1eab7703431d8`。选取老年人、高血压和 2 型糖尿病健康管理章节的叙述原文，排除流程图和空表；原始 PDF、抽取页码/字符区间和三份导入文件均在 `.local/public-sources/`。独立「PCIE 公卫规范（公开资料测试）」的 3 份文档已全部解析完成，共 14 个启用 `text` 分块且索引 `ready`，摘要和 AI 问题生成关闭。已逐块验证原文对应、选用正文完整覆盖，并按未裁剪的实际分块 UTF-8 字节计算批准哈希。`provenance.json` 记录出处与医学适用性待审核，`index-verification.json` 记录工程核验；原文保留 2017 第三版标识，不作为最新诊疗指南。

公开资料真实召回预先固定 12 个问题及原文预期来源/短语，位于 `.local/public-sources/retrieval-cases.json`。2026-10-10 通过 `node deploy/weknora/.local/verify-public-sources.mjs` 完成实际设备签名请求，12 项全部通过；预期来源在首位的为 12/12，完整预期短语在首片段的为 7/12，其余在前 5 个片段内，所有返回片段的批准哈希与文档版本均匹配。逐条请求、响应、延迟与名次保存在 `.local/public-sources/pcie-retrieval-verification.json`；该结果衡量本次公开原文召回，不代表医学正确性评测或与原知识库的召回对照。

同日 PCIE 1.4.7 本机包使用 `yarn tauri build --debug --bundles app --config '{"bundle":{"createUpdaterArtifacts":false}}'` 构建成功，类型检查、前端构建和 Rust 编译均通过；该覆盖只取消本次本机包的更新分发产物，不修改正式发布配置或读取发布私钥。2026-10-10 已在本项目生成的 `.app`（`tauri://localhost`）中打开「知识库 → 机构资料」，选择「公卫规范·2017第三版（公开资料测试）」并实际查询高血压年度面对面随访，页面返回 5 条原文、发布版本、文档来源/版本和展开的片段校验详情。原生 UI 文字记录在 `.local/public-sources/pcie-native-retrieval-ui.txt`。PCIE Server 和 WeKnora 健康检查均通过；并行公卫 Mock 仍占用 8081，本次主包验收仅证明知识检索页面和远端签名调用，不以现有 Mock 桥接的健康响应证明主包 HIS/PHIS 写回。

实际联调发现 WeKnora v0.8.2 的 `match_type` 为数字枚举，适配器已按上游 0–9 枚举归一为既有字符串契约，并保留旧文本标识兼容。未知枚举、非整数、缺失和其他非法类型仍按脱敏的上游失败处理。知识服务与设备签名定向回归 37 项通过；完整 `mvn -f server/pom.xml test` 执行 617 项，616 项通过，既有 `AdminLoginNavigationTest.loginShouldResolveRoleRedirectBeforeReplacingCurrentRoute` 失败。记录分别在 `.local/maven-knowledge-regression.log` 与 `.local/maven-full-regression.log`，不将完整构建报告为通过。

2026-10-10 提交前在仅包含本次知识接入的独立快照中复验：客户端基于同步后的 1.4.8 主线，`yarn type-check`、`yarn test:unit`（187 个文件、1134 项测试）与 `yarn build` 均通过；服务端 `mvn -f server/pom.xml test`（104 个测试类、515 项测试）和本机准备工具的 2 项 unittest 均通过。随部署交付的 OpenAPI 与工作区契约逐字节一致。上述 617 项记录来自混合工作区；既有暂存登录测试、公卫及 Benchmark 改动不属于本次提交范围。真实资料、运行配置和凭据继续仅保存在私有 `.local/` 中。

停止本项目时将上述 `up -d --no-build` 替换为 `stop`；再次使用 `up -d --no-build` 恢复。不要使用 `down -v`、全局 prune 或删除 `.local/runtime.env`；这些操作会丢失数据或密钥。首次拉取前检查磁盘容量与官方镜像体积，安装状态以容器健康及浏览器观察记录为准。

准备工具的权限、幂等和已有配置保护验证：`python3 -m unittest discover -s deploy/weknora -p 'test_*.py'`。Compose 用 `config --quiet` 验证结构，不将展开的密钥配置输出到日志。官方来源：[v0.8.2 Compose](https://github.com/Tencent/WeKnora/blob/v0.8.2/docker-compose.yml)、[App Dockerfile](https://github.com/Tencent/WeKnora/blob/v0.8.2/docker/Dockerfile.app) 与 Docker Hub 的 [App](https://hub.docker.com/r/wechatopenai/weknora-app/tags)、[UI](https://hub.docker.com/r/wechatopenai/weknora-ui/tags)、[Docreader](https://hub.docker.com/r/wechatopenai/weknora-docreader/tags) 发布标签。

1. 在独立目录获取官方 [WeKnora v0.8.2](https://github.com/Tencent/WeKnora/tree/v0.8.2)，按其 .env.example 配置并设置 WEKNORA_VERSION=v0.8.2；使用该标签的 docker-compose.yml 启动默认服务。知识检索无需启用 Agent 沙箱、联网搜索或 Neo4j。为 PostgreSQL、Redis、本地原文卷分别配置持久存储和备份。
2. 在 WeKnora 空间创建 document 类型知识库，配置嵌入模型及混合索引，上传经过授权的资料并核对解析后的原文。旧 Bsoft 的 token 分块大小不能直接复制到字符分块配置；原始文件/分段归档与新索引分别保留。
3. 创建仅 retrieve 能力、仅允许对应 KB 的 API Key。PCIE Server 配置 FB_WEKNORA_BASE_URL 和 FB_WEKNORA_API_KEY；本机同机运行的 Server 使用 `FB_WEKNORA_BASE_URL=http://127.0.0.1:8092`，不使用8091的管理端地址。客户端不配置供应商地址或密钥。多个空间配置独立 connectionId 与 Key。
4. 复制 catalog.example.json 为部署目录的新清单。填写真实机构 ID（设备绑定的 idOrg）、KB ID、文档 ID、来源/版本，以及 GET /api/v1/chunks/:knowledge_id?page=N&page_size=100 的全部启用分块 ID 和正文 SHA-256。分页直到 total 完整覆盖；使用正文 UTF-8 字节计算 hash。DRAFT 保持不可检索，内容审核通过后再显式改为 APPROVED，并冻结 releaseVersion 与 retrievalVersion。
5. 将清单置于服务端可读的持久目录，以原子替换发布；设置 FB_KNOWLEDGE_CATALOG_FILE 的绝对路径，按需要把 WeKnora host 加到现有出站允许名单，然后设置 FB_KNOWLEDGE_RETRIEVAL_ENABLED=true，重启 PCIE Server。
6. 医生在知识库工作台选择机构资料，发起检索；检索必须仅命中该机构批准资料，显示来源和版本。用测试机构验证未签名、其他机构、未批准资料和后台内容修改均被拒绝。上游停止时页面显示失败而不是无结果，不能自动转用模型生成。

示例清单中的 SYNTHETIC 字样与 DRAFT 状态是候选样例，不能用于正式医学批准。可在受控环境用合成资料验证工程接入；食谱与指南的内容审核、旧库召回对照、真实模型预算和现场上线独立执行。切换索引模型/引擎必须重建并发布新版本；修改已经批准的分块会被 PCIE 的内容哈希校验阻断。
