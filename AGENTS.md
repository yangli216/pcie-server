# AGENTS.md

全医慧助（PCIE，Primary Care Intelligent Expert）服务端工程 `floating-ball-server` 的协作规则。

本项目是全医慧助桌面端 `floating-ball` 的配套后台，当前采用：

1. `server/`：Spring Boot 2.7 + Java 8 + MyBatis-Plus；数据库支持 Oracle 19c 与华为高斯 GaussDB/openGauss PostgreSQL 兼容模式，新增数据库适配优先保证 GaussDB
2. `server/src/main/admin/`：Vue 2 + Element UI 管理端源码，由 `server/` 统一托管
3. `API.md`：远端 `/v1/*` 契约文档

## 必读顺序

1. 先读 [HARNESS.md](./HARNESS.md) 明确项目内执行流程和跨仓升级条件
2. 再读 [ARCHITECTURE.md](./ARCHITECTURE.md)
3. 再读 [API.md](./API.md)
4. 涉及需求边界时，以用户明确要求和当前代码为准；形成长期基线时写入本项目 `PRD.md`，不引用历史占位目录。
5. 涉及桌面端真实调用时，补读 [../development-harness/HARNESS.md](../development-harness/HARNESS.md)、[../floating-ball/HARNESS.md](../floating-ball/HARNESS.md) 和：
   - [../floating-ball/src/services/regionalClient.ts](../floating-ball/src/services/regionalClient.ts)
   - [../floating-ball/src/services/llm.ts](../floating-ball/src/services/llm.ts)
   - [../floating-ball/src/services/templateService.ts](../floating-ball/src/services/templateService.ts)
   - [../floating-ball/src/services/medicalData.ts](../floating-ball/src/services/medicalData.ts)
   - [../floating-ball/src/services/promptOverride.ts](../floating-ball/src/services/promptOverride.ts)
   - [../floating-ball/src/services/auditUploader.ts](../floating-ball/src/services/auditUploader.ts)

## 强制流程

1. 文档先行：架构、接口、数据模型、目录职责变化，先改文档再改代码。
2. 契约优先：`/v1/*` 契约变更时，必须同时更新 `API.md` 与 `floating-ball` 调用方。
3. 交付顺序：`HARNESS/ARCHITECTURE/API/AGENTS -> 代码 -> 构建/测试验证`
4. 管理端目录、构建命令、托管入口变化时，必须同步更新 `ARCHITECTURE.md` 与本文件。

## 硬约束

1. 不允许把本地 `/api/consultation/*` 逻辑直接搬进本项目；本项目只负责远端 `/v1/*` 与管理端。
2. 不允许只按旧 PRD 实现远端接口而忽略 `floating-ball` 当前真实字段。
3. 设备鉴权必须使用 `Authorization: Bearer {deviceToken}`，不得私自改成其他客户端认证方式。
4. `bootstrap`、`templates/mappings delta`、审计事件结构必须优先兼容 `floating-ball` 现有实现。
5. 未经明确要求，不引入 Redis、RocketMQ、微服务拆分等额外依赖。
6. **请求签名校验禁止绕过**：`DeviceAuthFilter` 和 `RealtimeSpeechHandshakeInterceptor` 必须校验 ECDSA P-256 签名；新增 `/v1/*` 接口必须经过 `DeviceAuthFilter`，不得私自添加绕过路径。

## 数据库 SQL 交付规则

1. `server/src/main/resources/sql/oracle/init.sql` 与 `server/src/main/resources/sql/gaussdb/init.sql` 是对应数据库的业务 schema 初始化基线，新增表、字段、索引、注释和默认种子数据必须同步折叠进对应基线。
2. Oracle 目录默认只保留 `bootstrap.sql` 与 `init.sql`；GaussDB 目录默认只保留 `init.sql`。不得在仓库中长期保留 `upgrade_*.sql` 补丁脚本。
3. 现场旧库无法重建时，由 DBA 基于当前对应数据库 `init.sql` 和现场库结构生成一次性迁移脚本；该脚本不作为常驻工程资产提交，除非用户明确要求纳入版本交付。
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
2. 若只做管理端单独联调或需要定位前端构建问题，可额外执行 `npm --prefix server/src/main/admin run build`
3. 新增生产代码默认同步新增或更新单元测试；确实不适合自动化覆盖时，交付说明必须写明原因和替代验证方式
4. 新增或修改核心 service/controller/security 逻辑时，默认新增或更新 JUnit 测试；确实不适合自动化时，必须在交付说明中写明原因
5. 修改 `/v1/*` 契约、设备鉴权、请求签名、AI 代理或客户端 delta 链路时，必须按工作区 [TESTING_STRATEGY.md](../TESTING_STRATEGY.md) 补充对应单元测试、集成测试或联调记录
6. 修改 Maven release/versions 配置或版本号规则时，至少执行 `mvn -f server/pom.xml test`；如需验证发版流程，必须在干净工作区执行 `mvn -f server/pom.xml -DdryRun=true -DreleaseVersion=X.Y.Z -DdevelopmentVersion=X.Y.(Z+1)-SNAPSHOT -Dtag=vX.Y.Z release:prepare` 后执行 `mvn -f server/pom.xml release:clean`
7. 若无法完成构建或测试，必须说明阻塞原因，并补充静态审查结论

## 管理端 UED 与组件规则

1. 管理端面向医疗 IT 运维和平台管理员，默认采用高信息密度、低装饰、状态明确的后台体验，不做营销式首屏、说明型大卡片或单一色系装饰。
2. 公共布局组件放在 `server/src/main/admin/src/components/layout/`，公共 UI 组件放在 `server/src/main/admin/src/components/ui/`；页面内仅保留业务编排和少量页面专属样式。
3. 新增或重构统计、分析、活跃度、安全运营页面时，优先复用 `AdminFilterBar`、`TimeRangeFilter`、`MetricCard`、`ChartPanel`，避免重复手写筛选行、指标卡和图表卡片样式。
4. 新增或重构列表 CRUD 页时，状态统一使用 `StatusPill`，编码/ID/密钥掩码统一使用 `CodeTag`，二选一启停类输入优先使用 `SegmentedSwitch`。
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
