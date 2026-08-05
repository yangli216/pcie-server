# 全医慧助服务端（PCIE Server）架构说明

> 更新日期：2026-08-04

## 1. 项目定位

全医慧助服务端（PCIE Server，仓库名 `pcie-server`）是全医慧助（PCIE）桌面端当前唯一的远程业务后端。桌面端已取消本地/区域双模式，AI、语音、知识库、配置、审计和统计能力固定经本服务，第三方凭据不下发客户端。服务端承担三类职责：

1. 面向桌面端的远端客户端能力：设备注册、配置引导、Prompt / 数据包增量下发、AI 代理、审计上报
2. 面向管理员的后台管理能力：区域、机构、令牌、AI 配置、Prompt、症状模板、检验检查结果手工回写、客户端版本发布、日志、推荐偏好观测
3. 面向平台管理员的基础治理能力：管理员登录、用户、角色、概览统计

本项目不承接 PCIE 桌面端的本地 HIS 桥接，不替代桌面端 `api.md` 中的 `/api/consultation/*`。

品牌迁移只调整仓库、Maven 工程、管理端展示、Spring 服务名和新增构建产物。Java 基础包 `com.regionalai.floatingball.server`、`floating-ball.*` 配置键、`FB_*` 环境变量、数据库对象、审计编码及现有 `/data/floating-ball-server-*` 部署目录继续作为运行兼容标识保留。

## 2. 技术基线

### 2.1 后端

- Java 8
- Spring Boot 2.7.x
- MyBatis-Plus
- 数据库支持 Oracle 19c 与华为高斯 GaussDB/openGauss PostgreSQL 兼容模式；新数据库适配优先保证 GaussDB
- Oracle 运行包必须同时携带 `ojdbc8` 与 `orai18n`，以兼容 `ZHS16GBK` 等非 UTF 数据库字符集；GaussDB 使用 openGauss JDBC 驱动 `org.opengauss.Driver`
- Maven
- WebClient / RestTemplate 用于上游 AI 与语音代理

### 2.2 管理端

- Vue 2
- Element UI
- Axios
- Vue Router
- npm
- 与 Spring Boot 同仓构建、同进程发布
- 视觉风格采用医疗 IT 后台设计语言：中性浅灰工作区、深色固定导航、白色数据承载面、医疗青绿主操作、蓝/黄/红独立语义状态

## 3. 目录规划

```text
pcie-server/
├── AGENTS.md
├── ARCHITECTURE.md
├── API.md
└── server/
    ├── pom.xml
    └── src/
        ├── main/java/com/regionalai/floatingball/server/
        │   ├── FloatingBallServerApplication.java
        │   ├── common/
        │   │   ├── api/            # 统一响应
        │   │   ├── config/         # Spring 配置
        │   │   ├── exception/      # 全局异常
        │   │   └── model/          # 基础模型
        │   ├── security/           # deviceToken + ECDSA 请求签名 / adminToken 鉴权
        │   ├── modules/device/     # 注册、心跳、令牌管理
        │   ├── modules/org/        # 机构管理
        │   ├── modules/region/     # 区域管理
        │   ├── modules/config/     # AI 配置、bootstrap
        │   ├── modules/prompt/     # Prompt 发布与 delta
        │   ├── modules/symptom/    # 症状模板管理与客户端 delta
        │   ├── modules/emrtemplate/# 住院病历 HTML 模板解析缓存与 AI 字段提示词维护
        │   ├── modules/lisresult/  # 检验检查申请单待执行查询与手工结果回写模拟
        │   ├── modules/datapackage/# 映射数据包与 legacy template 包兼容
        │   ├── modules/release/    # 内网客户端版本发布与 Tauri latest.json
        │   ├── modules/audit/      # 审计事件与日志
        │   ├── modules/feedback/   # 用户反馈与调用链路聚合
        │   ├── modules/recommendationpreference/ # 机构/科室/医生推荐偏好事件与灰度重排
        │   ├── modules/userlog/    # 运维用户日志，按一次问诊聚合首版与最终内容
        │   ├── modules/businessdebug/ # 业务调试台，按业务可用节点重放核心 AI 环节
        │   ├── modules/knowledge/  # PMPHAI/知识库代理
        │   ├── modules/adminui/    # 管理端静态页面入口控制
        │   ├── modules/ai/         # chat / transcribe / realtime 代理
        │   ├── modules/analytics/  # 综合概况统计分析：趋势、分布、核心指标
        │   ├── modules/useractivity/ # 用户活跃度统计：时间/区域/机构筛选、活跃指标、用户列表
        └── main/
            ├── admin/              # 管理端 Vue 2 + Element UI 源码
            │   ├── package.json
            │   ├── vite.config.js
            │   └── src/
            └── resources/
                ├── application.yml   # 安全默认值 + 环境变量映射
                ├── mapper/
                ├── template-seeds/ # 症状模板内置基线 / 导入源
                ├── static/admin/   # 管理端构建产物落点
                └── sql/
                    ├── oracle/      # Oracle bootstrap/init scripts
                    └── gaussdb/     # GaussDB/openGauss PostgreSQL-compatible init script
```

### 3.1 运行配置安全模式

1. 仓库内提交的 `application.yml` 只保留安全默认值与环境变量映射，不再内置真实数据库地址、账号口令或 AES key。
2. 服务启动前必须注入 `FB_DB_URL`、`FB_DB_USERNAME`、`FB_DB_PASSWORD`、`FB_AES_KEY`。
3. PMPHAI 等上游服务地址在公开仓库中只保留示例地址；真实地址通过管理端配置或部署环境注入。
4. 本地联调如需私有覆盖配置，应使用未入库文件或部署环境变量，不得直接改回仓库默认值。
5. AI、语音、Reviewer、PMPHAI 等服务端出站地址必须经过统一出站安全门；当前默认开启 `allow-all-hosts`、`allow-private-network`、`allow-insecure-http` 与代理软件 fake-ip 支持，不要求维护 host 白名单，适配医院内网 HTTP 上游。
6. 如部署环境需要收紧出站边界，应显式设置 `FB_OUTBOUND_ALLOW_ALL_HOSTS=false`、`FB_OUTBOUND_ALLOW_PRIVATE_NETWORK=false`、`FB_OUTBOUND_ALLOW_INSECURE_HTTP=false`，并通过 `FB_OUTBOUND_ALLOWED_HOSTS` 指定允许访问的上游 host。
7. 出站安全门按 host 做本地限流和熔断；上游失败达到阈值后短暂拒绝同 host 后续出站，防止 AI / 语音 / PMPHAI 配置异常拖垮后台线程与连接资源。
8. 连接使用 `ZHS16GBK` 等 Oracle 非 UTF 字符集的医院库时，发布包内必须包含与 `ojdbc8` 同版本的 `orai18n` 运行时依赖；否则服务可能在启动期读取初始化数据时因 `Non supported character set` 退出，导致 8080 端口未监听。
9. 小山现场并行部署时，正式环境使用 `xiaoshan` profile，测试环境使用 `xiaoshan-test` profile；两者配置保持一致，测试环境仅把服务端口调整为 `9090`。
10. 小山现场统一使用 `scripts/publish-xiaoshan.sh` 发布，命令必须显式指定 `testing` 或 `production`；脚本内固定环境目录、profile 和端口，不允许通过环境变量覆盖这些映射。
11. 测试环境固定发布到 `/data/floating-ball-server-testing`，使用 `xiaoshan-test` profile 和 `9090` 端口；正式环境固定发布到 `/data/floating-ball-server-production`，使用目录内的 `floating-ball-server.jar`、`start.sh`、`xiaoshan` profile 和 `8080` 端口。首次切换时发布脚本允许受控停止旧 `/data/floating-ball-server.jar` 正式进程，失败时恢复旧正式服务；切换成功后后续正式发布只操作 production 目录。
12. 发布脚本先构建并校验 JAR，再上传到目标环境的临时文件；远端校验摘要、profile 配置、当前进程归属和端口归属后才停止目标服务。切换失败或健康检查失败时自动恢复该环境的上一版 JAR 和启动脚本，不得操作另一环境的 PID、JAR 或启动脚本。
13. 测试环境一键发布使用 `./scripts/publish-xiaoshan.sh testing`；正式环境使用 `./scripts/publish-xiaoshan.sh production --confirm-production`，必须携带显式正式发布确认参数；脚本会为单次发布复用临时 SSH 控制连接，密码认证场景正常只需输入一次远程服务器密码，已配置 SSH key 时无需输入密码。

### 3.2 客户端安全基线

1. `/v1/client/register` 与 `/v1/client/releases/*` 是客户端侧仅有的未签名入口；其他 `/v1/*` 必须同时校验 `deviceToken` 与 ECDSA P-256 请求签名。
2. 签名必须绑定实际请求体：服务端用收到的 body 重算 SHA-256，并用该 hash 参与验签，不信任客户端单独声明的 body hash。
3. 激活设备再次以同机构同 `cdDevice` 注册时，若该设备已有公钥和令牌，服务端要求注册请求携带原 `deviceToken` 作为同终端证明，再自动接管版本升级或本地存储变化导致的密钥轮换：更新 `device_public_key`、客户端版本和来源 IP，并返回该设备令牌；桌面端无需提示医生手工更新密钥或改用新的兜底设备编码。
4. 管理端停用设备令牌后，服务端把同机构同 `cdDevice` 的停用记录视为封禁记录；`/v1/client/register` 必须拒绝该设备编码重新领取 token，避免指定旧客户端在 401 后自动重注册绕过禁用。
5. 管理端删除设备令牌仅用于异常设备重置，会移除旧令牌、公钥和状态记录并释放同机构同 `cdDevice`；删除后客户端可以重新注册领取新令牌。封禁旧客户端时必须使用停用，不使用删除。
6. 实时语音 WebSocket 握手同样校验设备令牌、签名与客户端版本门禁；日志不得输出完整 token 或签名。
7. 所有 `ApiResponse.timestamp` 均为服务端 epoch 毫秒时间，桌面端可用它维护签名时钟偏移；服务端仍按固定时钟窗口验签，不因客户端本地时区或系统时间漂移绕过签名校验。

## 4. 后端分层

### 4.1 控制层

- `ClientController`：设备注册、心跳、bootstrap、delta、审计上报
- `AiProxyController`：聊天代理、语音转写、实时语音
- `PmphaiProxyController`：PMPHAI 搜索、详情、列表浏览、签名跳转
- `Admin*Controller`：区域、机构、令牌、配置、Prompt、症状模板、客户端版本发布、日志、用户日志

### 4.2 服务层

- 负责业务校验、配置分层查找、版本比较、上游代理转发
- 封装“机构级 > 区域级 > 全局级”的配置查找优先级
- `modules/config` 中的 AI 配置除主模型地址/密钥外，还负责托管 `chatFast` 独立模型、`enableThinking` 开关、独立审查 AI、`check_examination` 审查开关与 PMPHAI 的服务端密钥；`bootstrap` 只下发非密钥视图
- `modules/symptom` 负责症状模板的逐条 CRUD、内置模板导入、JSON 模板文件导入、作用域合并、客户端 `templates/delta` 聚合与症状模板修改日志，数据结构对齐 `floating-ball` 的 `SymptomManagement.vue` / disease editor
- `modules/emrtemplate` 负责住院病历 HTML 模板解析结果缓存，客户端按 HIS 传入的 `templateId` 复用已解析字段；缓存记录保存客户端传入的模板主键、模板名称、原生 `htmlContent`、内容 hash 和完整字段列表。管理端支持查询缓存、源码/HTML 预览模板、停用/删除缓存、手动调整字段是否由 AI 生成、维护字段 AI 生成提示词，并展示字段规则生成的默认提示词；默认提示词会包含模板名称、记录类型、字段名称、所属段落和字段含义。字段提示词覆盖和 AI 生成类型会在客户端解析缓存命中或上传模板解析结果时与本次客户端字段合并后返回，供桌面端生成住院病历预览。
- `modules/lisresult` 负责面向管理端的检验检查结果手动录入与第三方回写模拟：只查询 `hi_ods_apply` 中检验/检查类、未报告/未作废的申请单；面向 PHIS 多版本表结构差异，申请单列表、报告查看和回写前校验查询必须显式选择界面展示或逻辑处理需要的最小列，避免实体映射中的扩展字段参与运行时查询；检验录入后将报告组 ID 写回 `hi_ods_apply.id_result`，并把每个检验指标写入 `hi_ods_apply_lis_report`；检查录入后将报告 ID 写回 `hi_ods_apply.id_result`，并把报告结果、临床印象、影像诊断、阴阳性等字段写入 `hi_ods_apply_pacs_report`。该功能不接管业务系统产生申请单的链路，也不新增本地 `/api/consultation/*` 能力。
- `modules/prompt` 负责 Prompt 配置化的逐步迁移：保留桌面端 `prompts/delta` 读取链路，管理端提供 Prompt 列表、新增、编辑、发布、归档和停用；服务端内置首批语音问诊默认 Prompt，配置表存在已发布覆盖时按机构级 > 区域级 > 全局级优先级生效。
- `modules/datapackage` 继续负责映射数据包读取；`template` 类型数据包仅作为症状模板表未初始化时的兼容回退来源，管理端不再提供数据包维护入口
- `modules/recommendationpreference` 负责接收桌面端在目录匹配之后产生的诊断和医嘱标准候选选择事件，按机构/科室/医生聚合偏好分，并为灰度客户端返回带样本置信度与作用域权重的名次 boost；管理端提供只读观测页查看聚合偏好分、样本计数和原始事件，便于确认采集效果与排查上报链路。该模块不学习 AI 原始文案，不注入 Prompt，不生成新的候选项，首版管理端不提供人工编辑偏好分入口
- `modules/release` 使用服务端本地文件目录托管桌面端安装包、签名文件、`latest.json` 元数据、`policy.json` 发布策略与历史发布快照，不新增数据库表；管理端上传后由客户端通过公开 `/v1/client/releases/{channel}/latest.json` 检测更新，并通过 `/v1/client/releases/{channel}/policy.json` 判断是否必须更新

### 4.2.1 内网客户端版本发布

- 存储根目录由 `FB_RELEASE_STORAGE_DIR` / `floating-ball.release.storage-dir` 配置，默认 `${java.io.tmpdir}/floating-ball-server/releases`
- 对外更新源地址可通过 `FB_RELEASE_PUBLIC_BASE_URL` / `floating-ball.release.public-base-url` 固定指定；为空时若请求 Host 为 `localhost` / `127.0.0.1`，服务端会尽量自动替换为本机局域网 IPv4
- 上传大小由 `FB_RELEASE_MAX_FILE_SIZE` / `FB_RELEASE_MAX_REQUEST_SIZE` 控制，默认 `2048MB`
- 管理端通过 `/admin/api/releases` 查看当前发布，通过 `/admin/api/releases/upload/batch` 一次选择多个发布通道和多个 Tauri 安装包；服务端基于同一份 `latest.json` 自动解析版本号、平台 target、签名和更新说明，并分别重写为各通道内网下载地址。平台 target 不再要求运维手工填写，服务端按上传安装包文件名匹配 `latest.json.platforms.{target}.url` 自动识别；若多个 target 指向同一个安装包文件名（例如 macOS universal 包同时覆盖 `darwin-aarch64` 与 `darwin-x86_64`），批量发布会将同一上传文件展开发布到这些 target，并保留各 target 在 `latest.json` 中的签名；`/admin/api/releases/upload` 保留单通道单安装包兼容入口。上传时可勾选强制更新，服务端会把每个目标通道的当前发布版本写入对应 `policy.json` 的 `minSupportedVersion`
- 管理端“版本发布”列表展示当前安装包的公开下载地址，支持复制链接和浏览器直接打开；首次部署新客户端时可直接访问 `/client-download?channel=production` 选择平台下载安装包，无需 U 盘拷贝
- 管理端通过 `/admin/api/releases/policy` 独立开启或关闭当前通道强制更新，不需要重新上传安装包；开启时最低可用版本固定为当前通道 `latestVersion`，关闭时清空 `minSupportedVersion`
- 管理端通过 `/admin/api/releases/history` 查看历史发布快照，通过 `/admin/api/releases/rollback` 回滚到历史版本；回滚只恢复当前通道的 `latest.json` 与 `policy.json`，不会重新上传安装包
- 每次上传新版本前，服务端会先把目标通道当前发布保存为历史快照；上传后也保存新版本快照。若同一版本分平台多次上传或批量上传，服务端会合并同版本平台；若版本号变化，则每个目标通道都重新开始该版本的 `platforms` 集合，避免把上一版本平台误混入新版本 `latest.json`
- 客户端更新检测与策略查询不走设备鉴权，避免 Tauri updater 无法附带 `Authorization`；仅暴露静态安装包、Tauri 兼容元数据和强制更新策略，不暴露管理能力
- 未上传版本的通道在访问 `/v1/client/releases/{channel}/latest.json` 时返回 `204 No Content`，作为 Tauri updater 可识别的“无可用更新”状态，不走业务异常日志
- 设备业务接口通过 `DeviceAuthFilter` 统一执行强制更新拦截：`/v1/client/releases/**` 始终放行，其他 `/v1/*` 在 `forceUpdate=true` 且客户端版本低于 `minSupportedVersion` 时返回 `426 / UPDATE-REQUIRED`
- 桌面端请求头优先携带 `X-Client-Version` 与 `X-Update-Channel` 供拦截器判断；旧客户端缺失请求头时，服务端回退设备表 `client_version` 与 `production` 通道策略
- 通道固定为 `production` / `testing`，分别对应桌面端设置页中的“正式内网”与“测试内网”更新源
- 平台值需与 Tauri updater target 匹配，例如 `darwin-aarch64`、`darwin-x86_64`、`windows-x86_64`

### 4.3 数据访问层

- 使用 MyBatis-Plus `Mapper` 访问 Oracle 19c 或 GaussDB/openGauss PostgreSQL 兼容库
- `mybatis-plus.db-type` 决定分页方言，默认 `oracle`，`gaussdb` profile 默认 `opengauss`
- 运行时数据库差异集中在 `common/db/DatabaseDialect`：分页尾句、日期分组、空值函数、JSON 数值读取等不得散落硬编码
- 首期不引入复杂读写分离、缓存和消息队列

### 4.4 管理端托管约定

- 管理端页面由 Spring Boot 统一托管，默认访问入口为 `/admin/`
- 管理端构建产物输出到 `server/src/main/resources/static/admin`
- `mvn -f server/pom.xml process-resources/test/package` 会在资源阶段自动执行管理端 `npm ci` 与 `npm run build`，确保 `/admin/index.html` 与静态资源进入后端类路径
- 管理端接口继续使用 `/admin/api/*`，但页面与接口默认同源，不再依赖独立部署
- 如需单独前端调试，仍可在 `server/src/main/admin` 内运行 Vite dev server；此时后端 CORS 仅作为本地开发补充能力
- 管理端请求错误统一由 `server/src/main/admin/src/api/http.js` 归一化：业务校验展示服务端友好 `message`，网络不可达、超时、5xx、非 JSON 响应等场景转换为可操作提示；存在 `requestId` 时带出请求 ID，避免把 Axios、HTTP statusText 或后端底层异常原样弹给管理员
- 管理端 UI 采用 vue-admin 类后台骨架：深色固定侧栏、顶部导航栏、tags-view、浅灰内容区；仍避免页面副标题、说明型提示文案、列表页默认统计卡片、营销式登录首屏或 Element UI 默认蓝色主题
- 管理端表格统一使用弱分隔线、无竖线、无 `border/stripe` 网格样式；状态、编码、ID 使用 pill 或 code tag 表达
- 管理端详情/编辑弹窗默认销毁隐藏 DOM，避免隐藏表单进入可访问树；表单采用 `label-position="top"` 与分组式结构
- 管理端 UED 以医疗 IT 后台为基准：保持高信息密度、稳定导航、明确状态语义和低干扰视觉层级；主色继续使用医疗青绿 `#1D9E75`，风险/警示/停用分别使用独立语义色，避免单一色系覆盖所有状态
- 管理端所有自定义交互元素必须使用语义元素：动作使用 `button`，导航使用 `router-link`；图标按钮必须提供 `aria-label`，可点击卡片必须支持键盘焦点和回车/空格触发
- 管理端全局交互态必须包含 hover、active 与 `:focus-visible` 样式；表单占位文案统一使用中文省略号 `…`，数字/时间/编码列使用等宽或 tabular number 表达，长文本必须可折行或截断
- 管理端公共组件按 `server/src/main/admin/src/components/layout` 与 `server/src/main/admin/src/components/ui` 分层：`layout` 只承载后台壳、侧栏、顶栏、tags-view 与账号菜单，`ui` 承载筛选条、指标卡、图表面板、状态 pill、code tag、分段开关等页面无关组件
- 管理端壳层 div 分配固定为 `admin-shell -> admin-shell__sidebar + admin-shell__main -> admin-shell__topbar + admin-shell__tabs + admin-shell__content`；`AdminLayout` 只做组合编排，侧栏、顶栏、页签栏分别由 `AdminSidebar`、`AdminTopbar`、`AdminTabBar` 承载，业务页面不得重新定义外层导航/内容容器
- 管理端壳层必须提供真实可用的侧栏折叠、窄屏侧栏抽屉与顶栏快捷动作；未实现的视觉按钮不得保留在顶栏。跨页面导航入口使用 `router-link` 或等价导航语义，避免把跳转伪装成普通动作按钮
- 管理端内容区统一由 `admin-shell__content` 提供滚动和页面留白，业务页面只提供页面内的筛选、指标、图表、表格区块，避免每个页面重复定义顶层灰底、横向滚动条或额外外框
- 统计分析、活跃度、安全分析等运营看板统一使用 `MetricCard`、`ChartPanel`、`AdminFilterBar` 与 `TimeRangeFilter`，保证筛选、指标、图表标题、导出入口和移动端折行行为一致
- 服务端 Excel 导出按单元格文本长度估算列宽，不调用依赖操作系统字体的 POI 自动测宽，保证无字体的服务器环境也能完成导出
- 图表类页面必须为 loading、空数据和重绘状态提供稳定视觉反馈；ECharts resize 监听由页面统一绑定和释放，不得在每次渲染时重复追加
- 列表 CRUD 页新增状态、编码、分段开关时优先复用 `StatusPill`、`CodeTag`、`SegmentedSwitch`；确有复杂交互时可在页面内组合，但不得重新定义同义状态样式
- 远端 `/v1/*` 默认 CORS 需要兼容 `floating-ball` 的 Tauri dev / desktop WebView origin，且 `OPTIONS` 预检请求不能被设备鉴权拦截
- `floating-ball.cors.allowed-origins` 的本地配置只能做增量补充，不能覆盖掉桌面端默认 origin（`tauri://localhost`、`asset://localhost`、`https://tauri.localhost`、`http://tauri.localhost`、本地 localhost/127.0.0.1`），否则桌面端会在浏览器 Fetch 层直接报 `Load failed`

### 4.4.1 后台发布版本号

- 后台 release 版本源以 `server/pom.xml` 的 Maven 项目版本为准；管理端随 Spring Boot 同仓构建，不单独以 npm `package.json` 版本作为发布版本源。
- `maven-release-plugin` 负责正式发布：从 `*-SNAPSHOT` 切到正式 `x.y.z`、执行 `test`、创建 `v@{project.version}` 标签，再推进到下一轮 `*-SNAPSHOT` 开发版本。
- 当前 `maven-release-plugin` 配置 `pushChanges=false`，避免 release 命令自动推送远端；本地 tag 和提交确认后再人工执行 `git push && git push origin vX.Y.Z`。
- `versions-maven-plugin` 负责手工调整开发版本号，默认不生成 `pom.xml.versionsBackup`。
- 后台 Git tag 属于 `pcie-server` 独立仓库，不与桌面端客户端版本发布或内网安装包通道混用。

### 4.5 错误信息出口

- `common/exception/GlobalExceptionHandler` 是 HTTP 业务异常的统一出口。`BusinessException` 可返回明确业务原因；未知异常、数据库异常、参数解析异常必须转换为用户可理解的提示，并通过日志保留完整堆栈。
- `security/DeviceAuthFilter` 与 `AdminAuthFilter` 负责鉴权失败响应。设备签名失败响应只给出“请重新连接/重新注册设备”的操作建议，具体签名缺失、时间戳、nonce、验签异常等原因进入安全拒绝日志，不直接暴露给桌面端。
- 所有错误响应继续使用 `ApiResponse`，并带 `requestId`。前端和运维排障以 `requestId` 关联服务端日志，不依赖把技术细节展示给最终用户。

## 5. 核心业务链路

### 5.1 客户端启动链路

1. `floating-ball` 首次启动调用 `POST /v1/client/register`
2. 服务端返回 `deviceToken`
3. 客户端查询当前更新通道的 `GET /v1/client/releases/{channel}/policy.json`，若命中强制更新门禁，则仅保留检查更新与下载安装能力
4. 客户端带 `Bearer deviceToken`、`X-Client-Version` 和 `X-Update-Channel` 调用 `GET /v1/client/bootstrap`
5. 客户端按版本号调用 `prompts/templates/mappings delta`
6. 客户端周期性调用 `POST /v1/client/heartbeat`
7. 若客户端持久化的 `deviceToken` 因环境切换或服务端重建失效，客户端会清理本地注册缓存并重新执行 `register -> bootstrap`

删库重建补充约定：

1. Oracle `init.sql` 作为当前初始开发阶段的单一建库基线，直接包含 `c_ai_config` 的服务端托管字段，不依赖运行期回退；存量库执行定向升级脚本时也必须补齐 `c_ai_config.speech_realtime_url`，确保实时 WebSocket 地址与批量转写地址分离
2. `init.sql` 会预置 `REGION001`、`ORG001`、`admin` 和作用域为 `ORG001` 的默认 AI 配置，保证 `register -> bootstrap -> audit` 链路可立即联调
3. 默认 AI 配置只保证启动链路与日志链路可用；真实上游 AI 地址、密钥、模型仍需在管理端修改

模板解析补充约定：

1. `GET /v1/client/templates/delta` 优先从 `c_ai_symptom_template` 合并当前作用域可见模板，合并优先级为机构级 > 区域级 > 全局级，同 `cd_symptom` 由高优先级记录覆盖
2. 管理端“症状模板”页面复用桌面端 disease editor 的逐条编辑思路，一条症状对应一条后台记录，而不是整包 JSON 覆盖
3. 管理端支持把桌面端已有 `templates.json`、`tcm-templates.json` 或后台导出的症状模板 JSON 重新导入 `c_ai_symptom_template`
4. 若当前作用域没有任何启用的症状模板记录，则兼容回退到已发布的 legacy `template` 数据包
5. 若既没有症状模板记录，也没有 legacy `template` 数据包，则回退到 `server/src/main/resources/template-seeds/` 内置症状模板基线
6. 管理端对症状模板新增、修改、删除、内置导入和 JSON 导入写入 `c_ai_symptom_template_change_log`，记录操作者、操作时间、操作类型、模板快照和字段级差异，供审计追踪

### 5.2 AI 代理链路

1. 客户端调用 `POST /v1/ai/chat`
2. 服务端通过 `deviceToken` 找到设备所属机构/区域
3. 按优先级查找 AI 配置
4. 转发到上游 OpenAI 兼容接口
5. 把非流式 JSON 或 SSE 流式结果回传给客户端

审查模型补充约束：

1. 当 `configProfile=fast` 时，服务端优先使用 AI 配置中的独立快速模型；缺失时回退主模型配置
2. 当 `configProfile=reviewer` 时，服务端优先使用 AI 配置中的独立审查模型地址 / 密钥 / 模型；缺失时回退主模型配置
3. `enableThinking` 作为当前 AI 配置的一部分统一作用于主模型 / fast / reviewer 代理请求，最终映射为上游 OpenAI 兼容载荷中的 `enable_thinking`
4. `bootstrap` 仅向桌面端暴露 `llm.model`、`llm.fastModel`、`llm.enableThinking`、`reviewer.enabled`、`reviewer.model`、`reviewer.checkExaminationEnabled` 等非敏感字段，不返回密钥
5. `reviewer.checkExaminationEnabled` 只控制桌面端是否触发 `check_examination` 场景的独立审查，不影响诊断、用药、病历一致性等其他 reviewer 场景；默认开启以兼容旧配置
6. `/v1/ai/chat` 与 `/v1/client/bootstrap` 的实际生效模型、thinking 开关和检查项目独立审查开关以服务端当前解析到的配置为准；桌面端不应依赖本地缓存的 `model` 回传覆盖服务端配置，确保后台修改后下一次请求立即生效
7. `/v1/ai/chat` 非流式、流式和 `configProfile=reviewer/fast/default` 都必须先通过出站安全门；流式 SSE 使用有界线程池转发上游事件，线程数与队列大小由 `floating-ball.ai.stream.*` 控制，线程池满时返回 SSE 错误帧而不是继续创建线程。

语音代理补充约束：

1. `floating-ball` 的聊天录音与语音兜底批量转写通过 `/v1/ai/speech/transcribe`、`/v1/ai/speech/realtime` 上传 base64 录音内容；DashScope 与自建 FunASR 实时语音通过 `/v1/ai/speech/realtime/ws` WebSocket 逐帧代理 PCM 音频
2. 服务端必须先把 base64 录音解码为真实字节数组，再按 `speech_provider` 选择批量上游协议：`openai-compatible` 与 `funasr-websocket` 组装为 `multipart/form-data` 调用 `/audio/transcriptions`，`aliyun-dashscope` 组装为 DashScope 兼容模式 chat completion 音频请求；`funasr-websocket` 的原生协议只用于实时链路，批量兜底仍由独立 `audio_base_url` 提供
3. 对原始 PCM 录音，服务端先补 WAV 头后再上游转发，避免不同语音供应商对裸 PCM 兼容不一致
4. 服务端应保留录音元数据（`mimeType`、`format`、`fileName`、`scene`）用于排障和审计，但不在日志中落原始音频内容
5. 语音代理日志中的录音内容需要单独落为文件，默认写入 `floating-ball.audit.speech-file-dir` 指定目录；`c_ai_op_log` 只保存该录音文件路径，不把 base64 或二进制音频写入 `payload_json`
6. 服务端批量访问语音上游时使用 `audio_base_url` / `audio_model` / `audio_api_key_encrypted`；语音独立密钥为空时回退主模型 `api_key_encrypted`。实时上游独立使用 `speech_realtime_url`，不得再把 WebSocket 地址填入 `audio_base_url`
7. `speech_provider` / `speech_model` 作为 bootstrap 下发给桌面端的语音提供方与实时识别模型；`aliyun-dashscope` 默认使用 `qwen-audio-3.0-asr-flash-streaming`，通过 DashScope `/api-ws/v1/inference` 的 `run-task` 协议发送 16 kHz 单声道 PCM 二进制帧，服务端必须保留并实际上送管理员填写的兼容模型名，不得因模型名不在内置列表而静默回退默认值；`qwen3-asr-flash-realtime` 等使用 `/api-ws/v1/realtime` session 协议的模型应在保存时明确拒绝。`funasr-websocket` 使用 FunASR 原生 `2pass` 协议。FunASR 首帧固定声明 `pcm`、16 kHz、单声道采样，结束时发送 `is_speaking=false`，服务端把 `2pass-online` / `2pass-offline` / `offline` 结果归一化为桌面端既有 `text/final/error` 帧；结束请求后的 offline 帧即使携带 `is_final=false` 也必须触发最终收口
8. 批量语音转写 HTTP 出站与实时语音 WebSocket 出站都必须经过同一 host allowlist、私网拦截、限流与熔断策略；`ws` 仅在 `allow-insecure-http=true` 时允许，生产公网链路应使用 `wss`
9. 管理端语音可用性测试直接使用当前表单草稿且不保存配置：DashScope 实时测试建立 WebSocket 并等待 `task-started`，FunASR 实时测试验证握手，批量测试向实际上游发送极短静音 WAV；三类测试均复用统一出站安全门，且不得生成语音审计文件。

### 5.3 审计链路

1. 客户端本地缓存事件
2. 客户端对区域化操作日志不再依赖本地 SQLite，直接调用 `POST /v1/client/audit/events/batch`；启动时补传遗留队列，新事件入队后异步立即尝试一次，失败或离线时继续保留队列并按固定周期重试
3. 客户端对 `operation` 事件优先上报 `{ module, action, title, sourceModule, scene, result, operationType, operationName, details }`，并在事件外层携带事件产生时的 `hisOrgId/hisOrgName`；其中 `module/action/title/sourceModule/scene/result` 与 HIS 机构会被服务端提取到结构化列，`operationType/operationName/details` 继续保留在原始 payload
4. AI 调用类 `operation` 事件必须同时保留摘要与完整出入参：`details.requestSummary/responseSummary` 用于列表摘要，`details.requestPayload/responsePayload` 用于详情排障。`requestPayload` 应记录实际发送给 `/v1/ai/chat` 或语音代理的业务请求体；`responsePayload` 应记录业务回文或错误对象。API Key、Bearer Token 等凭据不得进入 payload；语音原始 base64 / 二进制音频不得进入 payload。
5. 客户端本地只保留轻量失败重试队列，不再把区域化操作日志落本地 SQLite；服务端仍按同一批量接口落库
6. 服务端兼容旧载荷：若未显式提供 `module/action/title/sourceModule/scene/result`，则回退从 `operationType/operationName/success` 与 `details.traceId / details.consultationId` 等字段推导
7. 服务端写入 `c_ai_op_log`；`id_org` 继续来自设备鉴权，`id_his_org/na_his_org` 来自桌面端 SDK handshake 上下文，两者不得互相覆盖
8. 管理端提供分页查询，并支持按 `module/action/title/sourceModule/scene/traceId/consultationId/result` 结构化筛选；详情弹窗必须把完整入参、完整出参与原始 payload 分区展示，不能只展示摘要字段。JSON 默认使用可折叠的结构化视图，完整 JSON 字符串字段递归解析为对象或数组，普通长文本按真实换行折行展示；超大内容的结构化视图使用全树节点预算并在工具栏提示截断，原文仍保留完整内容供查看和复制。
9. `c_ai_op_log` 是审计事实源，只回答“发生过哪些技术/业务操作、链路如何排障”，不得直接作为辅诊功能调用次数统计源
10. 服务端自身产生的成功代理日志必须可靠落库；若成功调用上游后审计日志写入失败，接口最终返回业务失败。失败代理调用的补充审计日志写入失败时必须 error 级别记录完整异常，保留原始业务失败语义。

代理日志补充约束：

1. 服务端对 `/v1/ai/chat`、`/v1/ai/speech/*` 等上游代理请求，除了成功/失败结果外，还应在 `payload_json` 中保留请求元数据、实际上游请求体与上游回文，便于排障；桌面端 AI trace 生成的操作日志也必须在 `details.requestPayload/responsePayload` 中保留完整业务出入参。
2. `speech_proxy` 日志的原始录音不得写入 `payload_json`；录音文件单独落盘，表中通过 `audio_file_path` 指向对应文件
3. API Key、Bearer Token 等凭据不得入库；除此之外，业务请求正文与回文可按原文保留
4. 管理端日志页应支持按 `ai_proxy`、`speech_proxy` 等代理日志类型筛选与查看详情

### 5.4 功能调用事件链路

1. 功能调用事件是面向统计的业务事实源，独立于审计日志和问诊用户日志。
2. 桌面端在用户真实触发功能时调用 `POST /v1/client/feature-events/batch`，一次明确功能调用只提交一条事件。
3. 服务端以 `idDevice + idempotencyKey` 幂等入库到 `c_ai_feature_event`，客户端离线重试或接口重试不会重复计数；事件同时保存独立的 `id_his_org/na_his_org`，用于 HIS 机构统计，后台 `id_org/id_region` 仍由设备鉴权决定。
4. 事件固定使用 `featureCode` 表示产品功能，服务端统一映射展示名：语音问诊、智能问诊、报告单解读、聊天、AI诊断鉴别、AI推荐诊断、AI推荐用药、AI推荐检查、AI推荐检验、AI推荐处置、AI推荐治疗方案、知识库使用。
5. `traceId`、`consultationId`、`sessionId` 只用于把功能事件关联回 `c_ai_op_log` 或 `c_ai_user_consultation_log`，不参与统计去重。
6. 统计口径按用户显式功能入口统一：智能问诊、语音问诊、报告单解读、聊天、知识库使用按主功能入口计数；知识库批量检索只按一次用户检索动作计数，不按内部拆开的多个查询词累加；诊断鉴别和推荐诊断/用药/检查/检验/处置/诊疗方案推荐只统计医生显式触发的独立辅助入口，不统计智能问诊或语音问诊主流程内部自动生成的 AI trace。来自 HIS Bridge 的入口在桌面端接诊上下文校验通过并准备打开目标界面时即按成功调用入库；同一就诊再次显式触发入口按新调用计数，只有同一条已入队功能事件的离线重试或接口重试通过自身 `idempotencyKey` 去重。
7. 管理端“辅诊功能”统计只读 `c_ai_feature_event`；`c_ai_op_log` 保留为排障与审计，不再承担统计推断。
8. 功能事件只把重复幂等上报计入 `skipped`；不支持的 `featureCode`、缺失 `idempotencyKey`、不可序列化的 `payload` 必须计入 `rejected` 并返回拒绝明细，避免统计漏数被静默掩盖。

### 5.5 用户反馈链路

1. 桌面端医生可提交“评分 + 说明 + 截图”反馈。
2. 反馈请求携带最近一次 AI 代理调用的 `traceId`、会话 ID、来源模块、请求/响应摘要等链路上下文。
3. 服务端保存反馈主体与截图数据，并按 `traceId` 优先、`sessionId + 时间窗口` 兜底聚合相关 `c_ai_op_log`。
4. 服务端对带有 `chainContext.feedbackScopeKey` 的反馈采用“保留历史修订 + 最新版标记”策略：同一设备、同一问诊槽位的新反馈会保留旧记录，但旧记录转为历史版本，仅最新版本参与默认列表与统计。
5. 管理端“反馈管理”页面同时展示反馈内容、截图预览、上下文快照和调用链路时间线，便于排障；列表默认只显示最新版本，按需可展开历史修订。

### 5.6 运维用户日志链路

1. 用户日志是面向运维人员的问诊聚合视图，独立于 `modules/audit` 的原始操作日志，不复用 `/admin/api/logs` 与 `c_ai_op_log`。
2. 桌面端语音问诊停止录音后先上报 `speechText` 与录音 base64；服务端将录音文件落到 `floating-ball.audit.speech-file-dir`，表内仅保存 `audio_file_path`、原文件名、MIME 和大小，避免把原始音频写入 JSON。
3. 桌面端在智能问诊、语音问诊产生首版 AI 内容时上报 `firstSnapshot`；医生最终完成回写/提交或放弃时上报 `finalSnapshot`、`selectionSnapshot` 与结束状态。
4. 服务端按 `consultationId + consultationType + idDevice` 只聚合同一轮尚未结束的问诊日志；记录进入 `completed` 或 `abandoned` 后，同一就诊再次发起智能问诊/语音问诊必须创建新记录，保证“同一病人多次问诊多条记录”，且不记录医生每次中间编辑。
5. 服务端在写入最终快照时同步计算 `change_summary_json` 与 `total_changes`，供统计分析计算诊断符合率与用户日志变更筛选使用。
6. 管理端新增“用户日志”模块，列表列为机构、医生、问诊时间、问诊病人、问诊类型、操作；详情对比展示首版生成内容与最终修改内容，最终内容中发生变化的字段按 diff 样式显示为“原文字删除线 + 修改后文字”，病例正文统一包含主诉、现病史、既往史、个人史、家族史、体格检查、注意事项，并继续展示诊断、用药、检查、检验、处置和用药/项目选中状态，同时支持播放语音问诊录音与查看 ASR 识别文字。

### 5.7 安全拒绝日志链路

1. `DeviceAuthFilter` 与 `RealtimeSpeechHandshakeInterceptor` 对设备令牌、ECDSA P-256 请求签名、强制更新门禁等失败场景写入 `c_security_rejection_log`。
2. 安全拒绝日志记录拒绝类型、请求方法、路径、客户端 IP、设备、机构、请求 ID、拒绝原因、签名头、客户端版本和更新通道；敏感 token 与完整签名不入库。
3. 管理端“安全拦截”列表、“安全分析”概览/趋势/分布统一读取 `c_security_rejection_log`，不从普通审计日志或 HTTP 异常日志反推安全事件。

### 5.8 业务调试台

业务调试台面向“核心业务环节可重放”，不依赖历史 AI trace 拼装虚拟工作流。管理端从真实业务记录加载上下文，再由开发人员选择要调试的可用业务节点。

首版支持语音接诊场景：

1. 数据锚点：从 `c_ai_user_consultation_log` 选择一次语音接诊记录，读取原始 ASR 文本、患者/医生/机构上下文、首版/最终病历快照等业务上下文。
2. 可用节点目录：语音文本校准、病历生成、诊断推荐、治疗方案推荐、检查推荐、检验推荐、处置推荐、安全复核。节点目录先以服务端代码维护，避免过早引入流程编排表；后续需要保存实验模板或跨场景编排时再表化。
3. 入参来源：每个节点允许手工编辑 `currentInput` 和 `upstreamOutput`，管理端提供“原始语音文本、上游最后结果、全部上游结果、首版病历、最终病历”等快捷载入。
4. Prompt 调优：节点按 `promptCode` 解析当前机构/区域生效 Prompt，回退服务端内置默认 Prompt；管理员可在页面内临时修改 system/user prompt 后重放，不影响已发布配置。
5. 执行结果：调试结果只保存在当前页面链路中，并通过服务端 AI 代理写入普通审计日志用于排障；不依赖历史 `c_ai_op_log` 作为节点来源，也不改写业务病历结果。
6. `/v1/ai/chat` 请求允许携带可选 `consultationId`，服务端与客户端审计日志把该字段落入 `c_ai_op_log.consultation_id`，作为普通调用排障的业务锚点。
7. 调试重放必须走管理端鉴权，并复用原设备所属机构/区域的服务端 AI 配置；不得要求管理员输入或查看 API Key。

### 5.4 PMPHAI 知识库代理链路

1. 桌面端调用 `/v1/knowledge/pmphai/*`
2. 服务端按设备所属机构/区域解析当前可见 AI 配置
3. 服务端使用配置中的 PMPHAI `appKey/appSecret/baseUrl` 向上游申请 token 或生成签名 URL
4. 服务端把搜索结果、详情内容、列表浏览结果或页面 URL 返回给桌面端

约束：

1. PMPHAI 的 `appKey/appSecret` 只保留在服务端数据库或环境中，不能下发到桌面端
2. 桌面端不再包含 `src-tauri/src/http_server.rs` 的本地 `/api/pmphai/*` 代理；服务端是 PMPHAI 的唯一生产出口
3. PMPHAI `baseUrl` 生成页面跳转 URL 前也要经过出站安全门校验，搜索、详情、列表、token 申请等真实出站请求还要命中限流和熔断；配置为私网、localhost、未允许 host 或非安全协议时直接拒绝。

## 6. MVP 范围

### 6.1 第一阶段

1. `/v1/client/register`
2. `/v1/client/heartbeat`
3. `/v1/client/bootstrap`
4. `/v1/client/prompts/delta`
5. `/v1/client/templates/delta`
6. `/v1/client/mappings/delta`
7. `/v1/ai/chat`
8. `/v1/ai/speech/transcribe`
9. `/v1/client/audit/events/batch`
10. `/v1/client/user-logs/consultations`
11. `/v1/client/feature-events/batch`
12. `/v1/knowledge/pmphai/search`
13. `/v1/knowledge/pmphai/clip`
14. `/v1/knowledge/pmphai/list`
15. `/v1/knowledge/pmphai/page-url`
16. 管理端最小 CRUD：
   - 完整 CRUD：区域、机构、令牌、AI 配置、Prompt、症状模板
   - 查询增强：日志

### 6.2 第二阶段

1. 管理员登录与登录态校验
2. 用户、角色最小 CRUD
3. 概览统计与首页看板
4. 更细粒度权限和审计模型

### 6.3 当前实现假设

本轮按“最小闭环”推进第二阶段，只实现以下范围：

1. 轻量管理员鉴权：
   - `/admin/api/auth/login`
   - `/admin/api/auth/me`
   - `/admin/api/auth/logout`
   - `/admin/api/auth/password`
   - 管理端 `Bearer token` 鉴权
   - 管理端页面入口 `/admin/`
   - 支持“登录后自助修改密码”与“启动期受控重置管理员密码”
2. 用户管理：
   - 用户分页查询、新增、修改、停用
   - 用户与机构、角色关联
3. 角色管理：
   - 角色分页查询、新增、修改、停用
4. 概览统计：
   - 首页汇总区域、机构、令牌、配置、Prompt、症状模板、日志、用户、角色数量
5. 综合概况统计分析（`modules/analytics`）：
   - 核心指标卡片：功能调用总量、日均功能调用量、AI诊断建议采纳率、诊断符合率、活跃医生数、问诊总数
   - 服务趋势折线图：按日聚合功能调用量与问诊量趋势
   - 机构分布柱状图：Top 10 机构功能调用量
   - 区域分布饼图：各区县功能调用量占比
   - 问诊机构分布柱状图：按 `c_ai_user_consultation_log.id_his_org` 聚合的 Top 10 HIS 机构问诊量
   - 问诊区域分布饼图：问诊日志所属后台机构关联区域后的各区县问诊量占比
   - 功能调用量统一按 `c_ai_feature_event` 中成功的用户实际功能调用事件计数，不再按 `c_ai_op_log` 的 AI 代理日志行数计数
   - 支持时间范围快捷切换（今日/本周/本月/本季度/本年/自定义）
   - 统计分析、辅诊功能和用户活跃度三个页面仅展示区域与 HIS 机构下拉筛选，不展示平台机构筛选；HIS 机构使用业务事实中的 `id_his_org`。服务端仍保留 `idOrg` 作为后台/API 兼容筛选及授权、配置范围，来源于设备鉴权，与 `hisOrgId` 独立且互不替代
   - 区域下拉只展示 `fg_active='1' AND sd_status='1'` 的区域；HIS 机构选项从功能事件和问诊日志的非空 `id_his_org` 汇总，不要求在 `c_ai_org` 建立同值主键。兼容调用方显式传入 `idOrg` 时，平台机构仍须启用并归属所选区域，否则查询返回空结果
   - 支持统计分析、辅诊功能和用户活跃度 Excel 数据导出
   - “辅诊功能”统计必须按产品功能维度归并，不直接展示底层 AI 操作名或审计来源模块名；服务端只基于 `c_ai_feature_event` 的实际用户功能调用事件统计，统一归类为语音问诊、智能问诊、报告单解读、聊天、AI诊断鉴别、AI推荐诊断、AI推荐用药、AI推荐检查、AI推荐检验、AI推荐处置、AI推荐治疗方案、知识库使用；主流程内部自动 AI 推荐不重复拆分为子功能次数，知识库批量检索也不按内部多个查询词重复计数
   - 综合统计中的功能调用指标和机构分布、辅诊功能统计按 `c_ai_feature_event.id_his_org` 过滤；问诊指标按 `c_ai_user_consultation_log.id_his_org` 过滤。用户活跃度选定 HIS 机构后，只把历史上在该 HIS 机构产生过问诊的设备纳入分母，再按所选时段是否存在该机构问诊区分活跃与不活跃

约束：

1. 不引入 Redis、第三方 JWT 组件或复杂会话中心。
2. token 采用服务端轻量签发与校验，满足当前单体后台使用。
3. 统计首期直接基于现有业务表聚合，不新增统计中间表。

### 6.4 管理员密码维护约定

管理员密码采用双通道维护，分别解决日常改密与无法登录时的恢复场景：

1. 登录后自助修改：
   - 当前管理员登录后，可在管理端页头进入“修改密码”
   - 后端通过 `/admin/api/auth/password` 校验旧密码后更新 `c_ai_user.password_hash`
2. 启动期受控重置：
   - 服务启动时可读取 `floating-ball.admin.bootstrap-reset.*` 配置
   - 当 `enabled=true` 且提供了目标账号、新密码时，服务会在启动阶段把对应管理员密码重置为新值
   - 该机制只用于忘记密码或初始化恢复，不提供公开匿名 HTTP 重置接口

## 7. 数据模型基线

首期表按 PRD 和当前桌面端契约收敛，至少包括：

1. `c_ai_region`
   - `sd_status` 表示启用/停用，管理端启停只修改该字段；`fg_active` 仅表示逻辑删除/工程无效记录
2. `c_ai_org`
   - `cd_org` 必填，是桌面端 `/v1/client/register` 使用的稳定机构编码；激活机构通过 `uk_c_ai_org_code_active` 保证 `cd_org` 唯一
   - `sd_status` 表示启用/停用，管理端启停只修改该字段；`fg_active` 仅表示逻辑删除/工程无效记录
3. `c_ai_device`
   - 激活设备通过 `uk_c_ai_device_code_org_active` 保证同机构内 `cd_device` 唯一，通过 `uk_c_ai_device_token_active` 保证设备令牌唯一
   - `device_public_key` 保存桌面端注册时上传的 ECDSA P-256 SPKI 公钥，供后续 `/v1/*` 请求签名验签使用；激活设备同机构同 `cd_device` 再次注册时，若请求携带匹配的原 `device_token`，可由服务端更新该公钥，用于版本升级或本地密钥重建后的自动接管
   - 管理端停用令牌会把原记录置为 `fg_active='0'` 且 `sd_status='0'`，该同机构同 `cd_device` 历史记录用于表达后台停用/封禁，注册接口必须拒绝其重新领取 token；若管理员需要恢复该设备，应先新增同 `cd_device` 的激活令牌占位，再由客户端注册补录公钥
   - 管理端删除令牌是异常设备重置操作，会物理移除该设备记录并释放同机构同 `cd_device`；删除后客户端重新注册会生成新的 `id_device`、`device_token` 与公钥绑定
   - `register_ip` 记录注册请求来源 IP，`last_seen_ip` 随注册和心跳刷新；两者用于后台定位旧客户端、异常终端和网段，不参与设备身份认证或签名校验
   - 管理端令牌列表的“用户姓名”不写入设备表，而是按当前页设备批量读取 `c_ai_user_consultation_log` 中最近一次非空 `na_doctor`；未产生问诊记录的设备不显示姓名
4. `c_ai_config`
5. `c_ai_prompt`
6. `c_ai_data_package`
7. `c_ai_symptom_template`
8. `c_ai_symptom_template_change_log`
   - 记录症状模板新增、修改、删除、内置导入和 JSON 导入
   - 关键列包括模板 ID / Key / 名称 / 医学模式 / 作用域、操作者 ID / 账号 / 姓名、操作类型、操作时间、变更摘要
   - `before_json`、`after_json` 保存变更前后完整模板视图，`diff_json` 保存字段级差异
   - 仅作为症状模板审计追踪，不参与客户端 `templates/delta` 下发
9. `c_ai_op_log`
   - 代理日志主体仍保存在 `payload_json`
   - 同步冗余结构化列：`op_action`、`op_title`、`source_module`、`scene_code`、`trace_id`、`consultation_id`、`id_his_org`、`na_his_org`
   - 语音代理的录音文件路径保存在 `audio_file_path`
   - `id_org` 保存后台机构，`id_his_org/na_his_org` 保存事件产生时的 HIS 机构上下文；用于排障、审计和反馈关联，不作为辅诊功能调用次数统计源
10. `c_ai_feedback`
   - 统一存储四类反馈：`general`（设置入口）、`recommendation`（语音推荐）、`record_field`（语音病例字段）、`session`（语音整页评分）
   - 关键扩展列：`kind` / `severity` / `tags_json`（标签数组 JSON）/ `has_correction`（是否包含医生修正）/ `has_trace`
   - 反馈人身份列：`id_doctor` / `na_doctor` / `id_dept` / `na_dept` / `na_org`；`id_org` 仍来自设备鉴权解析出的后台机构 ID，`na_org` 取 SDK handshake `urt.orgPureName`，`id_dept` 取 `urt.userRoleDepts.deptId`
   - 索引：`idx_c_ai_feedback_kind` / `_doctor` / `_dept`，并通过 `uk_c_ai_feedback_latest_scope` 保证同一设备、同一 `feedback_scope_key` 只有一条激活的最新版反馈
11. `c_ai_user_consultation_log`
   - 按一次问诊聚合运维用户日志，关键列包括后台机构、HIS 机构 ID、医生、患者、问诊类型、问诊时间
   - `id_org` 保存设备鉴权得到的后台机构 ID；`id_his_org` 保存桌面端从 SDK handshake `urt.userRoleDepts.orgId` 上报的 HIS 机构 ID，不参与后台机构级配置解析；`na_org` 来自 `urt.orgPureName`，`id_dept` 来自 `urt.userRoleDepts.deptId`
   - `first_snapshot_json` 保存 AI 首次生成内容，`final_snapshot_json` 保存医生最终修改后内容，`selection_json` 保存诊断/用药/检查/检验最终选中状态
   - `speech_text` 保存语音问诊 ASR 识别文字；`audio_file_path` / `audio_file_name` / `audio_mime_type` / `audio_size` 保存录音文件引用和元数据
   - `change_summary_json` 保存主诉、现病史、诊断、用药、检查、检验、处置和选中状态等类别变更计数，`total_changes` 保存总变更数，统计分析诊断符合率依赖其中的 `diagnosisChanges`
   - 索引：`idx_c_ai_user_log_time` / `_patient` / `_doctor` / `_consultation` / `_round`，并通过 `uk_c_ai_user_log_round_active` 保证激活且尚未结束的 `consultation_round_id` 只有一条，已回写/放弃后同一就诊可再次生成新日志
12. `c_ai_feature_event`
   - 按用户真实功能调用记录统计事件，关键列包括 `feature_code`、`feature_name`、`event_action`、`idempotency_key`、`trace_id`、`consultation_id`、`session_id`、医生、后台机构、HIS 机构、事件时间
   - 通过 `id_device + idempotency_key` 保证同一设备的同一功能调用只计一次
   - 索引：`idx_c_ai_feature_event_time` / `_feature` / `_doctor` / `_org` / `_idem`
13. `c_security_rejection_log`
   - 记录设备鉴权、请求签名、强制更新门禁和实时语音握手等安全拒绝事件
   - 关键列包括 `rejection_type`、`request_method`、`request_path`、`client_ip`、`id_device`、`cd_device`、`id_org`、`request_id`、`reject_reason`、`reject_detail`、`has_signature`、`timestamp_header`、`nonce_header`、`client_version`、`update_channel`
   - 索引：`idx_c_security_rej_time` / `_type` / `_ip` / `_device` / `_path`
14. `c_ai_user`
   - 激活账号通过 `uk_c_ai_user_code_active` 保证 `cd_user` 唯一，用户资料与角色映射替换在同一事务内完成
   - `sd_status` 表示启用/停用，管理端启停只修改该字段；`fg_active` 仅表示逻辑作废，不作为日常启停入口
15. `c_ai_role`
   - 激活角色通过 `uk_c_ai_role_code_active` 保证 `cd_role` 唯一
16. `c_ai_user_role`
   - 激活映射通过 `uk_c_ai_user_role_active` 保证 `id_user + id_role` 不重复

扩展表如用户、角色、统计可在第二阶段补齐。

### 7.1 写入一致性与唯一性约束

多步业务写入必须以 Spring 事务作为边界，并以数据库唯一索引作为并发最终防线：

1. 用户新增、修改、停用：`c_ai_user` 与 `c_ai_user_role` 同事务提交或回滚；账号唯一性不只依赖代码检查。
2. 机构维护、角色停用、设备注册/维护：涉及主表和关联状态变化时同事务提交；机构编码必填且激活记录唯一，机构编码、设备编码、设备令牌、角色编码由数据库唯一索引兜底。
3. 反馈提交：上一版 `fg_latest` 降级、新版插入、首版 root 回填必须同事务完成；`uk_c_ai_feedback_latest_scope` 防止并发提交产生两个最新版。
4. 问诊日志保存：按 `consultation_id + consultation_type + id_device` 只对尚未结束的 `generated` 记录做事务化 upsert；`completed` / `abandoned` 记录保留为历史轮次，同一就诊再次生成时插入新记录。并发首次创建由唯一索引兜底，服务端在唯一冲突后重读未结束记录并重试一次更新。
5. 现场旧库一次性迁移添加唯一约束前必须先清理重复激活数据；迁移脚本应在发现重复时中止并提示具体对象。

## 8. 数据库初始化约定

本项目保留 Oracle 与 GaussDB/openGauss 两套初始化基线：

Oracle 初始化拆成两步：

1. `server/src/main/resources/sql/oracle/bootstrap.sql`
   - 由 `SYSTEM` 或具备 DBA 权限的账号执行
   - 负责创建业务表空间；在使用独立 schema 时，再创建业务 schema/user 并授予最小必需权限
2. `server/src/main/resources/sql/oracle/init.sql`
   - 由当前应用连接账号登录后执行
   - 负责创建业务表、索引和种子数据
   - 不显式声明 `TABLESPACE`，由执行前切换好的 schema/默认表空间决定对象落点
   - 作为工程交付的唯一业务初始化基线；历史补丁已折叠进该文件，仓库不再长期保留 `upgrade_*.sql`

说明：

1. 本项目不在业务初始化脚本中执行 `CREATE DATABASE`。Oracle 一般复用现有实例/服务，应用侧只负责 schema 层初始化。
2. 当前默认连接账号为 `SYSTEM`，因此 `bootstrap.sql` 会先建表空间、再跳过建用户步骤；`init.sql` 只保留建表语句，由执行前切换好的 schema/默认表空间决定对象落点。正式环境仍建议切回独立业务 schema。

GaussDB/openGauss 初始化：

1. `server/src/main/resources/sql/gaussdb/init.sql`
   - 面向华为高斯 PostgreSQL 兼容模式与 openGauss
   - 不提供 Oracle 风格 `bootstrap.sql`；目标 database / schema / user 由 DBA 按现场规范预先创建
   - 由当前应用连接账号登录目标 schema 后执行
   - 与 Oracle `init.sql` 对齐业务表、索引、注释和默认种子数据
2. 推荐运行配置使用 `SPRING_PROFILES_ACTIVE=gaussdb`，默认 driver 为 `org.opengauss.Driver`，默认 `mybatis-plus.db-type=opengauss`
3. `gaussdb` profile 默认日志目录为 `/opt/floating-ball-server/logs`，发布包与语音审计文件默认落在 `/opt/floating-ball-server/data/*`，可通过 `FB_LOG_PATH`、`FB_RELEASE_STORAGE_DIR`、`FB_AUDIT_SPEECH_FILE_DIR` 覆盖；服务器 `java -jar` 测试部署说明见 `server/src/main/resources/sql/gaussdb/DEPLOY.md`
4. openGauss JDBC 驱动与 PostgreSQL JDBC 驱动同 JVM 混用存在类名空间冲突风险；当前交付优先携带 openGauss 驱动，普通 PostgreSQL 作为 PG 兼容 SQL 的次级适配方向

存量库 HIS 机构统计与问诊轮次补齐：

1. 本次按明确交付要求，在 Oracle、GaussDB 与达梦数据库目录分别保留 `update_his_org_statistics.sql`，承载 HIS 机构结构化字段、索引、可确定关联的历史数据回填，以及现场库遗漏的问诊轮次字段和索引。
2. 升级文件同时补充历史基线中已经声明、但部分现场库遗漏的 `c_ai_user_consultation_log.id_his_org`、`consultation_round_id`、`idx_c_ai_user_log_round`、`uk_c_ai_user_log_round_active`，以及本次新增的操作日志和功能事件 HIS 机构列。
3. 新建库仍只执行对应 `init.sql`；存量库执行升级文件前必须先备份，并由 DBA 确认目标 schema。升级脚本使用各数据库方言的存在性判断，允许已包含部分字段或索引的现场库重复执行；若现场库已存在重复的激活 `generated` 轮次，必须先确认并清理重复数据，再创建 `uk_c_ai_user_log_round_active`，脚本不得静默修改业务记录。

## 9. 单体部署约定

本轮管理端改造采用“单体托管，不重写页面”的策略：

1. 保留现有 Vue 2 + Element UI 管理端实现，避免为“取消前后端分离”而重写成服务端模板。
2. 管理端源码迁入 `server` 模块，由同一套 Maven / Spring Boot 工程承载。
3. 发布结果为单个 Spring Boot 服务，前后端同端口、同域名、同进程。
4. 开发阶段允许保留 Vite 独立调试能力，但这不再是默认交付形态。
5. 管理端表单交互优先复用 `floating-ball` 现有设置页的分组式信息架构：按“基础信息 / 主模型 / 语音 / 知识库 / 审查模型 / 作用域与功能开关”分段展示，避免把所有配置字段平铺在单一网格中。
6. 语音配置页必须区分“服务端实际转写地址/密钥/模型”和“桌面端感知的 provider/model”：前者用于服务端上游语音协议选择与调用，后者用于 `floating-ball` 区域化设置页展示与策略选择。
7. 服务端 AI / 语音上游出站请求允许通过 `floating-ball.ai.proxy.*` 配置显式走 HTTP 代理；在 macOS 开发环境下同时建议启用 Netty 的 native DNS 解析依赖，避免 Java 进程与终端 `curl` 的网络行为不一致。
8. AI 配置页应提供“服务端到上游 LLM”的单独测试入口，用于区分“floating-ball -> server”链路故障与“server -> LLM”链路故障。
