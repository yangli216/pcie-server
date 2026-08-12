# PCIE Server 单实例生产部署基线

当前优先保证一个 PCIE Server 实例能够独立启动、提供完整业务并安全停止。Nginx 是可选的统一入口，用于固定访问地址、承载现场 TLS，以及正确代理 SSE、WebSocket 和大文件上传；Java 服务不能依赖 Nginx 才能启动。

```text
PCIE 客户端 / 管理端
        |
        | 可选的统一入口
        v
      Nginx
        |
        v
PCIE Server :8080
        |
        +-- 业务数据库
        +-- /var/lib/pcie-server/releases
        +-- /var/lib/pcie-server/speech-audit
```

本目录包含四个文件：

1. `pcie-server.env.example`：无真实地址和凭据的生产环境模板。
2. `pcie-server.service.example`：单实例 systemd 服务模板。
3. `nginx.conf.example`：可选的单 upstream Nginx 模板。
4. 本文档：安装、验证、停止和恢复边界。

## 目录与权限

建议使用专用的 `pcie` 系统账号，并预置以下目录：

```bash
sudo install -d -o root -g pcie -m 0750 /etc/pcie-server
sudo install -d -o pcie -g pcie -m 0750 /opt/pcie-server/app
sudo install -d -o pcie -g pcie -m 0750 /var/lib/pcie-server/releases
sudo install -d -o pcie -g pcie -m 0750 /var/lib/pcie-server/speech-audit
sudo install -d -o pcie -g pcie -m 0750 /var/log/pcie-server
```

用途如下：

- `/opt/pcie-server/app/pcie-server.jar`：当前受控版本的可执行 JAR。
- `/etc/pcie-server/pcie-server.env`：现场环境和密钥，建议权限为 `root:pcie 0640`。
- `/var/lib/pcie-server/releases`：客户端安装包、发布元数据与历史快照。
- `/var/lib/pcie-server/speech-audit`：语音审计文件。
- `/var/log/pcie-server`：应用滚动日志。

上述路径用于新安装。已有现场若仍使用受兼容约束保护的
`/opt/floating-ball-server`、`/data/floating-ball-server-*` 或既有 systemd
服务名，应在原路径上套用本模板的单实例参数，不要仅为本次清理擅自重命名；确需迁移时另行制定停机、备份和回退方案。

两个 `/var/lib` 子目录都属于业务持久数据，必须纳入备份、容量监控和磁盘告警。不得改回系统临时目录。

## 环境配置

将 `pcie-server.env.example` 复制到受控配置系统或 `/etc/pcie-server/pcie-server.env`，替换全部 `<...>` 占位符。不要把真实数据库密码、应用密钥、代理凭据或现场地址提交到仓库。

`SPRING_PROFILES_ACTIVE` 必须填写已经针对现场数据库完成审核的生产 profile。不得使用名称包含 `development` 或 `test` 的 profile，也不能依赖 profile 中的示例账号、示例密码、开发代理或临时目录默认值。数据库驱动、URL、账号、Hikari 参数与 MyBatis 数据库类型都应由现场环境明确给出。

如果 Nginx 与 Java 服务在同一台机器，`SERVER_ADDRESS` 优先设为 `127.0.0.1`。如果暂不安装 Nginx 或需要从受控运维网直接访问，可以绑定业务私网地址，但端口 `8080` 必须由防火墙限制在明确的业务网或运维网内，不能暴露到不受控网络。

管理端口固定绑定 `127.0.0.1:8081`，不通过 Nginx 转发，也不对 PCIE 客户端开放。

JVM 的 `Xms`、`Xmx`、`Xss`、systemd `MemoryMax` 与 `LimitNOFILE` 必须来自现场主机和真实混合负载验证；本模板不提供可直接照抄的容量值。

## systemd 安装与启动

将模板复制为正式服务文件并检查内容：

```bash
sudo install -o root -g root -m 0644 \
  deploy/standalone/pcie-server.service.example \
  /etc/systemd/system/pcie-server.service
sudo systemctl daemon-reload
sudo systemctl enable --now pcie-server
sudo systemctl status pcie-server
```

systemd 与 Nginx 没有启动依赖。先在没有 Nginx 的情况下确认 Java 服务本身可启动，再决定是否接入统一入口。

## 健康检查

Actuator 只允许从本机检查：

```bash
curl --fail --silent http://127.0.0.1:8081/actuator/health/liveness
curl --fail --silent http://127.0.0.1:8081/actuator/health/readiness
```

- liveness 用于确认 JVM 进程仍然存活。
- readiness 用于确认数据库和业务 schema 已可服务。
- 两者都不能代替真实的签名请求、SSE、WebSocket、上传和下载验证。

启动验收至少包括：管理端首页可访问、一次真实 ECDSA 签名请求成功、SSE 能逐帧到达、实时语音 WebSocket 能完成握手和关闭、受控测试文件可上传并下载、日志和两个持久目录均可写。

## 可选 Nginx

`nginx.conf.example` 默认连接本机 `127.0.0.1:8080`，并包含以下必要行为：

- 已签名请求关闭代理自动重试，避免重复使用 nonce。
- `/v1/ai/chat` 关闭代理缓冲，保证 SSE 及时到达客户端。
- 实时语音路径透传 WebSocket Upgrade，并关闭包含签名 query 的访问日志。
- 发布上传允许最大 `2048m` 请求体，读写超时为 600 秒。

接入顺序：

1. 先完成 Java 服务直连验收。
2. 根据现场访问地址和 TLS 规范调整 Nginx 配置。
3. 执行 `nginx -t`，通过后再 reload。
4. 通过统一入口重复签名、SSE、WebSocket、上传和下载验收。
5. 最后才把客户端访问地址切换到统一入口。

Nginx 配置存在于仓库不代表现场已经安装或运行 Nginx，必须以服务器上的服务状态和生效配置为准。

## 优雅停止与恢复

应用已经启用 Spring Boot graceful shutdown。计划维护时先停止新业务进入，等待普通请求、SSE 和 WebSocket 在维护窗口内完成，再发送 SIGTERM：

```bash
sudo systemctl stop pcie-server
```

systemd 的停止等待时间应大于应用的 shutdown timeout。强制结束进程会中断正在进行的生成和语音会话，只能作为故障处置手段，并需要记录影响。

Java 服务不依赖 Nginx，因此 Nginx 故障时运维仍可通过本机或受控运维网直连业务端口进行诊断。若要临时让业务流量绕过 Nginx，必须同时确认客户端协议、访问地址和 TLS 要求能够满足，并执行有记录、可回退的防火墙与入口变更；恢复后立即关闭临时通道。单台 Nginx 不提供无感故障切换，不能把临时 HTTP 直连描述为高可用。

本目录只承载单实例正式交付。确认单实例稳定、当前版本的共享数据库 nonce 与共享 POSIX 存储门禁已经完成后，可按 [手工多节点部署基线](../multi-node/README.md) 增加 AI 长任务节点；不能只在本模板前增加多个 upstream 就宣称已经支持扩展。
