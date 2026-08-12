# PCIE Server 手工多节点部署基线

本目录用于在单实例已经稳定运行后，按可预期的用户增长手工增加 AI 长任务节点。它只把以下四条高占用路径分配到多个节点：

- `POST /v1/ai/chat`
- `POST /v1/ai/speech/transcribe`
- `POST /v1/ai/speech/realtime`
- `WebSocket /v1/ai/speech/realtime/ws`

其他请求仍只进入一个 `pcie_primary` 主节点。因此，这套拓扑解决的是 AI 长连接和长任务容量扩展，不是全业务高可用；主节点不可用时，管理端、配置、发布和其他普通业务路径仍会中断。

```text
PCIE 客户端 / 管理端
          |
          v
        Nginx
          |
          +-- 四条 AI 长任务路径 --> pcie_long (least_conn)
          |                            +-- pcie-01（主节点）
          |                            +-- pcie-02（容量节点）
          |                            +-- pcie-03（容量节点）
          |
          +-- 其他全部路径 --------> pcie_primary
                                       +-- pcie-01（主节点）

所有 PCIE Server 节点
          +-- 同一业务数据库（数据库 nonce 防重放）
          +-- 同一共享 POSIX 存储（发布包与语音审计文件）
```

本目录包含：

1. `pcie-server.env.example`：每台节点使用的环境模板；`FB_NODE_ID` 必须逐节点唯一，其余集群关键配置必须一致。
2. `pcie-server.service.example`：依赖共享挂载的 systemd 服务模板。
3. `nginx.conf.example`：单主节点与 AI 长任务节点池的静态 Nginx 模板。
4. 本文档：前置条件、入池、验收、缩容与回退流程。

## 上线前置条件

多节点不是在单实例前面简单增加 Nginx。所有条件同时满足后才能把第二个节点加入 `pcie_long`：

1. 所有节点运行完全相同的 PCIE Server 版本、生产 profile、应用密钥和业务配置。
2. 所有节点连接同一个业务数据库；数据库 nonce 表、唯一约束和应用账号权限已经按当前版本的数据库基线完成。
3. `FB_NONCE_STORE=database`，且任意节点都不能回退到 JVM 内存 nonce。
4. 所有节点将同一份 NAS/NFS 挂载到同一个绝对路径，例如 `/mnt/pcie-shared`；`releases` 与 `speech-audit` 都位于该挂载内。
5. `FB_STORAGE_MODE=shared-posix`，所有节点的 `FB_SHARED_STORAGE_ID` 完全一致，节点各自的 `FB_NODE_ID` 唯一。
6. 各节点的 `pcie` 用户使用一致的 UID/GID，并且能够读写两个共享业务目录。
7. 管理端口 `127.0.0.1:8081` 只允许本机运维检查，不通过 Nginx 暴露。
8. 已有单实例完成备份，并有可执行的 Nginx 配置回退和应用版本回退方案。

### 存量库共享 nonce 初始化

新建库的 `init.sql` 已包含共享 nonce 对象。存量 Oracle 或 GaussDB/openGauss schema 在启用第二个节点前，使用仓库对应数据库目录下的 `update_shared_nonce.sql`；脚本由 DBA 审核后手工执行，应用和本部署流程不会连接数据库自动执行：

```text
Oracle:  以应用 schema 登录 SQL*Plus/SQLcl 后执行 @update_shared_nonce.sql
GaussDB: gsql -v ON_ERROR_STOP=1 -f update_shared_nonce.sql
```

该定向脚本只幂等创建 `c_ai_request_nonce`、复合主键 `(id_device, nonce_hash)` 和过期索引 `idx_c_ai_request_nonce_exp`。它不包含连接地址或凭据，不执行 `CONNECT/\connect`，不执行任何 `DROP`，也不读取、迁移或删除旧 `c_security_request_nonce`。同名对象已经正确存在时重复执行为 no-op；结构或唯一约束不一致时必须报错停止，由 DBA 处理漂移后重试，不能让脚本静默修改现场对象。

执行完成不等于可以入池。DBA 必须确认应用账号具备实际查询、插入和删除权限，并保留脚本退出码与对象核对记录；随后让应用 nonce 深度探针完整验证写入、唯一冲突和清理。`standalone` 使用内存 nonce 时不需要执行该脚本。

共享目录应由存储管理员预先创建，不能依赖应用启动时临时创建。两个业务根目录还必须分别包含 `.pcie-storage-id`，文件内容精确等于所有节点配置的 `FB_SHARED_STORAGE_ID`。

首次初始化时由操作者输入实际存储标识；下面的命令拒绝空值，也拒绝覆盖任何已有标识文件，避免把模板占位符或新的错误标识写入已使用的共享盘：

```bash
(
  set -eu
  read -r -p "PCIE shared storage ID: " PCIE_STORAGE_ID
  test -n "$PCIE_STORAGE_ID"

  sudo install -d -o pcie -g pcie -m 0750 /mnt/pcie-shared/releases
  sudo install -d -o pcie -g pcie -m 0750 /mnt/pcie-shared/speech-audit
  sudo test ! -e /mnt/pcie-shared/releases/.pcie-storage-id
  sudo test ! -e /mnt/pcie-shared/speech-audit/.pcie-storage-id

  sudo sh -c 'umask 0137; set -C; printf "%s" "$1" > "$2"' sh \
    "$PCIE_STORAGE_ID" /mnt/pcie-shared/releases/.pcie-storage-id
  sudo sh -c 'umask 0137; set -C; printf "%s" "$1" > "$2"' sh \
    "$PCIE_STORAGE_ID" /mnt/pcie-shared/speech-audit/.pcie-storage-id
  sudo chown pcie:pcie \
    /mnt/pcie-shared/releases/.pcie-storage-id \
    /mnt/pcie-shared/speech-audit/.pcie-storage-id
  sudo chmod 0640 \
    /mnt/pcie-shared/releases/.pcie-storage-id \
    /mnt/pcie-shared/speech-audit/.pcie-storage-id

  findmnt -T /mnt/pcie-shared
)
```

如果任一 `.pcie-storage-id` 已经存在，应先读取并核对现值，不得直接删除或覆盖。应用启动时会把两个文件与 `FB_SHARED_STORAGE_ID` 逐一比对；缺失、不可读或内容不一致都会阻止该节点进入可服务状态。

如果现场使用不同挂载点，必须同时修改环境文件中的两个存储路径，以及 systemd 文件中的 `ConditionPathIsMountPoint`、`RequiresMountsFor` 和启动前检查。不能在 NAS 未挂载时让节点悄悄写入本机同名目录。

## 节点配置与启动

在每台应用节点安装相同 JAR 和 systemd 模板：

```bash
sudo install -o root -g root -m 0644 \
  deploy/multi-node/pcie-server.service.example \
  /etc/systemd/system/pcie-server.service
sudo systemctl daemon-reload
```

将 `pcie-server.env.example` 复制到 `/etc/pcie-server/pcie-server.env`。以下项目必须逐节点核对：

- `FB_NODE_ID`：例如 `pcie-01`、`pcie-02`，不得重复。应用只能校验本机值非空；入池前必须在本批完整节点清单中人工查重，并核对 Prometheus `instance` 标签。
- `SERVER_ADDRESS`：该节点可被 Nginx 访问的业务私网地址。
- `FB_DEPLOYMENT_MODE=ai-scale-out`。
- `FB_NONCE_STORE=database`。
- `FB_STORAGE_MODE=shared-posix`。
- `FB_SHARED_STORAGE_ID`：所有节点相同，并与两个共享目录下 `.pcie-storage-id` 的内容精确一致。

先启动节点，但暂时不要加入 Nginx：

```bash
sudo systemctl enable --now pcie-server
sudo systemctl status pcie-server
curl --fail --silent http://127.0.0.1:8081/actuator/health/liveness
curl --fail --silent http://127.0.0.1:8081/actuator/health/readiness
curl --fail --silent http://127.0.0.1:8081/actuator/health/nonceStore
curl --fail --silent http://127.0.0.1:8081/actuator/health/storage
curl --fail --silent http://127.0.0.1:8081/actuator/traffic
```

readiness、`nonceStore` 和 `storage` 必须为 `UP`，traffic 必须处于接流状态。同时检查启动日志，确认部署模式、节点标识、数据库 nonce 和共享存储配置均为预期值。Actuator 结果只证明节点当前可服务，不代替跨节点防重放和共享文件验收。

nonceStore 的运行期轻量检查不能替代低频深度探针。任一次深度探针发现 `c_ai_request_nonce` 写入/删除权限或复合唯一约束漂移后，节点必须锁存不可信状态并把 readiness 置为 DOWN；此后 HTTP nonce claim 和实时语音 WebSocket 握手统一 fail-closed 为 `SECURITY-503`。普通查询恢复或轻量探针转绿不能解除锁存，只有后续完整深度探针重新通过才能恢复接流。运维应先从 `pcie_long` 摘除受影响节点，再由 DBA 修复权限或约束，禁止临时切回内存 nonce。

## 跨节点门禁

### 同 nonce 双节点门禁

使用已注册测试设备构造一个仍在时间窗口内的真实 ECDSA 签名请求。请求方法、路径、body、时间戳、nonce 和签名必须完全相同，然后分别直连两个节点各发送一次：

1. 两次请求中只能有一次通过签名门禁。
2. 另一次必须因 nonce 已使用而返回 HTTP `401`。
3. 如果两次都成功，说明仍在使用节点本地 nonce，禁止入池。
4. 如果数据库不可用时请求仍能绕过签名门禁成功，同样禁止入池。

该检查必须覆盖普通 HTTP 签名路径；实时语音 WebSocket 上线前还应使用同一组 `ts/nonce/sig` 分别向两个节点发起握手，并确认最多只有一个握手成功。

### 共享文件门禁

在受控测试目录中由主节点写入一个唯一标记或测试发布文件，容量节点必须立即读取到相同内容；随后反向执行一次。检查完成后清理测试数据。任一节点看到本机副本、内容不一致或 NAS 断开后仍显示 readiness `UP`，都不得入池。

## Nginx 入池与低权重预热

`nginx.conf.example` 中：

- `pcie_primary` 只配置主节点。
- `pcie_long` 使用 `least_conn`，包含主节点和所有容量节点。
- 新节点先以较低 `weight` 加入，例如现有节点 `weight=10`、新节点 `weight=1`。
- 所有代理位置都设置 `proxy_next_upstream off`，避免 Nginx 自动重放已经签名且 nonce 只能使用一次的请求。
- 只有本文列出的四条 canonical 路径使用精确匹配进入 `pcie_long`。其余 `/v1/ai` 路径及变体，包括尾斜杠、分号 path parameter、重复斜杠或额外子路径，必须在 Nginx 直接拒绝，不能落到 `pcie_primary` 或被 URI 规范化后绕过路由边界。
- canonical WebSocket 路径和所有被拒绝的 WebSocket 变体都不得把 query 写入日志；日志格式只能使用不含 `$args` 的 `$uri` 等脱敏字段，或对相关 location 关闭 access log，避免泄露 token、nonce 和签名。

模板中的 `192.0.2.11`、`192.0.2.12` 是不可用于现场的文档示例地址，安装时必须替换为主节点和容量节点的真实业务私网地址；`pcie_primary` 与 `pcie_long` 中代表主节点的地址必须保持一致。

每次变更都先检查再平滑加载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

预热阶段只观察新建立的 AI 请求和连接。确认新节点的错误率、上游连接、线程池、数据库连接池、SSE 完整性与 WebSocket 正常关闭后，再分阶段提高权重，最终与同规格节点一致。Nginx Open Source 不会主动读取应用 readiness；节点是否入池、摘除和恢复均由运维人员依据本机探针与监控手工决定。

如果节点此前处于摘流状态，应在完成本机门禁后先恢复接流，再加入低权重 upstream：

```bash
curl --fail --silent --request POST http://127.0.0.1:8081/actuator/traffic/resume
curl --fail --silent http://127.0.0.1:8081/actuator/traffic
curl --fail --silent http://127.0.0.1:8081/actuator/health/readiness
```

## 扩容验收

统一入口至少完成以下验证：

1. 普通管理和业务请求只出现在 `pcie_primary` 主节点。
2. 四条 AI 路径的新请求能够分布到 `pcie_long` 的不同节点。
3. `/v1/ai/chat` 的 SSE 帧及时到达，没有被代理缓冲。
4. 实时语音 WebSocket 能完成签名握手、持续收发和正常关闭。
5. 批量语音接口和发布上传下载在现场允许的最大样本下完成。
6. 同 nonce 双节点门禁仍保持“一次成功、一次 401”。
7. 每个节点的 liveness/readiness 均为 `UP`，日志和指标能够按唯一 `FB_NODE_ID` 区分。

只有真实客户端混合负载验证通过，才能把节点数和单节点会话上限作为现场容量结论。

## 缩容、维护与回退

`least_conn` 只选择新连接，不能拆分或迁移已经建立的 SSE、WebSocket 或正在执行的普通长任务。

计划摘除容量节点时：

1. 从 `pcie_long` 删除或注释该节点；降低权重只能作为预降载，不能代替正式摘流，因为正权重节点仍可能收到新请求。
2. 执行 `nginx -t` 并平滑 reload，确认目标节点的新请求计数持续不再增长。
3. 在目标节点本机执行 `POST /actuator/traffic/drain`，并用 `GET /actuator/traffic` 确认已经进入摘流状态；readiness 此时应反映不可接收新流量。
4. `GET /actuator/traffic` 必须返回统一的 `inFlight`，其口径覆盖执行中的 blocking、SSE、WebSocket 和仍在队列中的任务；可以同时返回 `blocking/sse/websocket/queued` 明细。只有 `inFlight=0`（若返回明细则各项也全部为 `0`）时才允许 `drained=true`。
5. 以 `drained=true` 作为正常停机的唯一应用侧依据，再执行 `systemctl stop pcie-server`；仅看到 readiness DOWN、Nginx 已摘除或某一种长连接为零都不代表排空完成。
6. 超过维护窗口仍未 `drained=true` 时只能按故障处置强制停止，必须记录被中断的任务和连接，不能描述为优雅缩容。

```bash
curl --fail --silent --request POST http://127.0.0.1:8081/actuator/traffic/drain
curl --fail --silent http://127.0.0.1:8081/actuator/traffic
```

紧急回退时，先把 `pcie_long` 恢复为仅包含主节点的已验证配置，再平滑 reload。该操作只影响之后建立的连接；已经落在故障节点上的连接会中断，客户端需要按既有协议重连或批量补录。不要把 Nginx reload 描述为长连接无感迁移。

## 本地 Docker 回归环境

需要在开发机上完整模拟 Nginx、双应用节点、独立 openGauss、一次性 schema 初始化和共享 POSIX 卷时，使用 [`../docker-multi-node/README.md`](../docker-multi-node/README.md)。openGauss 只在全新 named volume 的本地初始化阶段由 `prepare-database.sh` 以内置本地 `omm` 准备测试 database/schema owner；禁止开放 `omm` 跨容器远程连接。两个应用节点统一使用 `gaussdb` profile，后续 one-shot `schema-init` 只以 `rbmh_ai` 远程初始化或验证测试 schema，成功后才允许应用启动。首次复制 `.env.example` 后必须替换示例密码和密钥；从仓库根执行的全部 Compose 命令都必须显式指定 `--env-file deploy/docker-multi-node/.env`。该环境只用于本地回归，不替代本手册的生产部署与真实存储验收。
