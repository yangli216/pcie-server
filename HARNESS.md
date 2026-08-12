# 全医慧助（PCIE）服务端执行 Harness

> 适用范围：`pcie-server` 的服务端、管理端、数据库和部署改造。
> 根目录与本项目 `AGENTS.md` 都是硬约束；本文件只负责任务路由和验证收口。

## 1. 执行顺序

1. 阅读 `AGENTS.md`、`ARCHITECTURE.md`、`API.md` 与工作区 `TESTING_STRATEGY.md`。
2. 检查当前分支和未提交改动，保留用户已有文件，不执行清理、暂存、提交或推送。
3. 架构、接口、数据模型、部署行为变化先更新文档，再修改生产代码。
4. 新增生产代码同步新增或更新 JUnit；最后按“文档一致性 -> 定向测试 -> 全量测试/构建”收口。

## 2. 影响面路由

| 变化 | 必查内容 |
| --- | --- |
| `/v1/*` 请求、响应或错误语义 | `API.md`、桌面端真实调用、签名过滤器与契约测试 |
| AI/语音/知识转发 | 出站安全、连接池、执行器、超时、取消、审计与拥塞测试 |
| 单节点部署 | Nginx 单 upstream、本机持久目录、管理 token/AES、健康探针、优雅下线与受控绕行 |
| AI/长连接容量池 | `FB_DEPLOYMENT_MODE` 强校验、`pcie_primary/pcie_long` 路由白名单、数据库 nonce、共享 POSIX 发布/语音目录、节点身份、人工加摘和长连接恢复 |
| 数据库表或索引 | Oracle/GaussDB 初始化基线、对应 README、schema 测试；DM8 按项目规则交付定向兼容说明 |
| 管理端 | 同源 API、Vue 2 构建、可访问性和既有公共组件 |

## 3. 两种生产模式

`FB_DEPLOYMENT_MODE=standalone` 是默认正式交付。一个 `pcie-server` 必须能够直连启动并完成全部核心业务；发布包和语音审计使用显式本机持久目录，`FB_NONCE_STORE=memory`，Nginx 为可选单入口。

`FB_DEPLOYMENT_MODE=ai-scale-out` 只在可预期的 AI/长连接峰值超过单机安全容量时启用。它必须同时配置每节点唯一 `FB_NODE_ID`、`FB_NONCE_STORE=database`、`FB_STORAGE_MODE=shared-posix` 和各节点一致的 `FB_SHARED_STORAGE_ID`；任一条件不满足时节点不得启动或入池。Nginx 路由合同固定为：

- `pcie_primary` 只有一个主节点，承载 `/admin/**`、`/admin/api/**`、普通业务及所有未显式列入容量池的默认路径；
- `pcie_long` 使用 `least_conn`，只承载 `/v1/ai/chat`、`/v1/ai/speech/transcribe`、`/v1/ai/speech/realtime` 与 `/v1/ai/speech/realtime/ws`；
- 主节点可以加入 `pcie_long`，新增节点只承接加入后的新请求和新 SSE/WebSocket，既有长连接不迁移；
- 所有签名路径关闭 Nginx 自动重试；失败后的客户端重试必须生成新的时间戳、nonce 和签名。

两种模式共同至少满足：

1. 未签名、过期、正文不一致和重复 nonce 请求均被稳定拒绝，HTTP 与实时语音 WebSocket 不绕过签名链路。
2. 发布状态与更新策略从同一原子状态派生；`standalone` 从显式本机目录完成重启恢复，`ai-scale-out` 的所有节点从同一共享 POSIX 发布/语音目录完成交叉读写，且存储标识不一致或共享挂载缺失时拒绝入池。
3. `/actuator/health/liveness` 与 `/actuator/health/readiness` 只从受控管理网络访问；readiness 验证数据库和必要业务 schema，不把外部模型云故障错误解释为本机进程失活。
4. AI、语音、知识和 SSE 使用有界执行器、短队列和显式连接池；达到上限时快速、可解释地拒绝，不能进入分钟级长队列。
5. SSE 断开、超时、队列拒绝和 WebSocket 建连失败均释放本地线程、任务、输入流和连接池资源。
6. 客户端公共地址及发布下载地址不得指向临时地址或 `localhost`；容量池节点的业务端口只向 Nginx 和受控运维网开放。
7. 容量池不分发普通业务，因此不得用 AI 路径验证结果宣称普通业务高可用或全站 active-active。

## 4. 容量核算与手工扩缩

每次新增机构或部署批次都先记录预估并与单节点真实混合压测结果比较，至少包含：

- 同时在线终端、同时进入 AI 阶段的医生数和每个操作的实际扇出；
- 峰值阻塞请求、SSE 连接和 WebSocket 会话，以及客户端真实重试时序带来的重叠放大；
- 每类请求的 p95/p99 驻留时间，以及执行器、HTTP/数据库连接池、CPU、内存、FD 和网络出口峰值；
- 模型、语音、知识上游的并发、QPS/TPM 与限流配额。

预测峰值未超过单节点实测安全容量时继续使用 `standalone`。超过时先核对数据库总连接、共享 POSIX I/O、Nginx FD、网络出口以及模型/语音账号配额，再人工增加 `pcie_long` 节点；节点数来自当批真实混合压测，不保留固定节点数或历史终端数量合同，增加应用节点也不代表共享上游容量线性增加。

人工扩容顺序固定为：节点不入池启动 -> 校验版本/配置/节点 ID/共享存储 ID -> liveness/readiness -> 直连四条容量池路径 -> 跨节点重复 nonce 只成功一次 -> 共享发布/语音文件交叉读取 -> 低风险加入 `pcie_long` -> `nginx -t` 与 reload -> 观察 active/queue/rejected、DB 连接和上游配额。失败时先从 `pcie_long` 移除候选节点并 reload，再停止节点，不回滚共享数据库数据。

人工缩容顺序固定为：确认剩余容量 -> 从 `pcie_long` 移除目标节点并 reload -> 确认没有新请求进入 -> traffic drain -> 等待 `GET /actuator/traffic` 的统一 `inFlight`（覆盖 blocking、SSE、WebSocket、queued）归零且 `drained=true` -> 停止节点。只有 readiness DOWN 或某一类连接为零不能作为正常停机依据；超时强停必须记录为故障处置。Nginx 只决定新连接去向，不能迁移已建立的 SSE/WebSocket。

普通业务继续固定主节点，现阶段不要求修复 PatientMemory、LIS/PACS、推荐偏好聚合、问诊日志和 EMR 缓存的跨节点竞态。若未来要让 `pcie_primary` 包含多个节点，必须另立全站多节点任务并先修复这些数据库并发语义。

## 5. 验证命令

```bash
mvn -f server/pom.xml test
mvn -f server/pom.xml package
npm --prefix server/src/main/admin run build
```

除 JUnit 外，`standalone` 必须按 `deploy/standalone/README.md` 保留直连与单 upstream 验证；`ai-scale-out` 还必须保留以下环境验证：

1. 路由证明：四条 canonical 白名单路径能够分布到 `pcie_long`，管理端、注册、bootstrap、发布、慢病及其他普通路径始终只到 `pcie_primary`；其余 `/v1/ai` 路径及尾斜杠、分号、重复斜杠或额外子路径变体均被入口拒绝，WebSocket canonical/变体日志不含 query。
2. 同一设备同一有效 nonce 并发发往两个节点时只允许一次；数据库 nonce 不可用时安全失败，不得回退内存存储。
3. 节点 A 写入的语音文件可由主节点读取，主节点发布的状态与安装包可由全部容量节点读取；挂载缺失或 `FB_SHARED_STORAGE_ID` 不一致时节点不能入池。
4. 按本批次预测峰值发起真实扇出，并使用桌面端当前重试时序验证阻塞请求、SSE、WebSocket、数据库连接和上游配额。
5. 覆盖代表性上游延迟、明确超时、拥塞拒绝、加节点和摘节点，区分“节点保持受控”与“全部请求成功”。
6. SSE 断开、超时、队列拒绝、WebSocket 建连失败及节点摘除后，本地资源恢复到稳定基线；traffic 的 `inFlight` 及可选分类计数全部归零后才出现 `drained=true`，既有长连接不迁移，新连接只进入仍在池节点。
7. 单节点默认模式全量回归，证明容量池改造没有让现有客户被迫依赖 Nginx、NAS 或数据库 nonce。

## 6. 交付说明

交付时分别说明：API 影响、数据库影响、实际 `FB_DEPLOYMENT_MODE`、机构/批次峰值依据、主节点和容量节点清单、四条容量池路径、单节点及容量池阻塞/SSE/WebSocket/DB/上游结果、nonce 模式、存储模式与共享存储 ID、手工扩缩结果、执行的测试、未完成的真实环境验证，以及用户原有未提交改动。不得用“多节点”省略“仅 AI/长连接容量池”的范围限定。
