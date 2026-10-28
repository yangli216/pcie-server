# 全医慧助服务端（PCIE Server）架构说明

## WeKnora 机构知识服务（2026-09-30）

`modules/knowledge` 增加 KnowledgeCatalogService（批准清单/机构/有效期/版本）、KnowledgeRetrievalService（统一证据映射）及 WeKnoraKnowledgeProvider（v0.8.2独立hybrid-search）。只对服务端批准的文档/片段检索并校验原文UTF-8 SHA-256，禁止父块/邻块扩展；不依赖WeKnora生成回答或沙箱，不改变公卫生成与PMPHAI。KnowledgeRetrievalProperties托管连接配置，专用HTTP客户端固定超时、禁重定向/重试、复用出站限流熔断，响应有大小上限。配置与部署见 [知识服务设计](../development-harness/KNOWLEDGE_BASE_DESIGN.md) 和 [接入说明](deploy/weknora/README.md)，无新增业务表。

`deploy/weknora/compose.local.yaml` 管理独立本机知识服务环境（固定 v0.8.2 UI/App/Docreader、ParadeDB 和 Redis），与 PCIE Server 的 Oracle/GaussDB/DM8 及启动流程分离。UI/API 仅绑定环回8091/8092，内部服务不发布端口；新数据使用本项目专用持久卷，随机密钥保存在Git忽略且权限0600的 `.local/runtime.env`。准备工具不修改现有服务配置，也不自动创建机构批准清单或启用检索；模型、资料和签名接入仍须分别配置验证。

Provider 在供应商边界处理 v0.8.2 的整数匹配枚举，向桌面继续返回字符串 `matchType`；合法整数按官方枚举转换，既有文本标识兼容，未知或非法类型拒绝为脱敏上游错误。正文和批准清单校验不随标签转换改变。



> 更新日期：2026-09-07

## 1. 项目定位

全医慧助服务端（PCIE Server，仓库名 `pcie-server`）是全医慧助（PCIE）桌面端当前唯一的远程业务后端。桌面端已取消本地/区域双模式，AI、语音、知识库、配置、审计和统计能力固定经本服务，第三方凭据不下发客户端。服务端承担三类职责：

1. 面向桌面端的远端客户端能力：设备注册、配置引导、Prompt / 数据包增量下发、AI 代理、审计上报
2. 面向管理员的后台管理能力：区域、机构、令牌、AI 配置、Prompt、症状模板、检验检查结果手工回写、客户端版本发布、日志、推荐偏好观测
3. 面向平台管理员的基础治理能力：管理员登录、用户、角色、概览统计；可选接入 BBP/PHIS 统一账号认证与机构、人员目录

本项目不承接 PCIE 桌面端的本地 HIS 桥接，不替代桌面端 `api.md` 中的 `/api/consultation/*`。

品牌迁移只调整仓库、Maven 工程、管理端展示、Spring 服务名和新增构建产物。Java 基础包 `com.regionalai.floatingball.server`、`floating-ball.*` 配置键、`FB_*` 环境变量、数据库对象、审计编码及现有 `/data/floating-ball-server-*` 部署目录继续作为运行兼容标识保留。

## 2. 技术基线

门诊质控列表暂不提供科室筛选，保留开立医生筛选；病历列表和详情仍显示 PHIS 返回的科室。旧接口中的科室参数不再支持，非空时明确拒绝，避免跨页筛选出现不完整结果。

门诊质控评分 v6：病历问题全部由 AI 判断，服务端按每维度有效扣分之和计算得分（最多扣至该维度零分），不注入固定规则发现。人工调整问题或维度时采用同一口径；维度直接调整通过明确标注的人工扣分明细表达。旧规则结果需重新质控后才能人工调整。保存人工调整时必须比较打开时的分析版本，并重新核对 PHIS 正文及更新时间；冲突返回 409。AI 出站文本单独加强自由文本身份信息脱敏。

### 2.1 后端

- Java 8
- Spring Boot 2.7.x
- MyBatis-Plus
- Spring Boot Actuator（仅用于健康检查与功能事件 schema readiness）
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
        │   ├── modules/emrtemplate/# 住院模板缓存，以及门诊模板原文与解析快照审计
        │   ├── modules/phisemr/    # PHIS 门诊病历只读代理：管理员 BBP 会话复用、接口白名单、列表/详情归一化
        │   ├── modules/emrqc/      # 门诊病历 AI 质控：规则检查、脱敏、模型评分、发现与批量任务
        │   ├── modules/lisresult/  # 检验检查申请单待执行查询与手工结果回写模拟
        │   ├── modules/datapackage/# 映射数据包与 legacy template 包兼容
        │   ├── modules/release/    # 内网客户端版本发布与 Tauri latest.json
        │   ├── modules/audit/      # 审计事件与日志
        │   ├── modules/feedback/   # 用户反馈与调用链路聚合
        │   ├── modules/recommendationpreference/ # 机构/科室/医生推荐偏好事件与灰度重排
        │   ├── modules/inpatientlog/ # 住院病历生成轮次、快照、回填结果与事件历史
        │   ├── modules/assistantlog/ # 独立医学助手对话审计查询、全文详情与 CSV 导出
        │   ├── modules/userlog/    # 运维用户日志，按一次问诊聚合首版与最终内容
        │   ├── modules/businessdebug/ # 业务调试台，按业务可用节点重放核心 AI 环节
        │   ├── modules/knowledge/  # PMPHAI 代理、MySQL 药品说明书只读查询
        │   ├── modules/adminui/    # 管理端静态页面入口控制
        │   ├── modules/ai/         # chat / transcribe / realtime 代理
        │   ├── modules/analytics/  # 综合概况统计分析：趋势、分布、核心指标
        │   ├── modules/useractivity/ # 用户活跃度统计：按医生合并多设备的活跃指标与明细
        │   ├── modules/clientusage/ # 医生客户端使用情况：当前版本首次交互、最近活跃、全字段导出
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
9. 萧山现场并行部署时，正式环境使用 `xiaoshan` profile，测试环境使用 `xiaoshan-test` profile；两者配置保持一致，测试环境仅把服务端口调整为 `9090`。
10. 萧山现场统一使用 `scripts/publish-xiaoshan.sh` 发布，命令必须显式指定 `testing` 或 `production`；脚本内固定环境目录、profile 和端口，不允许通过环境变量覆盖这些映射。每次发布都必须执行 Maven `clean package`，由 Maven 在资源阶段完成管理端 `npm ci` 与 `npm run build`，不得复用 `server/target` 中来源或时效不明的旧 JAR。
11. 测试环境固定发布到 `/data/floating-ball-server-testing`，使用 `xiaoshan-test` profile 和 `9090` 端口；正式环境固定发布到 `/data/floating-ball-server-production`，使用目录内的 `floating-ball-server.jar`、`start.sh`、`xiaoshan` profile 和 `8080` 端口。首次切换时发布脚本允许受控停止旧 `/data/floating-ball-server.jar` 正式进程，失败时恢复旧正式服务；切换成功后后续正式发布只操作 production 目录。
12. 发布脚本先构建并校验 JAR，再上传到目标环境的临时文件；本地校验必须确认管理端入口及入口引用的静态资源均已进入 JAR。远端校验摘要、profile 配置、当前进程归属和端口归属后才停止目标服务。切换失败或健康检查失败时自动恢复该环境的上一版 JAR 和启动脚本，不得操作另一环境的 PID、JAR 或启动脚本。
13. 测试环境一键发布使用 `./scripts/publish-xiaoshan.sh testing`；正式环境使用 `./scripts/publish-xiaoshan.sh production --confirm-production`，必须携带显式正式发布确认参数；脚本会为单次发布复用临时 SSH 控制连接，密码认证场景正常只需输入一次远程服务器密码，已配置 SSH key 时无需输入密码。
    - 各环境可在部署目录维护独立的 `service.env`（受信任的 Bash 赋值文件，仅当前运行用户拥有、权限 `600`，不得为符号链接），启动脚本自动导出其中配置；凭据不得写进仓库、JAR 或启动脚本。目录、profile、端口等映射为只读变量。发布前检查配置文件权限及语法，发布不覆盖此文件；配置修改须单独备份和恢复。
    - 远端按环境使用 `flock` 防止并发切换；切换后的所有非零退出（含显式退出和中断）均尝试恢复上一版并重启原服务。验收同时检查 `/actuator/health/readiness` 的 `UP` 状态（包含主库与业务 schema）及管理端入口，失败按同样流程回滚。
14. 管理端认证由 `floating-ball.admin.auth.mode` 选择 `local` 或 `bbp`。启用 `bbp` 时必须同时配置 `floating-ball.admin.auth.bbp.base-url`；可通过 `tenant-id` 固定租户，未固定时由登录者填写租户 ID。服务地址仅允许 HTTP/HTTPS，不能包含账号信息、query 或 fragment。
15. 萧山现场访问 DashScope 时，由 AI 前置机 `172.17.2.6` 上的 Nginx `stream` 模块提供固定目标的 TCP 443 透传，配置基线为 `scripts/xiaoshan-ai-front-nginx.conf`；RHEL 7 的 SELinux PID 文件启动兼容配置为 `scripts/xiaoshan-ai-front-nginx.service.conf`。应用服务器 `192.168.204.122` 访问网络映射入口 `172.18.10.1:9443`，网络设备将其 DNAT 到前置机 `172.17.2.6:443`，前置机实测看到的 SNAT 来源为 `172.17.10.1`；Nginx 与主机防火墙只允许该 NAT 来源。前置机后端只连接 `dashscope.aliyuncs.com:443`，不终止 TLS、不读取 Authorization 或业务载荷，同时支持 HTTPS/SSE/WSS。应用服务器必须保持请求主机名为 `dashscope.aliyuncs.com` 并显式使用映射端口 `9443`；该入口解析不得同步到前置机，前置机必须继续把域名解析为阿里云公网地址，避免转发环路。测试与生产环境共用该出口，切换配置前必须先完成 `curl --resolve`、未鉴权 API 401、WebSocket 握手和测试环境业务验证。

### 药品说明书外部数据源

`modules/knowledge` 通过独立、惰性初始化的 Hikari 连接池只读访问 MySQL 5.7+ 的 `hzsz` 视图，JDBC URL 选择 `ipharmacare_knowledge` 数据库。不注册第二个 Spring `DataSource` Bean，不改变 Oracle/GaussDB 主库、MyBatis 或事务配置，不建表、不复制药品数据。

- 配置前缀 `floating-ball.drug-instructions`：`enabled`、`jdbc-url`、`username`、`password`；对应 `FB_DRUG_INSTRUCTIONS_ENABLED/JDBC_URL/USERNAME/PASSWORD`，默认关闭且凭据为空。URL 示例 `jdbc:mysql://db-host:3306/ipharmacare_knowledge?useUnicode=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=10000`；TLS 由现场按证书配置，工程不关闭证书验证。
- 连接池最多 2 个连接、最小空闲 0、只读；连接等待 5 秒、SQL 超时 5 秒。未启用、配置缺失或上游失败时仅说明书接口返回 503，不阻断主服务启动。账号需仅有目标视图 SELECT 权限。
- 签名接口 `/v1/knowledge/drug-instructions/search` 和 `/{productId}` 分别提供限量分页检索及完整说明书详情。SQL 参数绑定并转义 LIKE 通配符，数据库/视图名不接收客户端输入；检索不拉取 `summary`，仅详情返回完整原文，空值归一为空字符串。现场视图含大量关联重复行及同 ID 名称别名，不能直接对完整视图 OR 检索后排序；先使用 UNION 去重投影，按 ID 聚合后排序分页，再读取各 ID 的一条元数据记录，详情沿用相同读取方式。
- 客户端知识库工作台新增“药品说明书”，按名称、通用名或拼音查找，以规格/厂家区分同名药；`summary` 按文本与原始章节显示，不执行 HTML、不由 AI 改写，不参与处方自动选中或 HIS 回写。

### 3.1.1 BBP 统一账号认证边界

1. `local` 模式保持现有本地账号密码认证；`bbp` 模式只接受 BBP 认证，不在 BBP 故障时静默回退本地密码。
2. BBP 登录页只提交账号、密码以及未固定时的租户 ID，不展示或接收 BBP 角色选择。服务端调用 `POST /logon/myRoles` 验证身份并获取候选身份，再根据 PCIE 授权自动选择仅用于建立 BBP 会话的候选角色调用 `GET /logon/myApps`，并要求响应 Cookie 中存在非空 `tk`。密码按 BBP 既有约定进行一次 MD5 摘要，服务端不保存 BBP 明文密码或 `tk`。
3. BBP 负责验证账号身份，PCIE 通过 `c_ai_bbp_admin_grant` 按 `tenant_id + org_id + bbp_user_id + role_code` 管理普通 BBP 人员是否允许进入后台及其 PCIE 角色。当前可配置 `ORG_ADMIN`（机构权限管理员）、`ORG_ANALYST`（机构统计员）和 `EMR_QC_REVIEWER`（门诊病历质控员）；授权使用 BBP 稳定用户 ID，不依赖人员工号或登录名。普通 BBP 用户未命中启用授权时拒绝登录，不再回退本地 `c_ai_user/c_ai_user_role`；BBP `system` 账号仍自动使用 `tenantSystem` 身份并映射到本地 `admin`。
4. 登录成功后的菜单不根据 BBP 应用菜单覆盖 PCIE 管理端菜单，由 PCIE 角色决定可见范围。`SYSTEM_ADMIN` 和既有管理角色保持原菜单；仅具备 `ORG_ANALYST` 的账号只显示统计相关页面，仅具备 `EMR_QC_REVIEWER` 的账号只显示“门诊病历质控”。账号同时具备两种受限角色时按两者并集开放，不能借此访问其他后台功能。BBP 返回的角色只用于确认候选租户、机构和用户身份以及建立 BBP Cookie 会话，不能授予任何 PCIE 菜单或操作权限；PCIE 后台访问授权与 AI 使用权限均由 PCIE 管理，二者分别存储、不能相互推导。
5. BBP 登录会话仅保存在当前 PCIE 进程内，并与 12 小时管理端令牌会话绑定，用于读取 BBP 机构与人员目录及 PHIS 门诊病历；不把密码、Cookie 或 `tk` 写入数据库、磁盘或管理端令牌。进程重启后既有令牌仍可访问 PCIE 本地功能，但当前进程必须由该 BBP 身份至少重新登录一次才能恢复上游会话；同一租户、机构、用户和角色身份在当前进程内重新建立会话后，仍在有效期内的同身份旧管理端令牌可自动复用这份进程内 Cookie，避免同一账号的多个页面分别重复登录。不同身份、不同机构或真正被 PHIS 以 401/403 拒绝的会话不得复用。
6. 机构与人员只读接入：机构使用 `POST /api/bbp.organization/findByTenantId`，人员支持 `POST /api/bbp.person/findByOrgId` 与 `POST /api/bbp.person/findByDeptId`。BBP 模式下管理端“机构”“用户”页面直接以 BBP 目录作为主列表，不再先展示 PCIE 本地表、再通过弹窗查看 BBP 数据。BBP 仍是主数据源，PCIE 不反向修改机构或账号；PCIE 本地机构、用户与角色只承担配置作用域和后台授权。
7. 启用 `bbp` 模式后，管理端机构、用户页面不得提供新增、编辑、启用、停用或删除操作，后端对应写接口也必须拒绝请求，不能只依赖前端隐藏按钮。
8. BBP 机构/人员接口兼容直接数组与 `{ code, body }` 包装响应；字段只做兼容映射并保留原始 ID。人员目录保留 BBP 人员、用户、机构、部门、人员类型、职称、联系方式和状态字段；证件号码属于敏感字段，接口可用于授权管理员排障，但管理端列表默认不展示。
9. 已确认 `bbp.person/findByOrgId` 的请求体为单元素机构 ID 数组 `[orgId]`，响应为 `{ code: 200, body: Person[] }`；人员字段与 `findByDeptId` 使用同一结构，PCIE 必须同时校验响应中的租户和机构，不能仅信任请求作用域。
10. BBP 模式下机构和人员主数据仍保持只读；`SYSTEM_ADMIN` 可以在“用户”页面针对 BBP 人员分别授予或撤销“PCIE 机构权限管理员”“机构统计员”和“门诊病历质控员”后台角色。该动作只写 `c_ai_bbp_admin_grant`，不创建、修改或停用 BBP 人员，也不允许机构管理员继续委派后台角色。
11. 普通 BBP 人员登录时，服务端只保留租户、机构和 `userId` 同时命中启用 PCIE 后台授权的 BBP 候选身份。同一身份下存在多个 BBP 业务角色时自动选择一个建立会话；未命中任何授权时拒绝登录；命中多个不同机构或用户身份时也拒绝登录并提示管理员收敛授权，避免静默进入错误机构。BBP 已停用角色不会出现在登录候选中；目录人员只有明确返回 `active=true` 时才可新增后台授权，停用或状态缺失均按不可授权处理，已有风险授权在管理页突出显示并允许系统管理员撤销。
12. `ORG_ANALYST` 是可按 BBP 机构配置的只读统计账号，不为新塘、蜀山、瓜沥等机构写死账号或机构 ID。系统管理员在“用户”页面选择机构和“机构统计员”权限类型后授予；后续新增机构复用同一入口。
13. `EMR_QC_REVIEWER` 是按 BBP 机构和人员配置的门诊病历质控专用角色。系统管理员在“用户”页面选择“门诊病历质控员”后授予或撤销；单独持有该角色的用户只允许访问 `/emr-qc` 页面、`/admin/api/emr-qc/**` 以及当前用户/退出登录接口。前后端必须同时校验，直接构造 URL 或 API 请求不得扩大权限。

### 3.1.2 BBP 机构统计权限边界

1. 仅具备 `ORG_ANALYST` 的 BBP 用户只允许访问 `/admin/api/analytics/**`、`/admin/api/xiaoshan-analytics/**`、`/admin/api/user-activity/**` 以及当前用户/退出登录接口；其他管理接口统一返回 HTTP 403 与 `AUTH-403`。前端菜单和路由守卫只是体验层，服务端接口白名单是最终边界。
2. 所有统计汇总、趋势、分布、辅诊功能排行、用户活跃度明细和 Excel 导出都必须由服务端把 `hisOrgId` 强制锁定为登录令牌中的 `bbpOrgId`。请求未传机构时自动补入本机构；显式传入其他机构时拒绝，不能仅依赖前端禁用下拉框。
3. 机构统计员页面默认显示并选中登录 BBP 机构，HIS 机构选择不可清空或修改；区域筛选不展示，避免产生可扩大范围的错觉。机构选项接口只返回本机构，不能向该角色泄露其他机构目录。
4. `SYSTEM_ADMIN` 仍可跨机构统计；其他 BBP 非系统账号即使不是纯 `ORG_ANALYST`，统计查询也统一锁定登录机构。一个账号同时拥有 `ORG_ADMIN` 与 `ORG_ANALYST` 时保留管理菜单，但统计数据仍只能查看本机构；本地认证模式维持现有统计范围。

### 3.1.3 AI 使用权限边界

1. BBP/PHIS 只提供机构、人员主数据和统一账号身份，不作为 AI 使用权限的权威来源。
2. PCIE 通过 `c_ai_user_ai_permission` 保存租户、机构、BBP 人员/用户标识、真实工号及启停状态；管理端“AI 使用权限”页面把 BBP 人员目录与 PCIE 权限记录合并，明确展示已授权和未授权人员。
3. `SYSTEM_ADMIN` 可管理当前租户全部机构；其他 BBP 后台用户只能查询自身机构。写操作额外要求 PCIE 登录角色包含 `SYSTEM_ADMIN` 或 `ORG_ADMIN`，普通机构人员只能查看，不能授予或撤销权限。机构和角色范围都必须由服务端根据登录身份校验，不接受前端自行声明扩大范围；BBP 登录用户的 `ORG_ADMIN` 必须来自 `c_ai_bbp_admin_grant`，本地用户角色只在 `local` 认证模式下生效。
4. PHIS 通过 `POST /integration/api/phis/ai-permissions/check` 查询人员是否允许使用 AI。当前联调阶段暂不启用独立服务密钥，也不复用管理端令牌或设备令牌；该接口只能部署在 PCIE 与 PHIS 之间的可信内网或受控网关后，不得直接暴露公网。后续恢复服务鉴权时应在网关/mTLS 或独立服务凭据中选择一种，并保持判权业务契约不变。
5. PHIS 判权采用默认拒绝：权限记录不存在、已停用、租户/机构不匹配或身份字段无法匹配时均返回 `allowed=false`，不得因 BBP 人员处于激活状态自动放行。请求必须至少提供稳定的 `personId` 或 `userId`；`personCd` 只作为同时校验字段，不允许单独按工号放行。
6. 单人和批量权限写入都必须先一次读取目标机构的 BBP 人员目录、校验全部人员归属和启停状态，再在同一事务中全量写入；新增授权要求人员明确返回 `active=true`，停用或状态缺失均默认拒绝。批量请求去重后最多 200 人，任一人员不合法时整批不写入。
7. 权限授予和撤销只修改 PCIE 权限记录，不反向修改 BBP 机构、人员、账号或角色。每次变更同时写入 PCIE 操作日志，记录机构、人员、目标状态和操作人。
8. 权限清单按机构、科室、BBP 启停状态和授权状态筛选，并由服务端稳定排序、分页返回；机构总人数和授权汇总不受当前筛选影响。系统管理员必须显式选择机构，普通机构用户的机构上下文固定为登录角色所属机构。
9. 管理端把“BBP 已停用”“BBP 未明确返回启用状态”或“已不在当前 BBP 机构目录”但 PCIE 仍处于授权状态的记录计为风险授权，并允许权限管理员按机构一键撤销。PHIS 判权接口本身不持有 BBP 登录会话，因此人员停用的实时失效需要 BBP 后续提供推送、服务账号或其他可持续同步机制；在该机制接入前，风险清理是管理端补偿措施，不能宣称实时同步。
10. 应用启动时只读探测 `c_ai_user_ai_permission` 表及必需列，不自动修改现场数据库；探测失败时记录明确的 DBA 迁移提示，权限管理和 PHIS 判权接口返回 503 与稳定错误码，其他后台能力继续可用。存量库仍由 DBA 根据当前 `init.sql` 和现场结构生成一次性迁移脚本。

### 3.1.4 PHIS 门诊病历质控边界

门诊病历质控随服务默认启用，`floating-ball.emr-qc.enabled` / `FB_EMR_QC_ENABLED` 仅作为紧急停用开关；显式设为 `false` 时接口返回 `EMR-QC-DISABLED`。默认启用不改变 BBP 登录、PHIS 会话、机构权限、数据库表和 AI 配置等前置校验，也不使 PHIS 成为 PCIE Server 的单体启动前置条件。

1. 范围仅为 PHIS 门诊病历（`idApp=42`）的只读查询与 AI 书写质控，管理端页面路径 `/emr-qc`。不包含住院病历、PHIS 回写、打印/PDF 导出、规则或提示词配置页、独立人工复核流转和质控统计看板；2026-10-26 起支持在当前结果上直接人工调整评分与问题明细，不引入独立复核工单流。
2. 仅 `bbp` 认证模式可用。PHIS 业务网关与 BBP 登录共用同一环境基地址（`floating-ball.admin.auth.bbp.base-url`，现场由 `FB_ADMIN_BBP_BASE_URL` 注入），服务端直接复用管理员登录时 `myRoles + myApps` 建立的进程内 `tk` Cookie 会话，不保存管理员密码，也不建立第二套 PHIS 专用账号会话。会话与 12 小时管理端令牌同生命周期且仅存于内存；进程重启或 `tk` 过期后病历接口返回稳定错误码，提示管理员重新登录后台刷新会话，不回退专用服务账号、匿名访问或本地密码。
3. 只使用管理员 BBP 登录时已选定的角色及其 PHIS 机构/科室作用域，不在质控请求中切换角色或重新登录。不限制角色类型，是否可见病历由登录管理员的 PHIS `tk` 会话决定；角色缺少机构时以登录机构回退展示，`orgIdList` 留空交由上游按会话裁剪，不借用其他账号或扩大机构范围。
4. 服务端固定使用 `POST {baseUrl}/api/{service}/{method}`，仅允许 `otms.emr.rpcHistoryRecordService/queryHistoryRecord`（列表）、`otms.emr.rpcHistoryRecordService/getInitDataUser`（医生候选）与 `otms.mb.rpcElectronicMedicalRecordWritingService/getMedContentPreview`（详情）。不接受前端传入 ServiceId、Method、上游路径、租户、`idApp` 或机构 ID 列表；`idApp` 固定 `42`，列表使用 PHIS 全院查询参数 `userType=0`、已完成状态 `fgCheck=2`；`orgIdList` 由会话角色派生，角色未携带机构时留空并交由 PHIS 按 `tk` 会话裁剪。医生候选使用当前会话请求 `getInitDataUser` 的 `userType=0`、空关键字，并限制为当前 BBP 机构；选择的 `userId` 必须存在于该候选集，作为上游 `insertUser` 传入。详情与质控只接受当前会话最近一次列表查询返回过的 `idMedrecdoc`，未命中的构造 ID 一律按不存在处理，避免绕过机构数据权限。
5. 只读约束：不调用 `saveMedLog`、`queryPDF`、`exportHistoryRecordPdf` 或任何签名/回写接口，不修改 PHIS 数据，不把病历 HTML 原文落盘。上游失败、超时或结构异常时明确失败，不伪造空病历或空质控结果。
6. 所有 PHIS 出站请求经过统一出站安全门，禁止重定向并受独立超时限制；不记录 `tk`、Cookie、请求头凭据、患者姓名/身份证/病案号和上游 HTML/XML 正文。
7. 2026-10-26 用户授权 HTML 只读预览；管理端仅以隔离 iframe 展示服务端白名单清理、脱敏后的 `htmlPreview`，原始 `htmlContent` 不返回、不执行；缺失时回退 `xmlContent` 归一化纯文章节，安全边界见 5.9.1。
8. 送 AI 前由服务端对姓名、身份证号、病案号、联系方式和地址做掩码或移除，仅发送归一化章节文本及就诊科室、时间等质控必需上下文；不发送 HTML、签名图片、Cookie、原始 XML 或完整患者身份。
9. 复用 `ConfigService` 现有可用模型配置，按登录管理员的机构/区域作用域解析，不新增第三方密钥、独立模型配置页或客户端 bootstrap 入口；模型名称、质控维度版本与输入文本 SHA-256 随结果落库用于追溯。列表返回的开立医生标识使用当前登录机构的 BBP 人员目录按用户 ID、人员 ID、登录名或人员编码唯一映射为姓名；包括已停用人员，映射失败或不唯一时保留上游原值，目录不可用时不阻断病历查询。
10. AI 结果仅作为书写参考，不替代医生或病案管理人员的人工审核，不得表述为“质控通过”或“医学审核通过”，也不触发任何自动修改或回写。归一化文本 SHA-256 或 PHIS `updateTime` 变化后，管理端必须提示已有结果可能过期并允许重新质控。
11. 权限边界：`SYSTEM_ADMIN`、`ORG_ADMIN` 和 `EMR_QC_REVIEWER` 可访问 `/admin/api/emr-qc/**`；仅有 `ORG_ANALYST` 或其他角色的账号禁止访问。单独持有 `EMR_QC_REVIEWER` 的用户除质控接口、当前用户和退出登录外不得访问其他管理接口；与 `ORG_ANALYST` 同时授予时按两类白名单并集开放。BBP 非系统管理员只能查询登录机构范围内的病历，机构作用域由服务端锁定，不接受前端声明。
12. 质控结果、发现明细、批量任务和操作审计分别写入 `c_ai_emr_qc_*` 四表；只保存归一化文本摘录、评分与元数据，不保存完整病历原文、HTML/XML、Cookie、token 或上游原始响应。批量任务由单实例有界线程池串行执行，单任务最多 50 份，默认并发 1。
13. 评分标准（2026-10-26 起）按门诊病历质控评分表内置 9 个维度：患者基础信息 10 分、主诉 5 分、现病史 20 分、既往史 10 分、查体与辅助检查 15 分、诊断 10 分、诊疗措施 20 分、病历书写基本要求 5 分、其他 5 分，满分合计 100 分。“其他”覆盖急诊病历就诊时间未具体到分钟（扣 2 分）及急诊病人无 T、P、R、BP 生命体征记录（扣 3 分），仅在实际文本可判定为急诊病历时执行。维度 `score` 为该维度实际得分（0 至该维度满分），`weight` 为该维度满分；总分由服务端按维度得分求和计算，不接受 AI 或前端直接给定总分。每个问题保存 `deduction` 扣分及 `ignored` 忽略状态；出生年月、联系电话、工作单位及地址由患者档案维护，不作为病历质控检查或扣分项；服务端依据有效 AI 问题扣分计算维度得分，规则版本升级为 `emr-qc-rules-v6`；非强制质控只有在内容未变化且结果规则版本与当前版本一致时才可复用，旧版本结果需重新调用 AI。
14. 人工调整仅针对已完成 AI 质控且仍属于当前会话列表允许集的病历：必须在现有质控结果面板内联完成，不另开弹窗或独立页面；必须填写调整理由，可修改维度得分、问题说明与扣分，并可忽略/恢复问题。前端按全部有效问题重新计算维度得分，服务端独立重算并校验维度得分及总分。`deduction` 必须是不超过对应维度满分的非负整数，忽略问题保留原扣分及完整明细用于追溯，但不计入有效问题数和高危数。问题明细整体替换为调整后版本并生成新的 `analysisId`，AI 原始发现明细按原 `analysisId` 保留不删除。记录表保存 `result_source=manual`、调整人、调整时间、理由及 AI 原始总分/分析 ID；重新执行 AI 质控后结果回到 `result_source=ai`，并采用本次 AI 新问题及未忽略状态。人工调整结果同样仅作书写参考，不得表述为质控通过或医学审核通过，不回写 PHIS；操作写入 `c_ai_emr_qc_review_log`（`action=manual_review`）。

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
- `modules/emrtemplate` 承载两条语义独立但管理口径一致的链路。住院链路继续缓存 HTML 模板解析结果，客户端按 HIS 传入的 `templateId` 复用已解析字段；管理端使用脱敏示例病例按桌面端相同的 `[data-id][data-type]` 规则预渲染缓存模板，空缓存时提供不依赖后端数据的内置示例，但不读取或保存真实患者数据。门诊链路不复用住院缓存，也不向桌面端下发模板：客户端在正式 HIS Bridge/SDK 门诊分析调用模型前，先通过签名 `POST /v1/client/outpatient-emr/templates/snapshots/resolve` 按认证机构、`templateId` 与模板对 hash 精确查询历史解析；未命中时客户端才执行严格解析并通过签名 `POST /v1/client/outpatient-emr/templates/snapshots` 登记 `outpatient-emr-template-pair-snapshot.v1`。该快照把同一模板的渲染 HTML、结构定义 JSON 与完整合并解析结果作为不可分割版本；服务端按约定公式复算模板对 hash，严格校验顶层、字段和字典项 schema，不修剪或补造字段身份，并按 `idOrg + templateId + templateHash` 保持唯一。首次出现的模板 ID 或任一原文字节变化都形成新记录；同一身份的重复/并发登记只返回已有记录，不更新原文、解析结果、设备或时间。`c_ai_outpatient_emr_tpl_snapshot` 只保存模板身份、两份原文、完整解析 JSON、字段统计、首次接收设备和时间，不保存患者、就诊、病历上下文、模型返回或医生编辑结果。

  门诊字段人工维护独立保存在 `c_ai_outpatient_emr_tpl_mapping`，以 `idSnapshot + fieldId` 唯一，不修改不可变快照。管理端保存覆盖前必须确认字段属于该快照，并对标准病例字段、投影方式和重复映射做完整校验；覆盖可以指定有效映射，也可以显式设为未映射。历史解析命中及管理端详情读取快照后，在内存中叠加该版本的激活覆盖，并重新计算有效映射数；删除覆盖即恢复原始自动映射。为兼容当前 `outpatient-emr-template-pair.v1` 严格客户端，人工直接映射继续投影为 `mappingSource="definition-record-field"`，人工章节组合映射投影为 `mappingSource="definition-article-record-field"`，不增加响应字段或新枚举。历史查询失败、存量解析损坏或覆盖冲突必须显式失败，不允许客户端重复解析兜底。未映射字段的 `recordField / projectionMode` 必须保留显式 `null`。

  管理端统一以“门诊病例模板 / 住院病例模板”双页签呈现，公共操作统一为“病例预渲染 / 字段维护（门诊称字段映射）”。门诊预渲染按有效 `recordField / projectionMode` 使用脱敏模拟病例；字段映射展示自动来源、人工覆盖和待确认状态；渲染 HTML、结构定义 JSON、完整解析 JSON 收入只读“解析详情”。门诊快照仍不提供发布、启停、删除或反向下发，桌面端不提供本地模板实验台。旧 `sourceFormat/templateSource` 不兼容、不落库、不保留查询筛选或展示兜底。
- `modules/lisresult` 负责面向管理端的检验检查结果手动录入与第三方回写模拟：只查询 `hi_ods_apply` 中检验/检查类、未报告/未作废的申请单；面向 PHIS 多版本表结构差异，申请单列表、报告查看和回写前校验查询必须显式选择界面展示或逻辑处理需要的最小列，避免实体映射中的扩展字段参与运行时查询；检验录入后将报告组 ID 写回 `hi_ods_apply.id_result`，并把每个检验指标写入 `hi_ods_apply_lis_report`；检查录入后将报告 ID 写回 `hi_ods_apply.id_result`，并把报告结果、临床印象、影像诊断、阴阳性等字段写入 `hi_ods_apply_pacs_report`。该功能不接管业务系统产生申请单的链路，也不新增本地 `/api/consultation/*` 能力。
- `modules/prompt` 负责 Prompt 配置化的逐步迁移：保留桌面端 `prompts/delta` 读取链路，管理端提供 Prompt 列表、新增、编辑、发布、归档和停用；服务端内置首批语音问诊默认 Prompt，配置表存在已发布覆盖时按机构级 > 区域级 > 全局级优先级生效。普通语音渐进结果使用独立 `voiceIntentRecognitionStream` 编码和 NDJSON 分区协议，避免历史 `voiceIntentRecognition` 纯 JSON 已发布覆盖与新客户端协议混用；新编码默认同时返回核心病历、病历上下文、AI 病历候选、诊断、诊疗路由、明确医嘱和其余病历字段，并把明确医嘱的 `name` 与字符串枚举 `type` 作为独立可选分区契约，单条无法确认时应删除而不是重写病例结论。诊断分区必须同时提供 `clinicalRole=current_diagnosis|differential_cause|risk_modifier|history_only`、`diagnosisKind=disease|symptom_working`、`evidenceScope=current_visit|history_only|both` 与当前就诊证据；`history_only / risk_modifier` 不进入诊断建议，待鉴别只保留能解释当前主诉的病因候选，无病因性正式诊断时允许返回至多一项本次明确的症状性工作诊断。HIS 既往史、历史诊断和长期用药不得因其“历史已确诊”而被当作本次就诊高置信证据；`voiceIntentRepair` 只保留原始角色、诊断种类和证据作用域，不得在结构修复时把历史依据改写为本次证据。客户端仍负责协议容错、历史/风险项过滤、R 类症状工作诊断提升与检查优先路由、实时目录匹配、药品定稿及 PHIS 回写。
- `modules/datapackage` 继续负责映射数据包读取；`template` 类型数据包仅作为症状模板表未初始化时的兼容回退来源，管理端不再提供数据包维护入口
- `modules/recommendationpreference` 负责接收桌面端在目录匹配之后产生的诊断和医嘱标准候选选择事件，按机构/科室/医生聚合偏好分，并为灰度客户端返回带样本置信度与作用域权重的名次 boost；管理端提供只读观测页查看聚合偏好分、样本计数和原始事件，便于确认采集效果与排查上报链路。该模块不学习 AI 原始文案，不注入 Prompt，不生成新的候选项，首版管理端不提供人工编辑偏好分入口
- `modules/release` 使用服务端本地文件目录托管桌面端安装包、签名文件、`latest.json` 元数据、`policy.json` 发布策略与历史发布快照，不新增数据库表；管理端上传后由客户端通过公开 `/v1/client/releases/{channel}/latest.json` 检测更新，并通过 `/v1/client/releases/{channel}/policy.json` 判断是否必须更新。普通客户端与 Win7 legacy 客户端共用发布服务实现，但通道、策略和历史目录完全隔离

### 4.2.1 内网客户端版本发布

- 存储根目录由 `FB_RELEASE_STORAGE_DIR` / `floating-ball.release.storage-dir` 配置，默认 `${java.io.tmpdir}/floating-ball-server/releases`
- 对外更新源地址可通过 `FB_RELEASE_PUBLIC_BASE_URL` / `floating-ball.release.public-base-url` 固定指定；为空时若请求 Host 为 `localhost` / `127.0.0.1`，服务端会尽量自动替换为本机局域网 IPv4
- 上传大小由 `FB_RELEASE_MAX_FILE_SIZE` / `FB_RELEASE_MAX_REQUEST_SIZE` 控制，默认 `2048MB`
- 管理端通过 `/admin/api/releases` 查看当前发布，通过 `/admin/api/releases/upload/batch` 一次选择多个发布通道和多个 Tauri 安装包；服务端基于同一份 `latest.json` 自动解析版本号、平台 target、签名和更新说明，并分别重写为各通道内网下载地址。平台 target 不再要求运维手工填写，服务端按上传安装包文件名匹配 `latest.json.platforms.{target}.url` 自动识别；若多个 target 指向同一个安装包文件名（例如 macOS universal 包同时覆盖 `darwin-aarch64` 与 `darwin-x86_64`），批量发布会将同一上传文件展开发布到这些 target，并保留各 target 在 `latest.json` 中的签名；`/admin/api/releases/upload` 保留单通道单安装包兼容入口。上传时可勾选强制更新，服务端会把每个目标通道的当前发布版本写入对应 `policy.json` 的 `minSupportedVersion`
- 管理端“版本发布”列表展示当前安装包的公开下载地址，支持复制链接和浏览器直接打开；首次部署新客户端时可直接访问 `/client-download?channel=production` 选择平台下载安装包，无需 U 盘拷贝
- 管理端通过 `/admin/api/releases/policy` 独立开启或关闭当前通道强制更新，不需要重新上传安装包；开启时最低可用版本固定为当前通道 `latestVersion`，关闭时清空 `minSupportedVersion`
- 管理端通过 `/admin/api/releases/history` 查看历史发布快照，通过 `/admin/api/releases/rollback` 回滚到历史版本；回滚只恢复当前通道的 `latest.json` 与 `policy.json`，不会重新上传安装包
- 每次上传新版本前，服务端会先把目标通道当前发布保存为历史快照；上传后也保存新版本快照。若同一版本分平台多次上传或批量上传，服务端会合并同版本平台；若版本号变化，则每个目标通道都重新开始该版本的 `platforms` 集合，避免把上一版本平台误混入新版本 `latest.json`。Win7 两条通道额外以全部历史快照的最高版本执行单调递增门禁，同一当前版本可补传平台，低版本或回滚后复用旧版本均拒绝，降级只能使用显式回滚入口
- 客户端更新检测与策略查询不走设备鉴权，避免 Tauri updater 无法附带 `Authorization`；仅暴露静态安装包、Tauri 兼容元数据和强制更新策略，不暴露管理能力
- 未上传版本的通道在访问 `/v1/client/releases/{channel}/latest.json` 时返回 `204 No Content`，作为 Tauri updater 可识别的“无可用更新”状态，不走业务异常日志
- 设备业务接口通过 `DeviceAuthFilter` 统一执行强制更新拦截：`/v1/client/releases/**` 始终放行，其他 `/v1/*` 在 `forceUpdate=true` 且客户端版本低于 `minSupportedVersion` 时返回 `426 / UPDATE-REQUIRED`
- 桌面端请求头优先携带 `X-Client-Version` 与 `X-Update-Channel` 供拦截器判断；旧客户端缺失请求头时，服务端回退设备表 `client_version` 与 `production` 通道策略
- 通道固定为 `production` / `testing` / `win7-production` / `win7-testing`。前两者只服务普通客户端，后两者只服务同一 `main` 源码下的 Win7 legacy 独立构建；Win7 与普通 Windows 的 Tauri target 都是 `windows-x86_64`，因此不得跨通道上传、合并 `latest.json`、复用强更策略或执行回滚。GitHub 托管构建只离线生成带内网 URL 的签名 Artifact，不连接本服务；管理员在内网完成上传，由本服务执行最终版本门禁
- 平台值需与 Tauri updater target 匹配，例如 `darwin-aarch64`、`darwin-x86_64`、`windows-x86_64`

### 4.3 数据访问层

- 使用 MyBatis-Plus `Mapper` 访问 Oracle 19c 或 GaussDB/openGauss PostgreSQL 兼容库
- `mybatis-plus.db-type` 决定分页方言，默认 `oracle`，`gaussdb` profile 默认 `opengauss`
- 运行时数据库差异集中在 `common/db/DatabaseDialect`：分页尾句、日期分组、空值函数、JSON 数值读取等不得散落硬编码
- 首期不引入复杂读写分离、缓存和消息队列

### 4.4 管理端托管约定

- 管理端导航保留独立“首页概览”，其余入口按下表归入六个可折叠目录。`src/router/navigation.js` 统一维护目录、菜单名称、图标和页面元数据，侧栏、顶栏、页签及浏览器标题复用同一名称；只调整导航组织，不调整页面 URL、后端接口或角色授权。

  | 目录 | 菜单（按展示顺序） |
  | --- | --- |
  | 统计分析 | 统计概览、辅诊功能统计、用户活跃统计、客户端使用统计、推荐偏好分析 |
  | 临床业务 | 门诊病历质控、检验检查回写、患者记忆 |
  | AI 配置 | 模型配置、提示词配置、症状模板、病历模板 |
  | 日志审计 | 问诊日志、住院病历日志、医学助手日志、操作日志 |
  | 系统运维 | 客户端发布、用户反馈、业务流程调试、安全拦截记录、安全风险分析 |
  | 基础管理 | 用户管理、角色管理、AI 使用权限、区域管理、机构管理、设备令牌 |

- 目录默认收起，当前页面所在目录自动展开并标识；允许同时展开多个目录。侧栏缩为图标时显示所有可访问页面，保留名称提示和可访问名称。菜单先按原有权限过滤，再剔除空目录；`ORG_ANALYST` 仍只可见统计概览、辅诊功能统计、用户活跃统计，BBP 专属入口继续仅在 BBP 模式显示。
- 导航回归通过 `npm --prefix server/src/main/admin run test:navigation` 验证分类覆盖、名称一致、角色过滤及目录展开行为。
- Vite 依赖缓存独立放在管理端 `.vite-cache/`（Git 忽略），避免 Maven 的 `npm ci` 删除运行中开发服务的依赖缓存；图表和病历时间线依赖启动时预构建。路由异步页面加载失败须展示可关闭的刷新重试提示，不得仅在控制台报错而使菜单点击无反馈。
- 管理端页面由 Spring Boot 统一托管，默认访问入口为 `/admin/`
- 管理端构建产物输出到 `server/src/main/resources/static/admin`
- `mvn -f server/pom.xml process-resources/test/package` 会在资源阶段自动执行管理端 `npm ci` 与 `npm run build`，确保 `/admin/index.html` 与静态资源进入后端类路径；`npm rebuild` 只重建已安装依赖，不编译当前管理端业务源码，不能替代 `npm run build`
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
- 管理端所有服务端列表统一复用 `AdminPagination`：组件提供 `10 / 20 / 50 / 100` 条每页选项并在每页条数变化时回到第 1 页；页面只维护查询条件和列表数据，不得再次直接拼装 `el-pagination`。服务端列表参数统一由公共分页模型归一为 `current >= 1`、`1 <= size <= 100`，响应统一返回 `current / size / total / records`，导出链路不复用列表分页上限。
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
8. 小助手可在签名请求中声明 `enableSearch=true`，但服务端只在 `stream=true`、`scene=chat-stream`、`sourceModule=chat_panel` 同时满足时向上游附加 `enable_search=true`；其他聊天代理调用即使误传也必须忽略该字段，避免普通语音问诊、病历生成、诊疗推荐、风险分析和审查请求被全局带入联网搜索。未开启时省略上游字段，以继续兼容不认识千问扩展参数的 OpenAI 兼容模型。
9. 当前 Chat Completions 代理只转发模型回答正文，不提供 DashScope 原生 `search_info` 或引用角标；联网结果属于小助手补充信息，不得作为已经完成权威来源核验的结构化临床依据。
10. 每次文本 AI 代理调用都由服务端统一计时并写入对应 `ai_proxy` 审计 payload：非流式记录总耗时，流式同时记录首个有效 SSE 数据帧耗时和直到 `[DONE]` 的总耗时，失败记录截至失败时的耗时。计时使用单调时钟，客户端上报耗时只作链路补充，不能覆盖服务端实测值。
11. 桌面端把 `aiTrace` 已有的业务动作与标题随 `/v1/ai/chat` 请求传入；服务端代理日志优先使用该业务动作/标题，旧客户端缺失时回退 `chat / chat_stream`，以便按“基于现有信息评估用药”等真实场景检索。

语音代理补充约束：

1. `floating-ball` 的聊天录音与语音兜底批量转写通过 `/v1/ai/speech/transcribe`、`/v1/ai/speech/realtime` 上传 base64 录音内容；DashScope 与自建 FunASR 实时语音通过 `/v1/ai/speech/realtime/ws` WebSocket 逐帧代理 PCM 音频
2. 服务端必须先把 base64 录音解码为真实字节数组，再按 `speech_provider` 选择批量上游协议：`openai-compatible` 与 `funasr-websocket` 组装为 `multipart/form-data` 调用 `/audio/transcriptions`，`aliyun-dashscope` 组装为 DashScope 兼容模式 chat completion 音频请求；`funasr-websocket` 的原生协议只用于实时链路，批量兜底仍由独立 `audio_base_url` 提供
3. 对原始 PCM 录音，服务端先补 WAV 头后再上游转发，避免不同语音供应商对裸 PCM 兼容不一致
4. 服务端应保留录音元数据（`mimeType`、`format`、`fileName`、`scene`）用于排障和审计，但不在日志中落原始音频内容
5. 语音代理日志中的录音内容需要单独落为文件，默认写入 `floating-ball.audit.speech-file-dir` 指定目录；`c_ai_op_log` 只保存该录音文件路径，不把 base64 或二进制音频写入 `payload_json`
6. 服务端批量访问语音上游时使用 `audio_base_url` / `audio_model` / `audio_api_key_encrypted`；语音独立密钥为空时回退主模型 `api_key_encrypted`。实时上游独立使用 `speech_realtime_url`，不得再把 WebSocket 地址填入 `audio_base_url`
7. `speech_provider` / `speech_model` 作为 bootstrap 下发给桌面端的语音提供方与实时识别模型；`aliyun-dashscope` 默认使用 `qwen-audio-3.0-asr-flash-streaming`，通过 DashScope `/api-ws/v1/inference` 的 `run-task` 协议发送 16 kHz 单声道 PCM 二进制帧，并在 `parameters` 中固定启用 `heartbeat=true`，避免长会话或静音窗口在约 60 秒边界被上游回收。服务端必须保留并实际上送管理员填写的兼容模型名，不得因模型名不在内置列表而静默回退默认值；`qwen3-asr-flash-realtime` 等使用 `/api-ws/v1/realtime` session 协议的模型应在保存时明确拒绝。`funasr-websocket` 使用 FunASR 原生 `2pass` 协议。FunASR 首帧固定声明 `pcm`、16 kHz、单声道采样，结束时发送 `is_speaking=false`，服务端把 `2pass-online` / `2pass-offline` / `offline` 结果归一化为桌面端既有 `text/final/error` 帧；结束请求后的 offline 帧即使携带 `is_final=false` 也必须触发最终收口
8. 批量语音转写 HTTP 出站与实时语音 WebSocket 出站都必须经过同一 host allowlist、私网拦截、限流与熔断策略；`ws` 仅在 `allow-insecure-http=true` 时允许，生产公网链路应使用 `wss`
9. 管理端语音可用性测试直接使用当前表单草稿且不保存配置：DashScope 实时测试建立 WebSocket、携带与生产一致的心跳参数并等待 `task-started`，FunASR 实时测试验证握手，批量测试向实际上游发送极短静音 WAV；三类测试均复用统一出站安全门，且不得生成语音审计文件。该快速探测只证明建链和模型启动成功，不代表超过 60 秒的持续识别已通过。
10. 实时代理必须把上游 `task-failed` 的模型、任务 ID、错误码/错误信息以及上游关闭码写入服务日志，但不得记录音频内容或凭据。客户端主动关闭时先标记下游已关闭再清理上游，禁止上游关闭回调向已关闭客户端补发 `final`；上游非主动结束时只向客户端发送一次 `error` 或 `final` 并关闭下游，由桌面端重连。

### 5.3 审计链路

1. 客户端本地缓存事件
2. 客户端对远端操作日志不再依赖本地 SQLite，直接调用 `POST /v1/client/audit/events/batch`；启动时补传遗留队列，新事件入队后异步立即尝试一次，失败或离线时继续保留队列并按固定周期重试
3. 客户端对 `operation` 事件优先上报 `{ module, action, title, sourceModule, scene, result, operationType, operationName, details }`，并在事件外层携带事件产生时的 `hisOrgId/hisOrgName`；其中 `module/action/title/sourceModule/scene/result` 与 HIS 机构会被服务端提取到结构化列，`operationType/operationName/details` 继续保留在原始 payload
4. AI 调用类 `operation` 事件必须同时保留摘要与完整出入参：`details.requestSummary/responseSummary` 用于列表摘要，`details.requestPayload/responsePayload` 用于详情排障。`requestPayload` 应记录实际发送给 `/v1/ai/chat` 或语音代理的业务请求体；`responsePayload` 应记录业务回文或错误对象。API Key、Bearer Token 等凭据不得进入 payload；语音原始 base64 / 二进制音频不得进入 payload。
5. 客户端本地只保留轻量失败重试队列，不再把远端操作日志落本地 SQLite；服务端仍按同一批量接口落库
6. 服务端兼容旧载荷：若未显式提供 `module/action/title/sourceModule/scene/result`，则回退从 `operationType/operationName/success` 与 `details.traceId / details.consultationId` 等字段推导
7. 服务端写入 `c_ai_op_log`；`id_org` 继续来自设备鉴权，`id_his_org/na_his_org` 来自桌面端 SDK handshake 上下文，两者不得互相覆盖
8. 管理端提供分页查询，并支持按 `module/action/title/sourceModule/scene/traceId/consultationId/result` 结构化筛选；详情弹窗必须把完整入参、完整出参与原始 payload 分区展示，不能只展示摘要字段。JSON 默认使用可折叠的结构化视图，完整 JSON 字符串字段递归解析为对象或数组，普通长文本按真实换行折行展示；超大内容的结构化视图使用全树节点预算并在工具栏提示截断，原文仍保留完整内容供查看和复制。
9. `c_ai_op_log` 是审计事实源，只回答“发生过哪些技术/业务操作、链路如何排障”，不得直接作为辅诊功能调用次数统计源
10. 服务端自身产生的成功代理日志必须可靠落库；若成功调用上游后审计日志写入失败，接口最终返回业务失败。失败代理调用的补充审计日志写入失败时必须 error 级别记录完整异常，保留原始业务失败语义。
11. `ai_proxy / speech_proxy` 的服务端实测 `provider/model/durationMs/firstTokenMs` 保存在现有 `payload_json`；日志查询响应把这些字段解析为只读展示字段。该方案兼容现有数据库结构，不新增对现场 schema 的部署前置要求。

代理日志补充约束：

1. 服务端对 `/v1/ai/chat`、`/v1/ai/speech/*` 等上游代理请求，除了成功/失败结果外，还应在 `payload_json` 中保留请求元数据、实际上游请求体与上游回文，便于排障；桌面端 AI trace 生成的操作日志也必须在 `details.requestPayload/responsePayload` 中保留完整业务出入参。
2. `speech_proxy` 日志的原始录音不得写入 `payload_json`；录音文件单独落盘，表中通过 `audio_file_path` 指向对应文件
3. API Key、Bearer Token 等凭据不得入库；除此之外，业务请求正文与回文可按原文保留
4. 管理端日志页应支持按 `ai_proxy`、`speech_proxy` 等代理日志类型筛选与查看详情

### 5.4 功能调用事件链路

1. 功能调用事件是面向统计的业务事实源，独立于审计日志和问诊用户日志。
2. 桌面端在用户真实触发功能时调用 `POST /v1/client/feature-events/batch`，一次明确功能调用只提交一条事件。
3. 所有功能事件必须携带稳定 UUID `eventId`；服务端统一规范化该 ID，以 `featureCode + eventId` 派生版本化安全幂等键，并按 `idDevice + 派生键` 幂等入库到 `c_ai_feature_event`，客户端离线重试或接口重试不会重复计数。事件同时保存独立的 `id_his_org/na_his_org`，用于 HIS 机构统计，后台 `id_org/id_region` 仍由设备鉴权决定。
4. 事件固定使用 `featureCode` 表示产品功能，服务端统一映射展示名：语音问诊、智能问诊、报告单解读、聊天、AI诊断鉴别、AI推荐诊断、AI推荐用药、AI推荐检查、AI推荐检验、AI推荐处置、AI推荐治疗方案、知识库使用。
5. `traceId`、`sessionId`、`consultationId` 都不进入功能统计持久化；AI 技术链路关联只保留在独立审计日志，避免通过功能统计表跨表反查临床上下文。
6. 统计口径按用户显式功能入口统一：智能问诊、语音问诊、报告单解读、聊天、知识库使用按主功能入口计数；知识库批量检索只按一次用户检索动作计数，不按内部拆开的多个查询词累加；诊断鉴别和推荐诊断/用药/检查/检验/处置/诊疗方案推荐只统计医生显式触发的独立辅助入口，不统计智能问诊或语音问诊主流程内部自动生成的 AI trace。来自 HIS Bridge 的入口在桌面端接诊上下文校验通过并准备打开目标界面时即按成功调用入库；同一就诊再次显式触发入口按新调用计数，只有同一条已入队功能事件的离线重试或接口重试通过稳定 `eventId` 去重。
7. 管理端“辅诊功能”统计只读 `c_ai_feature_event`；`c_ai_op_log` 保留为排障与审计，不再承担统计推断。
8. 功能事件只把重复幂等上报计入 `skipped`；不支持的 `featureCode`、缺失或非法 UUID `eventId` 必须计入 `rejected` 并返回拒绝明细，避免统计漏数被静默掩盖。旧客户端 payload 只为兼容接收并丢弃，不参与统计。

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

### 5.6.1 医学助手日志

1. 管理端 `/medical-assistant-logs` 对独立医学助手对话提供按次查询、CSV 导出和输入/输出对照详情；复用 `c_ai_op_log` 的服务端代理审计记录，限定 `ai_proxy + ai + chat_panel + chat-stream/chat`，不混入问诊日志或客户端点击事件，不改变功能统计口径。
2. `modules/assistantlog` 负责只读查询和安全展示投影。数据库分页、计数、医生姓名与后台/HIS 机构筛选使用 Oracle/GaussDB 双实现；不新增表和依赖。列表仅返回末条 user 消息的 160 字预览；详情返回该条完整文本、图片数量和 AI 原始回答文本，不返回系统提示词、历史轮次、图片 base64、上游原文或配置地址。
3. 桌面端 `llm.ts` 仅在 `sourceModule=chat_panel` 时从 `feedbackContext` 读取医生/机构快照，作为 `/v1/ai/chat` 的可选元数据传入；服务端只写审计载荷，不向上游发送这些字段。后台机构始终由设备鉴权确定。历史缺失医生显示未记录，不根据设备当前用户推断。
4. 时间默认本周（服务端本地日期，周一至周日），支持日/周/月/季度/年度及自定义日期，结束日期按次日零点排除。CSV 沿用原型列：序号、机构、医生姓名、使用时间、查询内容输入；通过事务内数据库游标逐条导出命中记录（最多 1000 条，超限明确拒绝），避免同时加载大量含图片的审计载荷，含 UTF-8 BOM、规范引号及公式注入防护。
5. 详情保留成功/失败状态，失败或不完整回答明确标识。问答按纯文本渲染，不执行 AI 输出的 HTML。所有接口继续由 `AdminAuthFilter` 保护，机构统计专用账号不开放临床全文日志。

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

### 5.9 PHIS 门诊病历质控链路

管理端 `/emr-qc` 使用路由元数据 `containedLayout` 启用壳层内容区定高布局。列表按容器宽度分配列宽，仅表格内容区域滚动，标题、筛选与分页固定；详情固定返回操作、患者摘要及两侧面板标题，病历正文与质控参考各自内部滚动。桌面端双栏按约 `43% / 57%` 分配，将更多宽度留给问题处理；窄屏使用上下两个独立滚动面板，不回退为整页滚动。左侧在存在安全预览时默认展示经清理和脱敏的 PHIS 原始排版，并允许用户手动切换至服务端归一化章节视图；PHIS 页眉只保留原文自带的单条分隔线，服务端为可识别章节写入非业务、只读的显示标记，选中问题时即使章节内容为空也能在原排版中高亮真实标签。右侧采用紧凑评分概览、默认折叠的维度评分及按严重程度筛选的问题卡片；非编辑态的问题说明与修改建议在宽屏下左右排列，窄屏再回落为上下排列，以减少单条问题高度。选择问题后，页面按章节名、评分维度与脱敏原文摘录解析目标：存在 PHIS 安全预览时始终保留机构标题、病历标题、患者信息及所有原文章节，仅在原排版中高亮匹配章节，不再自动切换到会省略空章节的归一化视图，也不重复显示“当前关联”提示条；只有无 HTML 预览时才使用归一化章节联动。患者基础信息问题仍高亮上方摘要，整份病历问题通过右侧选中态表达，不伪造原文。该联动只作用于显示状态，不修改问题、病历、评分输入或哈希。

1. 管理端 `/emr-qc` 通过 `/admin/api/emr-qc/*` 调用服务端，不直接访问 PHIS；浏览器不接触 `tk`，也不接触 PHIS 服务标识。
2. `modules/phisemr` 从当前管理员令牌关联的进程内会话取出 PHIS Cookie，按固定服务/方法白名单查询门诊病历列表和详情，把 `xmlContent` 归一化为按章节排序的纯文本视图（章节名、字段名、字段值），并剥离签名、HTML 样式和未知字段。
3. 服务端按管理员登录机构/角色作用域强制列表范围；列表默认近 7 天，日期跨度最长为起始日期顺延 1 个自然年，默认 10 条、最大 100 条。“全部”查询的病历条件由 PHIS 执行并直接使用其页码、每页条数和总数，取消全量拉取及 1000 条上限；只为返回页面中的病历加载本平台质控投影。“已质控”使用本机构病历基础快照在本地数据库分页，包含可能已经过期的结果；需从 PHIS 全部列表重新打开核验最新状态。旧记录缺少快照时使用就诊时间近似筛选、显示姓名掩码，并禁止直接操作；患者或医生等条件可能无法匹配旧记录，须从全部列表重新质控或授权回填。为支持查询、展示和再次脱敏，质控时保存患者姓名、性别、年龄、医生筛选 ID 和 PHIS 创建时间，身份证号和病案号使用现有服务端 AES 配置加密保存；不保存明文身份证、病案号或正文。页面筛选创建日期、开立医生、患者姓名与质控状态，暂不显示科室筛选；开立医生候选按 PHIS 全院查询使用当前会话获取并按当前机构裁剪。
4. 单份质控：读取详情归一化文本 → 计算输入 SHA-256 → 脱敏并补充不含身份值的基础信息可用性、就诊上下文及安全预览纯文字 → 调用现有 AI 配置生成维度评分与发现 → 在同一事务边界写入记录投影与发现明细，并写一条操作审计。AI 或上游失败时不写部分结果，返回明确错误。
5. 批量质控：创建任务后由有界线程池逐份执行，记录处理进度与逐份结果；任务只对当前登录管理员可见，重复创建同一批记录时返回提示而不是静默并行覆盖。批量与单份复用同一质控服务，不产生两套评分逻辑。
6. 结果展示：列表展示患者/医生/科室/就诊时间、质控评分、有效问题数与最近质控时间；详情左侧显示归一化病历纯文本，右侧按维度评分与发现（严重程度、章节、原文摘录、问题说明、修改建议、扣分及忽略状态）。发现中的原文摘录必须来自脱敏后的归一化文本，忽略项保留展示但不计入有效问题数和高危数。
7. 质控维度与规则版本由服务端代码维护（首期内置，不提供配置页）；2026-10-26 起采用 9 维度、满分 100 的门诊病历评分表（见 3.1.4 第 13 条），维度得分按各维度满分计分、总分由服务端求和。结果标记「AI 书写参考」，不展示为人工审核结论，不生成 PDF、不打印、不调用 PHIS 任何写接口。
8. 人工调整：详情的质控结果面板在已有结果上提供内联「人工调整」，不打开独立弹窗；可修改各维度得分、问题明细与每条扣分，忽略/恢复问题并根据有效问题重新计算维度得分，保存时必填调整理由。服务端校验版本、病历内容、扣分、维度范围和忽略状态后重算分数，生成新分析版本并保留 AI 原始结果；页面标记「人工调整」并展示调整人、时间、理由与 AI 原始总分。
9. 会话失效、角色未绑定机构、机构越权、PHIS 超时或结构异常均返回稳定业务错误码；管理端据此提示重新登录或稍后重试，不把上游异常展示为「无病历」。

### 5.9.1 病历 HTML 预览（2026-10-26）

用户授权以 PHIS 原有 HTML 排版提供只读预览。详情响应新增 `htmlPreview`（清理后的完整 HTML 文档，缺失或无正文时为空字符串），不返回原始 `htmlContent`。服务端通过固定版本 jsoup 白名单保留段落、标题、表格、合并单元格与受限行内排版，去除脚本、图片/签名、事件、URL、表单、嵌入内容及外部资源；文本中的身份、联系方式和已知地址在解码后脱敏，覆盖跨标签拆分。原始预览 HTML 不落库、不记录日志、不直接发送 AI；授权后提取安全预览的脱敏纯文字用于 AI 质控，且其文字变化参与内容哈希；预览不可用时回退结构化章节。

预览复用 PHIS 打印预览的受控展示语义：在安全清洗前移除 `data-printhidden=true`、`display:none`、`hidden`、标签标记、非打印节点和占位节点，去除标题必填星号，并仅对结构化字段中明确启用 `PRINTVALUE` 的字段按文章/字段 ID 回填 `TEXT`。服务端不加载或执行 PHIS 的 `printscript`，不复用分页、打印控件或动态脚本运行环境。随后使用上游结构化字段元数据识别空字段占位符，去除模板大括号并保留实际填写值；男患者仅隐藏内容为空的月经史独立段落，不能隐藏真实记录或未知性别记录。预览排版处理作用于展示副本；安全预览的脱敏纯文字也用于质控和内容哈希，样式变化不影响哈希。

前端仅通过 `sandbox=""`、`referrerpolicy="no-referrer"` 的 iframe `srcdoc` 展示该文档，CSP 禁止脚本、网络、表单、基地址与外部资源，只允许清理后的内联样式；不使用 `v-html`。无 HTML 时保留原有纯文本回退。两侧面板内部滚动与固定标题继续生效，评分展示仅作 AI 书写参考。

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
5. 客户端使用情况：
   - 管理端提供独立只读列表，固定展示机构名称、医生名字、工号、安装客户端时间、当前使用的客户端版本、最近活跃时间
   - 列表按“后台机构 + HIS 机构 + 真实工号”识别医生，不按设备行统计；真实工号固定读取 SDK handshake 的 `urt.personCd`，缺少真实工号的旧事件不进入列表
   - 数据事实源为 `c_ai_feature_event` 中医生实际触发的功能事件；事件产生时固化 `cd_doctor`、`na_doctor`、`client_version`、`id_his_org/na_his_org`
   - 当前版本取最近一次功能交互事件的版本；“安装客户端时间”表示该医生在当前版本上的首次功能交互时间，不表示操作系统安装包执行时间；最近活跃时间取最近一次功能交互时间
   - `/admin/api/client-usage` 在数据库层执行分页并支持按机构、医生、工号或版本搜索；导出上限为 50,000 条，超过时明确拒绝而不截断
6. 综合概况统计分析（`modules/analytics`）：
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
   - 萧山部署的“辅诊功能”采用隔离专版：管理端 `/function-usage` 加载 `XiaoshanFunctionUsageView`，专版后台位于 `modules/xiaoshananalytics` 并只暴露 `/admin/api/xiaoshan-analytics/*`；原 `FunctionUsageView`、`modules/analytics` 及 `/admin/api/analytics/function-*` 保留通用 12 类口径，不因萧山需求改写
   - 萧山专版固定展示语音问诊、慢病配药、报告回诊、报告解读、医学助手 5 类。语音问诊、慢病配药、报告回诊、报告解读统一读取 `c_ai_user_consultation_log` 中激活的 `voice`、`chronic_refill`、`report_consultation`、`report_interpretation`，一条问诊日志计一次调用，并与用户日志按相同 HIS 机构、时间和场景透视后的结果一致；医学助手因没有对应问诊日志，继续读取 `c_ai_feature_event` 中成功的 `chat` 功能事件。专版查询先把问诊日志和医学助手事件归一为功能名、发生时间、医生/设备、后台机构、区域和 HIS 机构，再统一筛选、排行与按日聚合；不得读取 `c_ai_op_log`
   - 萧山专版功能使用率的分母固定为 5；使用医生数在两类事实中都优先按医生 ID 去重，缺少医生 ID 时回退设备 ID。专版筛选、汇总、排行、趋势、分页明细与 Excel 导出必须复用同一归一化查询条件。BBP 非系统账号的所有萧山专版查询和导出都由服务端强制锁定 `hisOrgId=currentUser.bbpOrgId`、清空区域与后台机构条件并拒绝跨机构参数；管理端隐藏区域筛选并将 HIS 机构锁定为登录机构，不能只通过放行 `/admin/api/xiaoshan-analytics/**` 绕过机构范围约束
   - 综合统计中的功能调用指标和机构分布、辅诊功能统计按 `c_ai_feature_event.id_his_org` 过滤；问诊指标按 `c_ai_user_consultation_log.id_his_org` 过滤。用户活跃度按 `c_ai_user_consultation_log.id_doctor` 识别医生，同一医生的多台设备合并为一人，缺少医生 ID 的日志不进入人数。筛选范围内历史上出现过的医生作为活跃率分母，再按所选时段是否存在问诊区分活跃与不活跃；该页的活跃医生数与统计分析的活跃医生数使用相同的时间、区域、平台机构和 HIS 机构过滤口径

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
   - 独立“客户端使用情况”不复用设备分页，而是按 `c_ai_feature_event` 的医生身份与版本交互事实聚合
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
   - 按用户真实功能调用记录统计事件，关键列包括 `feature_code`、`feature_name`、`event_action`、`idempotency_key`、`trace_id`、`consultation_id`、`session_id`、`id_doctor`、真实工号 `cd_doctor`、`na_doctor`、后台机构、HIS 机构、事件发生时的 `client_version` 与事件时间
   - 通过 `id_device + idempotency_key` 保证同一设备的同一功能调用只计一次
   - 医生使用情况以“后台机构 + HIS 机构 + `cd_doctor`”聚合，最近事件版本为当前版本，并在该版本内取最早 `event_time`；缺少真实工号或客户端版本的历史事件不参与该列表
   - 索引：`idx_c_ai_feature_event_time` / `_feature` / `_doctor` / `_org` / `_idem` / `_usage`
13. `c_ai_inpatient_emr_tpl_cache`
   - 保存住院 HTML 模板与字段解析缓存，支持字段生成类型、提示词、启停和逻辑删除维护
14. `c_ai_outpatient_emr_tpl_snapshot`
   - 保存门诊模板对不可变版本，以 `id_org + template_id + template_hash` 唯一，只包含模板原文、确定性解析快照和接收元数据
15. `c_ai_outpatient_emr_tpl_mapping`
   - 保存门诊模板版本字段映射覆盖，以 `id_snapshot + field_id` 唯一；`record_field / projection_mode` 同时为空表示管理员显式设为未映射
   - 覆盖记录不保存病例数据，不修改模板快照；客户端命中和管理端详情在内存中叠加覆盖
16. `c_security_rejection_log`
   - 记录设备鉴权、请求签名、强制更新门禁和实时语音握手等安全拒绝事件
   - 关键列包括 `rejection_type`、`request_method`、`request_path`、`client_ip`、`id_device`、`cd_device`、`id_org`、`request_id`、`reject_reason`、`reject_detail`、`has_signature`、`timestamp_header`、`nonce_header`、`client_version`、`update_channel`
   - 索引：`idx_c_security_rej_time` / `_type` / `_ip` / `_device` / `_path`
17. `c_ai_user`
   - 激活账号通过 `uk_c_ai_user_code_active` 保证 `cd_user` 唯一，用户资料与角色映射替换在同一事务内完成
   - `sd_status` 表示启用/停用，管理端启停只修改该字段；`fg_active` 仅表示逻辑作废，不作为日常启停入口
18. `c_ai_role`
   - 激活角色通过 `uk_c_ai_role_code_active` 保证 `cd_role` 唯一
19. `c_ai_user_role`
   - 激活映射通过 `uk_c_ai_user_role_active` 保证 `id_user + id_role` 不重复
20. `c_ai_user_ai_permission`
   - 保存 PCIE 管理的人员 AI 使用权限，BBP/PHIS 人员字段仅作为身份锚点和显示快照
   - 以 `tenant_id + org_id + subject_key` 保证同一机构人员只有一条激活权限记录
   - `sd_status='1'` 表示允许使用，`sd_status='0'` 表示已撤销；不存在记录与已撤销均按无权限处理
   - `person_id`、`user_id`、`person_cd` 建立查询索引；PHIS 请求必须同时携带机构 ID，并至少使用 `person_id` 或 `user_id` 之一，`person_cd` 只能作为一致性校验字段
21. `c_ai_bbp_admin_grant`
   - 保存 PCIE 对 BBP 人员的后台访问授权，不保存 BBP 密码、Cookie 或人员主数据副本
   - 以 `tenant_id + org_id + bbp_user_id + role_code` 保证同一机构人员同一后台角色只有一条激活授权记录
   - 当前允许授予 `ORG_ADMIN` 与 `ORG_ANALYST`；`sd_status='1'` 表示允许以对应 PCIE 机构角色进入后台，`sd_status='0'` 表示已撤销
   - 保存人员姓名、登录名、人员 ID 仅用于管理端显示和审计快照，登录校验以稳定的 `bbp_user_id`、租户和机构为准

22. `c_ai_emr_qc_record`
   - 保存每份门诊病历的最近质控投影，以 `id_org + id_medrecdoc` 唯一；`id_medrecdoc` 为 PHIS 文书 ID，不作为业务外键
   - 关键列：`his_org_id`、`id_tep`、模板名称、医生/科室、就诊时间、PHIS `update_time`、归一化文本 SHA-256、`latest_analysis_id`、总分、维度分 JSON、问题数与高严重度计数、模型与规则版本、质控时间
   - 患者姓名与身份证号只保存掩码值；不保存病历正文、HTML、XML 或上游原始响应
23. `c_ai_emr_qc_finding`
   - 保存每次质控分析的发现明细，以 `id_analysis + sort_no` 唯一；`id_analysis` 为分析 UUID，`id_qc_record` 关联记录投影
   - 关键列：`severity`（high/medium/low/info）、`category`（完整性/规范性/一致性/临床合理性等）、`rule_code`（确定性规则可空）、`section_name`、`excerpt`（原文摘录，限长）、`issue`、`suggestion`
   - 重新质控插入新的 `id_analysis`，旧分析保留用于追溯；记录表只指向最新分析，首期不提供历史结果页面
24. `c_ai_emr_qc_task`
   - 保存批量质控任务：`id_task`、`id_org`、创建人、`record_ids_json`（最多 50 份）、逐份进度 `items_json`、总数、已处理、成功、失败、状态（queued/running/completed/partially_completed/failed）、创建/开始/结束时间与截断后的错误说明
   - 进度按逐份处理结果回写，任务与创建管理员绑定；服务重启后未完成任务标记为失败，不自动重跑
25. `c_ai_emr_qc_review_log`
   - 质控操作审计：`id_log`、`id_org`、操作者、动作（analyze/batch_analyze/view_detail）、`id_medrecdoc`、`id_task`、`request_id`、发生时间与结果状态
   - 只记录动作与结果计数，不保存病历正文、摘录、患者身份或 AI 提示词/响应原文

扩展表如用户、角色、统计可在第二阶段补齐。

### 7.1 写入一致性与唯一性约束

多步业务写入必须以 Spring 事务作为边界，并以数据库唯一索引作为并发最终防线：

1. 用户新增、修改、停用：`c_ai_user` 与 `c_ai_user_role` 同事务提交或回滚；账号唯一性不只依赖代码检查。
2. 机构维护、角色停用、设备注册/维护：涉及主表和关联状态变化时同事务提交；机构编码必填且激活记录唯一，机构编码、设备编码、设备令牌、角色编码由数据库唯一索引兜底。
3. 反馈提交：上一版 `fg_latest` 降级、新版插入、首版 root 回填必须同事务完成；`uk_c_ai_feedback_latest_scope` 防止并发提交产生两个最新版。
4. 问诊日志保存：按 `consultation_id + consultation_type + id_device` 只对尚未结束的 `generated` 记录做事务化 upsert；`completed` / `abandoned` 记录保留为历史轮次，同一就诊再次生成时插入新记录。并发首次创建由唯一索引兜底，服务端在唯一冲突后重读未结束记录并重试一次更新。
5. 现场旧库一次性迁移添加唯一约束前必须先清理重复激活数据；迁移脚本应在发现重复时中止并提示具体对象。
6. BBP 后台管理员授权的启用/撤销与操作日志必须同事务提交；并发写入由 `c_ai_bbp_admin_grant` 的激活唯一索引兜底，不能在数据库唯一键异常导致事务失效后继续重试查询或更新。
7. 门诊病历质控的「记录投影 + 发现明细 + 操作审计」必须同事务提交，任一步失败不留下部分结果；批量任务进度按单份独立事务更新，单份失败只累计失败数，不回滚已完成的其它病历。

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
4. 服务启动与 `featureEventSchema` readiness 使用当前应用连接执行 `c_ai_feature_event` 零行投影，并验证 `c_ai_schema_migration.feature_event_minimization_v1` 标记；缺表、缺列、缺标记或权限不足时拒绝启动，提示执行对应数据库的 `update_his_org_statistics.sql`。
5. 探针默认由 `floating-ball.feature-event.schema-validation.enabled=true` 启用；它不验证 `idx_c_ai_feature_event_usage`，该索引仍须由 DBA 在节点入池前确认。
6. 功能统计只保留功能编码、动作、医生/机构/版本和时间等结构化统计列；`consultation_id/trace_id/session_id` 置空，`payload_json` 固定为空对象。升级脚本会重建全表幂等键，必须在备份、停止写入、核查外部报表依赖并由 DBA 审核后，于维护窗口执行。
7. 功能动作、来源模块、场景和状态只接受受限字符集与列宽内的技术编码，避免通过结构化列旁路写入临床自由文本。
8. 门诊模板历史解析表的存量库使用各数据库目录下 `update_outpatient_emr_template_snapshot.sql`，只重建唯一索引为 `id_org + template_id + template_hash`，不修改或删除业务记录；执行窗口必须暂停模板快照登记。新建 Oracle/GaussDB 库直接由 `init.sql` 获得相同约束，达梦继续使用 Oracle 兼容基线加定向脚本。
9. 门诊病历质控四表 `c_ai_emr_qc_record / c_ai_emr_qc_finding / c_ai_emr_qc_task / c_ai_emr_qc_review_log` 只进入 Oracle 与 GaussDB 两套 `init.sql` 基线及其 `README.md`，首期不新增常驻升级脚本；存量库由 DBA 基于 `init.sql` 生成一次性迁移。缺表或缺列时仅 `/admin/api/emr-qc/**` 返回 503 与 DBA 迁移提示，不阻断其他后台能力。

## 9. 单体部署约定

本轮管理端改造采用“单体托管，不重写页面”的策略：

1. 保留现有 Vue 2 + Element UI 管理端实现，避免为“取消前后端分离”而重写成服务端模板。
2. 管理端源码迁入 `server` 模块，由同一套 Maven / Spring Boot 工程承载。
3. 发布结果为单个 Spring Boot 服务，前后端同端口、同域名、同进程。
4. 开发阶段允许保留 Vite 独立调试能力，但这不再是默认交付形态。
5. 管理端表单交互优先复用 `floating-ball` 现有设置页的分组式信息架构：按“基础信息 / 主模型 / 语音 / 知识库 / 审查模型 / 作用域与功能开关”分段展示，避免把所有配置字段平铺在单一网格中。
6. 语音配置页必须区分“服务端实际转写地址/密钥/模型”和“桌面端感知的 provider/model”：前者用于服务端上游语音协议选择与调用，后者用于 `floating-ball` 设置页展示与策略选择。
7. 服务端 AI / 语音上游出站请求允许通过 `floating-ball.ai.proxy.*` 配置显式走 HTTP 代理；在 macOS 开发环境下同时建议启用 Netty 的 native DNS 解析依赖，避免 Java 进程与终端 `curl` 的网络行为不一致。
8. AI 配置页应提供“服务端到上游 LLM”的单独测试入口，用于区分“floating-ball -> server”链路故障与“server -> LLM”链路故障。
9. 统一“病历模板”管理页使用 `/emr-templates`，同时承载住院模板缓存与门诊解析记录；早期仅住院页面名 `/inpatient-emr-templates` 不再注册，也不保留重定向。

## 10. 两慢病随访持久化

`modules/chronicdisease` 为医生桌面端提供高血压与 2 型糖尿病随访保存能力：

1. `ClientChronicDiseaseController` 暴露签名保护的 `/v1/client/chronic-disease/*`。
2. `ChronicDiseaseFollowUpRequest` 逐字段声明原始 `TcdVisitForm.getFormData()` 输出；不接受 `Map` 或可替代整条记录的任意 `payload`。原始字典值按字符串透传，服务端只校验实例代码能够确认的值域和联动规则。
3. `ChronicDiseaseFollowUpService` 负责 `sdVisitKind` 病种组合、阶段 `status`、数值范围、条件必填、多选互斥、药品空行过滤和 `X-Request-Id` 幂等；联合随访只插入一条记录。
4. `c_ai_chronic_followup` 固化 `id_phr/id_record/sd_visit_kind` 等检索锚点和完整 `form_data_json`。JSON 是强类型 DTO 的无损持久化结果，不是接收任意业务结构的入口。
5. Oracle 与 GaussDB 初始化基线同步维护表、注释、索引和唯一幂等索引；Oracle、GaussDB 与达梦 DM8 存量库分别提供同名定向升级脚本 `update_chronic_disease_followup.sql`。
6. 实际插入由 `ChronicDiseaseFollowUpWriter` 在独立事务中完成；并发唯一键冲突先回滚写事务，再由外层服务读取已提交记录，避免 GaussDB/openGauss 事务进入失败态后仍继续查询。
7. `POST /v1/client/chronic-disease/artifact-snapshots` 保存健康处方或年度评估打印前快照；DTO 使用固定字段和强类型确认项，服务端序列化确认项但不接受任意业务 payload，`requestId` 在机构内幂等。
8. `c_ai_chronic_artifact` 固化患者锚点、病种集合、数据截至时间、模板/路径/依据/规则版本、年度指标、医生确认项及打印医生。
9. 一期不增加管理端页面；正式模板与临床路径由代码仓库受审清单发布，AI 和医生均无规则发布权限。

## 慢病检查检验推荐代理一期

`modules/chronicdisease/examination` 提供签名 `/v1/client/chronic-disease/examination-recommendations/query`，直接复用两慢病 getJYJCPro 底层健康处方协议：服务端用 AK/SK、UTC+8 时间与 HMAC-SHA1 登录 `/api/login`，取得仅内存缓存的 token；将 `{idTypeCode:"01",idNo}` 用 hex SK 对应的 SM4/ECB/PKCS7Padding 加密，通过 `/api/wjjbgs/cy/getUserInfo` 查询并解密 data，仅规范化 inspectionItems。HMAC 的 key 是 SK 的 UTF-8 文本，SM4 的 key 是 SK 的十六进制解码，二者不能混用。使用 Java 8 适配的 Bouncy Castle 密码实现，不自行实现 SM4，不允许加密失败回退明文。

独立短超时 HTTP Adapter 对登录与查询均复用既有出站安全门、禁止重定向，不转发设备令牌，不记录 AK/SK、token、身份证或上游正文。token 单实例同步刷新，最长缓存一小时；HTTP 401/403 时清除并只重新登录查询一次，其他协议或业务错误不自动重放。配置变更使缓存失效；无患者数据缓存。未配置、协议异常、解密失败与上游失败明确返回不可用，不以空数组掩盖失败。直接调用真实上游，避免 rbmh-cdw 的 Mock 开关。

`RegionalExaminationHistoryProvider` 仅为健康大脑全区历史端口，默认固定 not_connected。接通健康处方推荐不代表全区90天历史已接通。配置前缀 floating-ball.chronic-examinations，enabled 默认 false，base-url/ak/sk 默认空，由受保护的服务端 service.env 注入，不进入 bootstrap 或客户端。无需新数据表。

## 住院病历日志

1. 管理端菜单和页面统一为“住院病历日志”，路径 `/inpatient-emr-logs`。范围仅为桌面端 `features/inpatient-emr`，家庭病床模板与普通住院模板共用链路，文书类型采用实际模板名称筛选，不以名称推断患者业务归属。
2. `modules/inpatientlog` 使用 `c_ai_inpatient_emr_log` 保存每个设备/生成 UUID 的最新完整快照，`c_ai_inpatient_emr_log_event` 保存不可变事件。每次生成或重新生成使用新 UUID，上一轮标为 superseded；最小化不改变日志，明确放弃才写 abandoned。没有可靠 HIS 文书主键时不伪造 recordId。
3. 签名 `/v1/client/user-logs/inpatient-emr/events` 接收强类型完整快照。快照包含医生/HIS 机构/患者身份、住院锚点、模板 ID/名称/原始 HTML 的 SHA-256、生成时间、病历书写时间、来源文本、按模板顺序排列的字段及 AI 原稿/最终稿。模板原始 HTML、录音、系统提示词、完整 HIS raw 对象和密钥不上传。非 AI 字段保留其来源，不冒充 AI 生成。
4. 设备/后台机构从鉴权获得，客户端身份快照不参与授权。父记录按设备+生成 UUID 唯一，事件按设备+事件 UUID及轮次+序号唯一；写事务锁定父记录，校验锚点一致，按最大序号更新投影。重复事件同正文幂等、不同正文拒绝；迟到旧事件只入历史，不覆盖新状态。唯一冲突必须退出并回滚独立写事务后再重试一次，兼容 GaussDB 事务失败态。
5. status 分别记录 generating、waiting、generated、generation_failed、fallback、writeback_pending、writeback_success、writeback_failed、abandoned、superseded。只有严格匹配住院 ID 和待回写 requestId 的 HIS 成功回执才是回填成功，不宣称已签署或数据库已落库；无回执保持待确认。失败后可重试回填，最终稿冻结于发起回填瞬间，不受随后编辑或迟到回调影响。
6. 客户端 `inpatientEmrLog` 组装生成轮次快照，独立 `inpatientEmrLogOutbox` 使用 IndexedDB 按服务地址+机构编码+设备编码隔离保存待上传事件；按序通过既有签名 regionalPost 补传、确认后删除，不保存 token。最多100条/8MiB，禁止静默淘汰；存储失败或上传失败在住院页显示待补传/失败提示及重试入口，内存保留尚未落盘的事件。进入住院工作区时恢复补传，当前运行期后台每30秒重试；最小化仍保活。关闭客户端前未能落盘的事件不能承诺恢复。
7. 管理端分页默认本周，时间按生成开始时间筛选，同时独立显示病历书写时间；支持机构、医生、患者、模板、状态。详情纯文本按字段分区显示生成依据、AI原稿、最终稿、字段差异和事件时间线。CSV只导出列表元数据，最多1000条，超限拒绝；日志全文权限不开放给机构统计专用账号，BBP机构管理员限制本机构。
8. Oracle/GaussDB 初始化基线同步建表、注释、唯一键和查询索引。按用户 2026-10-21 的明确交付要求，两套 SQL 目录各保留定向一次性 `update_inpatient_emr_logs.sql`，只创建住院日志两表及其约束、索引和注释，DDL 与对应 `init.sql` 一致；执行前校验当前 schema 的应用基础表及同名对象，已存在对象时拒绝继续，不静默跳过未知结构。Oracle 遇错停止但已完成 DDL 不可回滚；GaussDB PostgreSQL 兼容模式采用事务。执行方法、验收及失败恢复见 `docs/inpatient-emr-logs-migration.md`。历史代理日志不自动伪造为有完整闭环的住院日志。
