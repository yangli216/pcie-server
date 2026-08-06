# 全医慧助（PCIE）服务端项目 Harness

> 适用范围：全医慧助服务端 `pcie-server` 独立 Git 仓库内的后端、管理端、数据库与部署任务。
> 根目录与本仓库 `AGENTS.md` 都是硬约束；项目内细节以本仓库规则为准。

## 1. 任务入口

1. 先读 `AGENTS.md`。
2. 架构、模块、部署任务读 `ARCHITECTURE.md`。
3. `/v1/*`、鉴权、签名或字段契约任务读 `API.md`。
4. 检查 `git status --short`，保留用户已有配置和部署文件修改。
5. 涉及客户端真实调用时，核对 `../pcie/src/services/` 的生产消费方，不能只按静态需求实现。

## 2. 影响判断

| 改动 | 必查内容 |
| --- | --- |
| `/v1/*` Controller / DTO | 客户端调用、ECDSA/deviceToken、API 文档、兼容 fixture |
| Service / Mapper | 事务、错误语义、Oracle/GaussDB 方言、JUnit |
| 数据表或字段 | Oracle/GaussDB init.sql、索引/注释、目录 README、schema 测试 |
| 管理端页面 | `/admin/api/*`、公共组件、错误 requestId、`npm run build` |
| AI/语音/PMPHAI 出站 | 密钥不下发、出站安全门、超时/熔断、审计日志 |
| 发布与部署 | Maven 主版本、profile、环境变量、安装包/策略兼容 |

只要远端契约影响桌面端请求、响应或行为，就升级为 workspace 跨仓任务，补读 `../development-harness/HARNESS.md` 与客户端项目 harness。

## 3. 实施顺序

1. 先更新 `ARCHITECTURE.md` 或 `API.md`。
2. 先补 DTO/fixture/JUnit，再实现 Controller、Service、Mapper。
3. 数据库变化同步折叠进 Oracle 与 GaussDB 基线；不得长期提交 upgrade patch。
4. 管理端变化同步更新 API 封装、页面和必要公共组件。
5. 复查新增 `/v1/*` 已进入 `DeviceAuthFilter`，WebSocket 已经过签名握手拦截器；禁止明文 token 成为唯一认证。

## 4. 验证矩阵

| 影响范围 | 最小验证 |
| --- | --- |
| Java / API / DB | `mvn -f server/pom.xml test` |
| 管理端 | `npm --prefix server/src/main/admin run build` |
| Maven 完整打包 | `mvn -f server/pom.xml package` |
| 跨仓 `/v1/*` | 对应 JUnit + 客户端测试/构建 + 一条请求到结果的联调记录 |

若 Maven 内嵌 npm 安装受环境阻塞，可独立构建管理端，并使用项目规则允许的跳过 npm 参数执行 Java 测试；交付时必须明确实际命令。

## 5. 交付检查

1. 分别说明服务端 API、管理端、数据库和部署配置影响。
2. 明确桌面客户端是否需要同步升级。
3. 列出两种数据库基线、测试和构建结果。
4. 不把用户已有的 `application*.yml` 或部署配置修改计入本次成果。
