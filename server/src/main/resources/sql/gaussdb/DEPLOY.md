# GaussDB 服务器测试部署说明

本文档面向单机 `java -jar` 测试部署，数据库使用 `application-gaussdb.yml`。

## 目录约定

推荐在服务器上统一放到 `/opt/floating-ball-server`：

```bash
sudo mkdir -p /opt/floating-ball-server/{app,logs,data/releases,data/speech-audit}
sudo chown -R "$USER":"$USER" /opt/floating-ball-server
```

目录用途：

1. `/opt/floating-ball-server/app`：放置 Spring Boot jar。
2. `/opt/floating-ball-server/logs`：应用滚动日志目录，主日志为 `floating-ball-server.log`。
3. `/opt/floating-ball-server/data/releases`：管理端上传的客户端安装包、`latest.json` 与发布历史。
4. `/opt/floating-ball-server/data/speech-audit`：语音审计录音文件目录。

## 打包

在源码目录执行：

```bash
mvn -f server/pom.xml package
```

将生成的 jar 上传到服务器：

```bash
server/target/pcie-server-0.2.0-SNAPSHOT.jar
```

## 环境变量

建议在服务器创建 `/opt/floating-ball-server/app/floating-ball-server.env`。该文件同时兼容 shell 启动和 systemd `EnvironmentFile`，因此使用 `KEY=VALUE` 格式，不写 `export`：

```bash
SPRING_PROFILES_ACTIVE=gaussdb
FB_SERVER_PORT=8080

FB_DB_URL=jdbc:opengauss://10.17.73.49:8000/phis?currentSchema=phis
FB_DB_USERNAME=phis
FB_DB_PASSWORD=******

FB_LOG_PATH=/opt/floating-ball-server/logs
FB_LOG_ROOT_LEVEL=INFO
FB_APP_LOG_LEVEL=INFO

FB_AUDIT_SPEECH_FILE_DIR=/opt/floating-ball-server/data/speech-audit
FB_RELEASE_STORAGE_DIR=/opt/floating-ball-server/data/releases
FB_RELEASE_PUBLIC_BASE_URL=http://服务器IP:8080

FB_ADMIN_ORIGIN=http://服务器IP:8080
FB_AES_KEY=请替换为16位以上随机密钥
```

说明：

1. `FB_DB_PASSWORD`、`FB_AES_KEY` 不建议写入仓库，只在服务器环境文件或启动系统中配置。
2. `FB_RELEASE_PUBLIC_BASE_URL` 用于客户端更新包下载地址，测试服务器建议明确设置为内网 IP。
3. `FB_ADMIN_ORIGIN` 只用于跨域访问管理端 API；若浏览器直接访问同一个服务的 `/admin/`，不配置也可以。
4. 首次测试如需重置管理端 admin 密码，可临时追加：

```bash
FB_ADMIN_BOOTSTRAP_RESET_ENABLED=true
FB_ADMIN_BOOTSTRAP_RESET_USERNAME=admin
FB_ADMIN_BOOTSTRAP_RESET_PASSWORD=admin123
```

密码重置成功并确认可登录后，应移除上述三项并重启服务。

## 前台启动

```bash
cd /opt/floating-ball-server/app
set -a
. ./floating-ball-server.env
set +a
java -jar pcie-server-0.2.0-SNAPSHOT.jar
```

## 后台启动

```bash
cd /opt/floating-ball-server/app
set -a
. ./floating-ball-server.env
set +a
nohup java -jar pcie-server-0.2.0-SNAPSHOT.jar \
  > /opt/floating-ball-server/logs/console.log 2>&1 &
echo $! > /opt/floating-ball-server/app/floating-ball-server.pid
```

查看启动日志：

```bash
tail -f /opt/floating-ball-server/logs/console.log
tail -f /opt/floating-ball-server/logs/floating-ball-server.log
```

停止服务：

```bash
kill "$(cat /opt/floating-ball-server/app/floating-ball-server.pid)"
```

## systemd 示例

如测试服务器使用 systemd，可创建 `/etc/systemd/system/floating-ball-server.service`：

```ini
[Unit]
Description=Floating Ball Server
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/floating-ball-server/app
EnvironmentFile=/opt/floating-ball-server/app/floating-ball-server.env
ExecStart=/usr/bin/java -jar /opt/floating-ball-server/app/pcie-server-0.2.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

启动与查看状态：

```bash
sudo systemctl daemon-reload
sudo systemctl enable floating-ball-server
sudo systemctl start floating-ball-server
sudo systemctl status floating-ball-server
journalctl -u floating-ball-server -f
```

## 验证

```bash
curl -I http://127.0.0.1:8080/admin/
curl http://127.0.0.1:8080/v1/client/releases/production/policy.json
```

浏览器访问：

```text
http://服务器IP:8080/admin/
```

若端口未监听，优先检查：

1. `/opt/floating-ball-server/logs/console.log`
2. `/opt/floating-ball-server/logs/floating-ball-server.log`
3. 数据库网络、账号、schema 权限
4. `FB_AES_KEY` 是否已设置
