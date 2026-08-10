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
| 集群或部署 | nonce、共享存储、管理 token/AES、健康探针、优雅下线、LB 与滚动发布 |
| 数据库表或索引 | Oracle/GaussDB 初始化基线、对应 README、schema 测试；DM8 按项目规则交付定向兼容说明 |
| 管理端 | 同源 API、Vue 2 构建、可访问性和既有公共组件 |

## 3. 集群交付门禁

集群能力不能只以“启动了多个 JVM”验收，至少满足：

1. 同一设备、同一 nonce 的已签名请求同时到达两个节点时，只有一个节点可以接受。
2. nonce 存储不可用时 HTTP 返回 `SECURITY-503`，WebSocket 握手返回 503，不得降级为本地缓存。
3. 所有节点使用相同 `FB_AES_KEY`、数据库和共享存储，且 `FB_NODE_ID` 唯一、`FB_CLUSTER_ID` 一致。
4. 对 release 与 speech 两个根分别使用受控 `clusterStorageChallenge`：节点 A 原子写随机 UUID challenge，节点 B/C 读取一致，由另一节点删除，最后 A/B/C 都确认消失；只做本节点写读不算共享存储验收。
5. `/actuator/health/liveness` 与 `/actuator/health/readiness` 仅从管理网络访问；readiness 必须包含数据库与集群存储。
6. 滚动发布必须按“LB drain -> 等待在途请求/长连接 -> SIGTERM -> 新节点 readiness UP -> 重新入池”执行。
7. 发布写操作固定到一个 writer 节点；下载和策略读取可以由所有共享存储节点提供。

## 4. 200 终端容量合同

首轮生产目标固定为：

- 200 个已部署并可同时在线的医生终端；
- 40 名医生可同时进入核心四路 AI 推荐阶段；
- 健康场景是 160 个首波核心请求；桌面端当前每次调用最多再重试 3 次，故障场景理论上可放大到 640 attempts；
- 云端容量不纳入本服务核算，但完整响应时长按 p95 不超过 60 秒计算本地线程和连接驻留；
- 三节点 active-active，失去一个节点后核心请求仍可受控承载；
- Reviewer、知识检索、语音使用独立舱壁，不得无界叠加到核心四路推荐；连接池容量必须覆盖各舱壁之和并保留突发余量。

单节点默认只作为安全基线，不等同于 200 终端生产承诺。所有超额请求必须快速、可解释地拒绝，不能进入分钟级长队列。

## 5. 验证命令

```bash
mvn -f server/pom.xml test
mvn -f server/pom.xml package
npm --prefix server/src/main/admin run build
```

本机容量模拟默认跳过，必须显式开启：

```bash
mvn -f server/pom.xml -Dpcie.capacity.simulation=true -Dtest=AiCapacitySimulationTest test
mvn -f server/pom.xml -Dpcie.capacity.simulation=true -Dpcie.capacity.upstream-delay-ms=60000 '-Dtest=AiCapacitySimulationTest#nMinusOne_uniformDistributionAcceptsHealthy160WithRealHttpPool' test
mvn -f server/pom.xml -Dpcie.capacity.simulation=true -Dpcie.capacity.timeout-boundary-simulation=true '-Dtest=AiCapacitySimulationTest#nMinusOne_uniformDistributionReleasesResourcesAt120SecondReadTimeout' test
```

第一条使用加速延迟桩验证全部容量边界，其中 640 attempts 被压缩到约 1 秒派发，只代表极端突发保护，不代表桌面端真实 1/2/4 秒重试时序；第二条让均匀分发的 N-1 健康场景真实驻留 60 秒；第三条让上游静默超过生产 120 秒 HTTP read timeout，验证明确超时与资源回收，而不是把 120 秒边界写成成功容量。输出中的 `accepted/completed/rejected/failed`、端到端与上游执行 P95、逐节点峰值 active/leased/pending 和恢复后的资源状态必须一并留档。它只模拟生产执行器、连接池和延迟上游，不得写成真实三节点、签名、数据库、LB、共享存储或云端容量已验收。

涉及集群与并发时，除 JUnit 外还必须保留以下环境验证：

1. 200 个独立设备身份与真实 ECDSA 签名链路。
2. 40 名医生在 1 秒内各发起 4 路请求；健康场景验证 160 个首波请求，故障场景按桌面端当前真实重试策略验证最多 640 attempts，并区分“节点保持受控”与“全部请求成功”。
3. 30/60/90/120 秒延迟上游模拟；60 秒场景必须通过，90/120 秒用于确认保护边界。
4. 压测中摘除一个节点并恢复，确认无持续队列、无签名降级、无共享文件分裂。
5. SSE 断开、超时、队列拒绝和 WebSocket 建连失败均能释放本地资源。
6. 执行 `deploy/cluster/test-cluster-preflight.sh`，并在真实三节点通过 `cluster-preflight.sh` 完成两个共享根的跨节点 write/read/delete/absent 验证。

## 6. 交付说明

交付时分别说明：API 影响、数据库影响、部署配置、容量参数、执行的测试、未完成的真实三节点/数据库烟测，以及用户原有未提交改动。
