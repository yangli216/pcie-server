# AGENTS.md

## WeKnora 知识服务

新知识检索使用服务端批准集合与设备机构，验证原文片段SHA-256；第三方API Key只保存在受控部署配置。新增Provider必须复用出站安全和默认Fake测试，不绕过签名、不将知识入库自动当批准。WeKnora为用户授权的独立组件，其基础设施不成为PCIE Server单体启动前置条件。执行知识service/controller/security测试及Maven构建，真实部署/召回/医学审核单列。

供应商响应回归须使用 WeKnora v0.8.2 的真实整数 `match_type` 形态，验证官方枚举转客户端字符串、既有文本兼容及未知/非法类型拒绝，不能只使用字符串 Fake 响应声称兼容实际服务。

`deploy/weknora` 提供固定版本的本机 Compose 启动配置和随部署交付的知识 OpenAPI；机器契约必须与工作区协同版本逐字节一致。只绑定环回端口，使用独立项目和持久卷；密钥与实际配置写入被忽略的 `.local` 目录，不覆写现有 PCIE Server 配置。执行方式见该目录 README，运行状态、模型配置和真实检索分别报告。

本机真实接入用 Spring additional-location 加载私有连接与测试集合配置，保留已有数据库/AES/AI 参数。默认对非医学合成工程资料做技术批准；2026-10-08 用户另授权公开原始资料实际测试，须建立明确标注的独立本机测试集合，核对官方出处、版次、页码、解析原文及片段 SHA-256。该检索发布批准不能表述为医学审核通过，也不能自动接入公卫生成或 HIS 写回。模型安装须核对官方固定版本和文件摘要，不能用 Fake 嵌入宣称真实召回。默认测试不调用真实模型。


`pcie-server`（全医慧助服务端 / PCIE Server）项目的协作规则。

本项目是“全医慧助（PCIE）”桌面端的配套后台，当前采用：

1. `server/`：Spring Boot 2.7 + Java 8 + MyBatis-Plus；数据库支持 Oracle 19c 与华为高斯 GaussDB/openGauss PostgreSQL 兼容模式，新增数据库适配优先保证 GaussDB
2. `server/src/main/admin/`：Vue 2 + Element UI 管理端源码，由 `server/` 统一托管
3. `API.md`：远端 `/v1/*` 契约文档

## 必读顺序

1. 先读 [ARCHITECTURE.md](./ARCHITECTURE.md)
2. 再读 [API.md](./API.md)
3. 涉及需求来源时，读 [../rbmh-ai-platform/PRD.md](../rbmh-ai-platform/PRD.md)
4. 涉及桌面端真实调用时，读：
   - [../floating-ball/src/services/regionalClient.ts](../floating-ball/src/services/regionalClient.ts)
   - [../floating-ball/src/services/llm.ts](../floating-ball/src/services/llm.ts)
   - [../floating-ball/src/services/templateService.ts](../floating-ball/src/services/templateService.ts)
   - [../floating-ball/src/services/medicalData.ts](../floating-ball/src/services/medicalData.ts)
   - [../floating-ball/src/services/promptOverride.ts](../floating-ball/src/services/promptOverride.ts)
   - [../floating-ball/src/services/auditUploader.ts](../floating-ball/src/services/auditUploader.ts)

## 强制流程

1. 文档先行：架构、接口、数据模型、目录职责变化，先改文档再改代码。
2. 契约优先：`/v1/*` 契约变更时，必须同时更新 `API.md` 与 `floating-ball` 调用方。
3. 交付顺序：`ARCHITECTURE/API/AGENTS -> 代码 -> 构建/测试验证`
4. 管理端目录、构建命令、托管入口变化时，必须同步更新 `ARCHITECTURE.md` 与本文件。

## 硬约束

1. 不允许把本地 `/api/consultation/*` 逻辑直接搬进本项目；本项目只负责远端 `/v1/*` 与管理端。
2. 不允许只按旧 PRD 实现远端接口而忽略 `floating-ball` 当前真实字段。
3. 设备鉴权必须使用 `Authorization: Bearer {deviceToken}`，不得私自改成其他客户端认证方式。
4. `bootstrap`、`templates/mappings delta`、审计事件结构必须优先兼容 `floating-ball` 现有实现。
5. 未经明确要求，不引入 Redis、RocketMQ、微服务拆分等额外依赖。
6. **请求签名校验禁止绕过**：`DeviceAuthFilter` 和 `RealtimeSpeechHandshakeInterceptor` 必须校验 ECDSA P-256 签名；新增 `/v1/*` 接口必须经过 `DeviceAuthFilter`，不得私自添加绕过路径。
7. **品牌与运行兼容标识禁止混改**：正式服务端名称统一为“全医慧助服务端（PCIE Server）”，仓库与 Maven 工程使用 `pcie-server`；Java 包 `com.regionalai.floatingball.server`、`floating-ball.*` 配置键、`FB_*` 环境变量、数据库对象及现有现场部署目录属于兼容契约，未经迁移方案和现场验证不得改名。

## 数据库 SQL 交付规则

1. `server/src/main/resources/sql/oracle/init.sql` 与 `server/src/main/resources/sql/gaussdb/init.sql` 是对应数据库的业务 schema 初始化基线，新增表、字段、索引、注释和默认种子数据必须同步折叠进对应基线。
2. Oracle 目录默认只保留 `bootstrap.sql` 与 `init.sql`；GaussDB 目录默认只保留 `init.sql`。不得在仓库中长期保留 `upgrade_*.sql` 补丁脚本。
3. 现场旧库无法重建时，由 DBA 基于当前对应数据库 `init.sql` 和现场库结构生成一次性迁移脚本；该脚本不作为常驻工程资产提交，除非用户明确要求纳入版本交付。
   - 用户于 2026-10-21 明确要求交付住院病历日志一次性迁移：两套 SQL 目录允许保留 `update_inpatient_emr_logs.sql`，仅包含该功能新增对象，必须与对应 `init.sql` 的 DDL 保持一致并维护一致性测试，不扩展为通用补丁链。
4. 修改任一数据库 `init.sql` 后必须同步更新对应目录 `README.md`，并维护 schema 测试，防止补丁文件回流。
5. 运行时 SQL 新增数据库相关函数、分页尾句、日期分组、JSON 读取时，必须通过数据库方言封装或明确提供 Oracle/GaussDB 双实现，不得只写 Oracle 专属 SQL。

## 当前阶段目标

1. 第一优先级：跑通 `floating-ball` 的远端客户端接口。
2. 第二优先级：补最小管理端 CRUD，支撑令牌、配置、症状模板和日志管理。
3. 第三优先级：逐步补用户、角色、统计等平台能力。

## 版本发布与版本号

1. 后台发布版本以 `server/pom.xml` 的 Maven 项目版本为唯一主版本源；管理端 npm 包作为内嵌前端构建元数据，不作为后台 release 版本源。
2. 正式发版使用 Maven Release Plugin，例如从 `0.1.0-SNAPSHOT` 发布 `0.1.0` 并进入 `0.1.1-SNAPSHOT`：
   `mvn -f server/pom.xml -DreleaseVersion=0.1.0 -DdevelopmentVersion=0.1.1-SNAPSHOT -Dtag=v0.1.0 release:prepare`
3. `release:prepare` 会执行 `test`、提交正式版本、创建 `vX.Y.Z` 标签，并把 `server/pom.xml` 推进到下一个 `*-SNAPSHOT` 开发版本；当前配置 `pushChanges=false`，发布后由人工执行 `git push && git push origin vX.Y.Z`。
4. 仅调整开发版本号时使用 Maven Versions Plugin：`mvn -f server/pom.xml versions:set -DnewVersion=0.2.0-SNAPSHOT`。

## 最小质量门禁

1. `server` 至少执行 `mvn -f server/pom.xml test` 或 `mvn -f server/pom.xml package`，该流程会自动执行管理端 `npm ci` 与 `npm run build`
2. 若只做管理端单独联调或需要定位前端构建问题，可额外执行 `npm --prefix server/src/main/admin run build`；`npm rebuild` 只重建依赖包，不能替代 Vite 业务源码构建
3. 新增生产代码默认同步新增或更新单元测试；确实不适合自动化覆盖时，交付说明必须写明原因和替代验证方式
4. 新增或修改核心 service/controller/security 逻辑时，默认新增或更新 JUnit 测试；确实不适合自动化时，必须在交付说明中写明原因
5. 修改 `/v1/*` 契约、设备鉴权、请求签名、AI 代理或客户端 delta 链路时，必须按工作区 [TESTING_STRATEGY.md](../TESTING_STRATEGY.md) 补充对应单元测试、集成测试或联调记录
6. 修改 Maven release/versions 配置或版本号规则时，至少执行 `mvn -f server/pom.xml test`；如需验证发版流程，必须在干净工作区执行 `mvn -f server/pom.xml -DdryRun=true -DreleaseVersion=X.Y.Z -DdevelopmentVersion=X.Y.(Z+1)-SNAPSHOT -Dtag=vX.Y.Z release:prepare` 后执行 `mvn -f server/pom.xml release:clean`
7. 若无法完成构建或测试，必须说明阻塞原因，并补充静态审查结论
8. 小山现场发布必须通过 `scripts/publish-xiaoshan.sh` 执行 Maven `clean package` 并校验 JAR 内管理端入口及其静态资源；不得跳过构建或直接复用 `server/target` 中来源、时效不明的 JAR
9. 萧山环境私有配置放在对应部署目录的 `service.env`（当前运行用户所有、权限 `600`），不得提交凭据；发布保持该文件，配置变更须单独备份。脚本修改后执行 `python3 scripts/test_publish_xiaoshan.py`，覆盖环境隔离、配置保护、显式失败与命令失败回滚；现场验收必须检查 readiness 和管理端入口。

## 管理端 UED 与组件规则

1. 管理端面向医疗 IT 运维和平台管理员，默认采用高信息密度、低装饰、状态明确的后台体验，不做营销式首屏、说明型大卡片或单一色系装饰。
2. 公共布局组件放在 `server/src/main/admin/src/components/layout/`，公共 UI 组件放在 `server/src/main/admin/src/components/ui/`；页面内仅保留业务编排和少量页面专属样式。
3. 新增或重构统计、分析、活跃度、安全运营页面时，优先复用 `AdminFilterBar`、`TimeRangeFilter`、`MetricCard`、`ChartPanel`，避免重复手写筛选行、指标卡和图表卡片样式。
4. 新增或重构列表 CRUD 页时，分页统一使用 `AdminPagination`（每页 `10 / 20 / 50 / 100` 条），状态统一使用 `StatusPill`，编码/ID/密钥掩码统一使用 `CodeTag`，二选一启停类输入优先使用 `SegmentedSwitch`；页面不得直接重复拼装 `el-pagination`。
5. Element UI 表格继续保持弱分隔线、无竖线、无 `border/stripe`；如页面历史代码仍保留 `border/stripe`，改造该页面时必须同步移除。
6. 自定义动作必须使用 `button`，导航使用 `router-link`；不得用无 `href` 的 `<a>` 承载动作。图标按钮必须有 `aria-label`，自定义可点击卡片必须有键盘焦点与回车/空格触发。
7. 管理端文案与样式必须遵守 Web Interface Guidelines：保留可见 `:focus-visible`，占位/加载文案使用 `…`，长文本可截断或折行，禁用 `transition: all` 和无替代焦点样式的 `outline: none`。
8. 系统大框层固定使用 `admin-shell -> admin-shell__sidebar + admin-shell__main -> admin-shell__topbar + admin-shell__tabs + admin-shell__content`，不得在业务页面重写同义外框 div；侧栏、顶栏、页签栏分别维护在 `AdminSidebar`、`AdminTopbar`、`AdminTabBar`。

## 关键联调清单

1. `POST /v1/client/register` 返回的 `deviceToken` 能被 `floating-ball` 缓存并复用
2. `GET /v1/client/bootstrap` 返回结构兼容 `floating-ball/src/services/regionalClient.ts`
3. `GET /v1/client/prompts/delta` 返回结构兼容 `promptOverride.ts`
4. `GET /v1/client/templates/delta` 返回结构兼容 `templateService.ts`
5. `GET /v1/client/mappings/delta` 返回结构兼容 `medicalData.ts`
6. `POST /v1/client/audit/events/batch` 能接收 `auditUploader.ts` 当前上传结构
7. `POST /v1/ai/chat` 非流式和流式响应都能被 `llm.ts` 正常消费
