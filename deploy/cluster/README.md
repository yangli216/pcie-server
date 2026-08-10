# PCIE Server 三节点集群部署基线

此目录是面向 200 个医生终端的三节点 active-active 样例。业务请求可落到任意节点；发布文件与语音审计文件使用同一共享存储；数据库由所有节点共享。

## 拓扑与角色

- `pcie-01`、`pcie-02`、`pcie-03` 均承接 `/v1/*`。
- 三个节点统一设置 `FB_RELEASE_WRITER_NODE_ID=pcie-01`，只有 `FB_NODE_ID` 与该值相等的节点允许修改发布状态。所有节点仍可读取和下载发布文件。
- 负载均衡器只访问业务端口 `8080`。管理端口 `8081` 绑定 `127.0.0.1`，由节点本机监控代理采集。
- 发布目录和语音审计目录必须是各节点看到的同一共享挂载，不得放在系统临时目录。

## 共享存储预置

运维在启动应用前创建目录和 marker。应用不会自动生成 marker；cluster ID 不一致时节点拒绝启动。

```text
/srv/pcie-shared/releases/.pcie-cluster-id      内容：clinic-prod
/srv/pcie-shared/speech-audit/.pcie-cluster-id 内容：clinic-prod
```

为应用运行账号授予两个目录的读取、写入、创建临时文件、原子重命名和删除权限。以 `pcie-server.env.example` 为三节点生产变量基线；模板包含集群、共享存储、writer、管理端 loopback、连接池和 canonical timeout 覆盖。复制到受控配置系统后必须替换全部尖括号占位符，不能直接作为生产环境文件。

三台之间只有 `FB_NODE_ID` 不同。受控副本分别落为 `/etc/pcie-server/pcie-01.env`、`pcie-02.env`、`pcie-03.env`，与 systemd 实例名对应。数据库 profile/连接、`FB_CLUSTER_ID`、`FB_RELEASE_WRITER_NODE_ID`、共享目录、外部 LB URL 和各池参数必须一致；数据库凭据、`FB_AES_KEY` 与云服务凭据由 secret 管理系统注入，也必须在三节点保持同一有效版本。不要把真实密钥或地址回填到仓库示例。

本集群基线连接云端上游，生产环境按 `pcie-server.env.example` 关闭开发期的宽松出站策略，只允许实际使用的云服务 host。`FB_OUTBOUND_ALLOWED_HOSTS` 的占位符必须由受控配置替换。

如医院网络必须通过受控代理访问云端，应单独配置代理地址与凭据，并在上线前验证 DNS 解析、代理出口和 host 白名单三者一致。

`pcie-server.env.example` 中的池参数是三节点、200 终端首轮生产压测起点，必须在每个节点一致，不能替代压测结论。canonical timeout 环境变量用于覆盖任何数据库 profile 中残留的字面值，生产配置不得删去。

出站限流是“节点内、host + operation”舱壁；默认每节点每项操作 120 次/分钟。40 名医生各触发 4 路核心生成的 160 次首波请求只代表健康场景。桌面端当前单请求最多重试 3 次、没有 jitter 且不读取 `Retry-After`，理论最坏会形成 640 attempts；当前有界池能保护节点不被拖垮，但不能保证这些请求全部成功。200 终端和 N-1 正式验收前，必须先调整 `pcie` 重试策略，或按当前真实重试行为单独执行 640 attempts 故障压测，不能再用“20% 额外尝试即 192 次”作为故障容量结论。若网关还配置全局限流，其阈值必须单独核算，不能与节点阈值混为同一口径。

### JVM 与主机资源门禁

systemd 样例只提供 `PCIE_JAVA_OPTS` 占位，不给出拍脑袋的生产内存值。压测前必须按实际机器和线程池配置确定并记录：

- `-Xms`、`-Xmx`、`-Xss`；
- systemd `MemoryMax`，且必须覆盖 Java heap、metaspace、direct buffer、线程栈和其他 native memory；
- `LimitNOFILE`，且必须覆盖监听连接、上游连接、共享文件和运维采集连接并留出余量。

压测和 N-1 演练期间同时采集 GC、进程 RSS、线程数、打开文件数和连接池指标；在可控压测环境启用并采集 Native Memory Tracking。以实测峰值和安全余量确定最终值，并通过 systemd drop-in 配置 `MemoryMax`、`LimitNOFILE`，不得直接复制其他医院或开发机参数。

## 上线与排空

1. 在每个节点本机检查 `http://127.0.0.1:8081/actuator/health/readiness`，确认数据库、`nonceStore` 和 `clusterStorage` 均为 `UP`。
2. 运行 `cluster-preflight.sh`。除节点身份与 readiness 外，工具会对 release、speech 两个根分别执行 A 写随机 UUID、B/C 读一致、另一节点删、三节点确认消失。任何本地路径误配都会在跨节点读取阶段失败。全部通过才允许入池；`--writer-upstream-node-id` 必须来自当前生效的 LB 配置，不能填写期望值代替核对。
3. 负载均衡器必须由健康代理依据 readiness 动态摘挂节点，或使用支持主动健康检查的负载均衡产品；本目录的静态 Nginx upstream 不能单独承担 readiness 摘挂。逐台加入业务池，且至少保留两台健康节点，以满足三节点部署的 N+1 目标。
4. 下线节点时先从负载均衡池移除，等待活动请求和 SSE 流排空，再停止服务。不要直接同时重启三台。
5. 演练时至少覆盖：单节点退出、跨节点 nonce 重放拒绝、共享安装包下载、发布过程中节点排空。
6. 健康场景容量演练使用 40 名医生在 1 秒内各触发 4 路核心生成；云端响应 p95 按不超过 60 秒计算。另按桌面端当前最多 3 次重试执行 640 attempts 故障场景，验证快速拒绝和保护边界。90/120 秒上游响应场景不能通过扩大队列伪装为成功容量。
7. 三个服务节点、负载均衡器和医生终端均启用 NTP/chrony。时钟偏差达到 30 秒即告警，必须远低于签名 5 分钟有效窗口，避免合法请求被误判或放大重放窗口。

### 集群 preflight

工具依赖 Bash、`curl` 和 `jq`。管理端默认只监听 loopback，推荐在受控运维机分别建立 SSH 隧道，或在每台机器由本机代理执行同等检查；不要为了方便把管理端口直接暴露到业务网络。

```bash
# 以下三个长连接分别在三个受控终端保持运行
ssh -N -L 18081:127.0.0.1:8081 ops@pcie-01
ssh -N -L 28081:127.0.0.1:8081 ops@pcie-02
ssh -N -L 38081:127.0.0.1:8081 ops@pcie-03

./deploy/cluster/cluster-preflight.sh \
  --node pcie-01=http://127.0.0.1:18081 \
  --node pcie-02=http://127.0.0.1:28081 \
  --node pcie-03=http://127.0.0.1:38081 \
  --writer-upstream-node-id pcie-01
```

`--writer-upstream-node-id` 表示从当前生效的 `pcie_release_writer` upstream 反查出的节点，而不是计划切换到的节点。脚本先请求 `/actuator/info` 和 `/actuator/health/readiness`，再调用仅在 loopback 管理面暴露的 `/actuator/clusterStorageChallenge/{release|speech}`。challenge 的 token、content 都是随机 UUID，文件名由服务端派生，默认 60 秒 TTL；端点不接受任意路径或文件名。无网络自测见“工具验证”。

preflight 的固定跨节点顺序为：`pcie-01` 写、`pcie-02` 与 `pcie-03` 读、`pcie-02` 删除、三个节点确认不存在；speech 根再轮换为 `pcie-02` 写、另外两节点读、`pcie-03` 删除。端点只用于部署验证，禁止通过业务 LB 暴露或改造成通用文件 API。

### readiness 自动摘挂是上线前置条件

静态 `nginx.conf.example` 只有被动失败判断，不会读取 Spring Boot readiness。上线前必须选择并记录以下一种机制，完成 readiness `UP -> DOWN -> UP` 的自动摘除和恢复演练：

- 每个节点的本机健康代理轮询 `127.0.0.1:8081/actuator/health/readiness`，通过受控 LB API 摘挂对应业务节点；或
- 使用支持主动健康检查的 LB，通过独立管理网和防火墙访问管理端口。此时不得把 Actuator 暴露到医生终端网络。

演练记录至少包含 readiness 变更时间、LB 摘除时间、恢复时间和期间失败请求数。只部署本目录静态 Nginx 配置而没有上述机制，不满足生产上线门禁。

### 发布 writer 迁移与回退

以 `pcie-01` 迁移到 `pcie-02` 为例：

1. 保存三个节点原环境文件和当前 LB 配置，确认回退 writer 为 `pcie-01`。
2. 在 LB 暂停 `/admin/api/releases` 写入口，并确认没有在途上传、策略变更或回滚请求；客户端发布下载入口不受影响。
3. 将三个节点的 `FB_RELEASE_WRITER_NODE_ID` 都改为 `pcie-02`，但暂不开放写入口。
4. 先重启旧 writer `pcie-01`，使集群进入“暂时无 writer”状态；再逐台重启 `pcie-02`、`pcie-03`。不得先启动新 writer 形成短暂双写。
5. 将 LB `pcie_release_writer` upstream 改为 `pcie-02`，然后运行 preflight，参数使用 `--writer-upstream-node-id pcie-02`。
6. 只有 preflight 通过且新 writer 的 readiness 为 `UP` 后，才恢复 `/admin/api/releases`，并执行一次只读查询和受控发布验证。

任一步失败时保持发布写入口关闭：恢复三个节点原 `FB_RELEASE_WRITER_NODE_ID`，先重启当前 writer 使其失去写权限，再重启原 writer 和其余节点，恢复原 LB writer upstream；使用旧 writer 参数重新跑 preflight。回退验证通过后才能恢复写入口。

### 工具验证

仓库提供不访问网络的样例 Actuator JSON：

```bash
bash -n deploy/cluster/cluster-preflight.sh
deploy/cluster/test-cluster-preflight.sh
```

`nginx.conf.example` 展示业务流量入口，并明确透传 WebSocket Upgrade、关闭 SSE 缓冲和禁止 LB 自动重放已签名请求；`pcie-server@.service.example` 展示 systemd 的优雅停止与每节点环境文件加载方式。
