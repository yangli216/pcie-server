# PCIE Server 本地 Docker 双节点测试环境

本目录提供一套完全运行在 Docker 网络中的本地虚拟测试栈，用于验证 `ai-scale-out` 的双节点行为。应用节点、Nginx、数据库和共享存储都由同一份 Docker Compose 编排，不依赖宿主机上手工启动的 Java 进程，也不复用现场数据库。

该环境只用于开发和回归测试，不是生产部署模板。生产环境仍以 [`../multi-node/README.md`](../multi-node/README.md) 的人工扩缩容、真实共享 POSIX 存储和受控数据库流程为准。

## 目标与边界

本环境验证以下能力：

1. `pcie-primary` 与 `pcie-capacity` 使用相同应用镜像、同一测试数据库和同一共享卷启动。
2. 两个节点均运行 `gaussdb` Spring profile，并显式启用 `FB_DEPLOYMENT_MODE=ai-scale-out`。
3. Nginx 将普通业务固定转发到主节点，只将四条 AI 长任务路径按 `least_conn` 分发到双节点池。
4. 两个节点通过数据库 nonce 实现跨节点防重放，通过同一共享 POSIX 卷读写发布包和语音审计文件。
5. 停止、重启和销毁动作边界明确，不会误操作宿主机现有数据库、`8081` 桌面端 Bridge 或其他 Docker 数据卷。

本环境不证明生产 NAS/NFS 性能、数据库高可用、主节点故障切换或生产容量。Docker named volume 只模拟共享 POSIX 语义；它不能替代真实共享存储验收。

## 拓扑

```text
宿主机测试请求
      |
      v
Nginx :18000
      |
      +-- 普通业务 --------------------> pcie-primary:8080
      |
      +-- 四条 AI 长任务路径 ----------> pcie_long (least_conn)
                                          +-- pcie-primary:8080
                                          +-- pcie-capacity:8080

pcie-primary / pcie-capacity
      +-- schema-init (一次性成功后退出)
      |       +-- 仅以 rbmh_ai 远程检查并初始化 schema
      +-- openGauss:5432
      |       +-- prepare-database.sh 仅在全新数据卷内以本地 omm 准备 owner
      +-- pcie-shared:/mnt/pcie-shared
             +-- releases
             +-- speech-audit
```

数据库使用本机已有的 `enmotech/opengauss-lite:5.0.3` 镜像。该镜像是仓库 GaussDB/openGauss 说明为 macOS、Docker Desktop 和 ARM64 环境推荐的本地验证镜像。数据库容器由本 Compose 独立创建，使用独立测试库和独立 named volume；它不复用当前已有的 `floating-ball-opengauss-test` 容器，也不连接或修改宿主机已有的 `floating_ball`、Oracle、达梦或其他业务库。

## 目录文件

本环境文件名固定如下，文档和自动化命令不得另造同义入口：

- `compose.yml`：完整测试栈及依赖、网络、端口和 named volume。
- `Dockerfile`：从当前 Maven 工程构建统一的 PCIE Server 测试镜像。
- `Dockerfile.dockerignore`：应用镜像构建上下文排除规则。
- `container-healthcheck.sh`：应用容器健康检查。
- `prepare-database.sh`：只在 openGauss 全新 named volume 初始化阶段，以数据库容器内本地 `omm` 准备测试 database/schema owner。
- `init-schema.sh`：`schema-init` one-shot 数据库初始化与完整性检查。
- `nginx.conf`：单主节点与双节点 AI 长任务路由。
- `.env.example`：仅用于本地测试的变量模板，不包含现场凭据。

## 服务、端口与数据

| 服务 | 容器内端口 | 宿主机端口 | 用途 |
| --- | ---: | ---: | --- |
| `nginx` | `8080` | `127.0.0.1:18000` | 统一测试入口 |
| `pcie-primary` | `8080` | `127.0.0.1:18101` | 主节点直连诊断 |
| `pcie-primary` | `8081` | `127.0.0.1:19101` | 主节点 Actuator |
| `pcie-capacity` | `8080` | `127.0.0.1:18102` | 容量节点直连诊断 |
| `pcie-capacity` | `8081` | `127.0.0.1:19102` | 容量节点 Actuator |
| `opengauss` | `5432` | `127.0.0.1:15433` | 本地测试库诊断 |
| `schema-init` | - | - | 一次性 schema 初始化/验证，成功后退出 |

这些宿主机端口均为本地测试端口，不占用 PCIE 桌面端 Bridge 的 `127.0.0.1:8081`。数据库端口只用于本机排障；应用节点必须通过 Compose 内部服务名访问数据库。

持久数据分为两个 named volume：

- `pcie-db-data`：保存 openGauss 测试库。普通停止和再次启动会保留它。
- `pcie-shared`：同时挂载到两个应用节点的 `/mnt/pcie-shared`，保存 `releases`、`speech-audit` 及对应 `.pcie-storage-id`。

每个节点仍使用独立容器文件系统和唯一 `FB_NODE_ID`。除数据库和上述共享目录外，不得通过整目录共享掩盖节点本地状态问题。

## 启动

从 `pcie-server` 仓库根目录执行：

```bash
mvn -f server/pom.xml -DskipTests -Dskip.installnodenpm=true -Dskip.npm=true package
cp deploy/docker-multi-node/.env.example deploy/docker-multi-node/.env
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml up --detach --build
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml ps --all
```

应用节点、Nginx、数据库和 schema 初始化等运行服务全部在 Docker 容器中执行，但本目录的 `Dockerfile` 是 runtime-only 镜像：它只封装上述 Maven 命令在宿主机生成的当前 fat JAR，不在镜像构建阶段重新编译源码。该 JAR 和生成的应用镜像只用于本地隔离测试，不得推送到镜像仓库或作为发布产物分发。

源码或 fat JAR 有任何变化后，必须重新执行 Maven `package`，再执行带 `--build` 的 Compose `up`，确保两个节点使用同一份最新 JAR。仅重新启动已有容器不会包含源码变化。

复制完成后必须先编辑 `.env`，替换示例数据库密码、应用密钥和其他示例值，再执行 `up`。不得直接沿用 `.env.example` 中的占位密码/密钥，也不得把生成的 `.env` 提交到仓库。本文所有 Compose 命令都从仓库根目录执行并显式指定同一份 `--env-file deploy/docker-multi-node/.env`，避免 Compose 从当前目录隐式选择其他环境文件。

首次创建 `pcie-db-data` 时，openGauss 容器必须在自身的本地初始化阶段执行 `prepare-database.sh`。该脚本只允许通过容器内本地连接使用 `omm`，把本环境新建的测试 database 与 `public` schema owner 授给 `rbmh_ai`，并授予 `rbmh_ai` 完整执行 `init.sql` 所需的测试库权限。已有数据卷再次启动时不得重复转移 owner，也不得用该脚本修改任何存量 schema。

openGauss 禁止初始超级用户 `omm` 从其他容器远程登录。本环境不得修改访问控制来开放 `omm` 远程连接，也不得把 `omm` 口令或超级用户连接传给 `schema-init`、应用节点或宿主机。`schema-init` 必须等待 openGauss healthcheck 通过，之后始终只以 `rbmh_ai` 远程连接，并按以下 fail-closed 顺序处理当前 `public` schema：

1. schema 不包含任何 PCIE 业务对象时，确认 database 与 `public` schema owner 已由本地 `prepare-database.sh` 正确授给 `rbmh_ai`，再以 `rbmh_ai` 完整执行当前 GaussDB/openGauss `init.sql`；owner 或权限不符时直接失败，不尝试提权修复。
2. 完整执行成功后，由本 Docker 环境单独创建 `c_ai_docker_schema_state`，并写入 marker `docker_full_init_v1`。这个表只表达“本 Compose 已完整执行当前 `init.sql`”，不复用、不解释业务迁移表 `c_ai_schema_migration`，也不依赖其中任何业务 marker。
3. `c_ai_docker_schema_state` 中已存在 `docker_full_init_v1` 时，不重复执行 `init.sql`，只核对 owner、Docker marker、核心业务表以及 nonce 表的字段、主键和索引；全部通过后退出 `0`。
4. 已存在任何 PCIE 业务对象、但没有 Docker marker，视为部分 schema 或非本环境创建的存量 schema，立即非零退出；脚本不得尝试补建、覆盖或根据业务迁移 marker 猜测初始化状态。
5. Docker marker 存在但关键对象、权限或 nonce 约束校验失败时，同样非零退出，不得让应用节点启动。

`c_ai_docker_schema_state` 是本地 Docker 测试栈的引导状态，不属于通用业务 schema 基线；因此不得把它折叠进 GaussDB/openGauss `init.sql`，也不得要求应用运行时开启某项特定业务表的 schema 校验来替代本 one-shot 完整性检查。

`pcie-primary` 和 `pcie-capacity` 必须依赖 `schema-init` 成功完成，不能只依赖数据库端口可连接。`prepare-database.sh` 与 `init-schema.sh` 只面向本环境新建的数据卷和独立测试库；不得把 Compose 的数据库地址、账号或初始化动作改指向已有业务库。

应用容器必须显式使用以下关键配置，任一项缺失时节点应启动失败或 readiness 为 `DOWN`：

- `SPRING_PROFILES_ACTIVE=gaussdb`
- `FB_DEPLOYMENT_MODE=ai-scale-out`
- `FB_NODE_ID=pcie-primary` 或 `pcie-capacity`
- `FB_NONCE_STORE=database`
- `FB_STORAGE_MODE=shared-posix`
- 两节点一致且非空的 `FB_SHARED_STORAGE_ID`
- 两节点相同的 `/mnt/pcie-shared/releases` 与 `/mnt/pcie-shared/speech-audit`

### Hikari 参数注入

`.env` 仍使用 `PCIE_DB_POOL_MAX_SIZE`、`PCIE_DB_POOL_MIN_IDLE` 和 `PCIE_DB_CONNECTION_TIMEOUT_MS` 作为本环境统一的连接池输入，但 Compose 不得把它们转换为 `SPRING_DATASOURCE_HIKARI_*` 环境变量。`gaussdb` profile 已包含 Hikari 字面量配置；DataSource 启动后再次用环境变量绑定这些属性会触发 sealed property rebind 并导致节点启动失败。

两个应用服务必须把上述三个值以 Spring Boot CLI 参数附加到 Java 启动命令，分别对应：

```text
--spring.datasource.hikari.maximum-pool-size=${PCIE_DB_POOL_MAX_SIZE}
--spring.datasource.hikari.minimum-idle=${PCIE_DB_POOL_MIN_IDLE}
--spring.datasource.hikari.connection-timeout=${PCIE_DB_CONNECTION_TIMEOUT_MS}
```

CLI 参数优先级高于 `gaussdb` profile，并在 DataSource 首次创建时一次性生效。不要同时保留同名 `SPRING_DATASOURCE_HIKARI_*` 环境变量，也不要为了绕过启动失败修改生产 profile 的连接池基线。

不要把生产 profile、生产数据库密码、现场密钥或实际发布文件复制进本目录。测试密钥和测试账号只能作用于本 Compose 网络。

## 验证

### 基础健康检查

```bash
curl --fail http://127.0.0.1:19101/actuator/health/liveness
curl --fail http://127.0.0.1:19101/actuator/health/readiness
curl --fail http://127.0.0.1:19102/actuator/health/liveness
curl --fail http://127.0.0.1:19102/actuator/health/readiness
curl --fail http://127.0.0.1:19101/actuator/traffic
curl --fail http://127.0.0.1:19102/actuator/traffic
curl --fail http://127.0.0.1:18000/admin/
```

`schema-init` 必须是成功退出状态，不能处于循环重启或失败状态。两个应用节点必须同时满足：liveness/readiness 为 `UP`，traffic 为 `accepting`，节点 ID 唯一，数据库 nonce 和共享存储检查通过。

### 多节点门禁

完整验收至少保留以下结果：

1. 同一份仍在有效时间窗口内的 ECDSA 签名请求分别直连 `18101` 与 `18102`，只能一次成功，另一次必须返回 `401`；反向先后顺序再验证一次。
2. 主节点写入共享卷的测试发布文件能被容量节点读取，容量节点写入的语音审计测试文件能被主节点读取；测试结束后清理这些标记文件。
3. 通过 `18000` 访问普通业务时只命中 `pcie-primary`；四条 canonical AI 长任务路径能够命中双节点池。
4. `/v1/ai/chat` 的 SSE 无代理缓冲，实时语音 WebSocket 能完成升级、收发和正常关闭。
5. 将容量节点从 Nginx 池摘除并执行 drain 后，新请求不再进入该节点，且只有 `drained=true` 才允许停止容器。

不得用“容器均为 running”代替这些门禁，也不得把同机 named volume 的成功描述为真实 NAS/NFS 已验收。

## 停止、重启与日志

停止整个测试栈但保留数据库和共享卷：

```bash
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml stop
```

再次启动并复用现有测试数据：

```bash
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml start
```

停止并删除容器、网络，但保留 named volume：

```bash
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml down
```

查看服务状态和日志：

```bash
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml ps
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml logs --follow nginx pcie-primary pcie-capacity schema-init opengauss
```

停止前如果存在真实 SSE、WebSocket 或长任务，应先从 Nginx 摘除对应节点，再通过 `/actuator/traffic/drain` 等待 `drained=true`。直接 `stop` 只适用于确认无在途任务的本地测试。

## 清理边界

只有明确需要重建全新测试库和共享卷时，才允许执行：

```bash
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml down --volumes
```

该命令会永久删除本测试栈的 `pcie-db-data` 和 `pcie-shared` 数据，无法恢复。执行前必须先执行以下命令核对目标，仅删除本项目 Compose 创建的命名卷；不得按前缀、通配符或全局 prune 清理 Docker 资源。

```bash
docker compose --env-file deploy/docker-multi-node/.env -f deploy/docker-multi-node/compose.yml config --volumes
```

清理 Compose 不会、也不应停止宿主机 PCIE 桌面端、删除其他 Docker 容器或更改已有业务数据库。测试结束后应确认 `18000`、`18101`、`18102`、`19101`、`19102` 和 `15433` 已释放，而宿主机 `127.0.0.1:8081` Bridge 保持原状态。
