# 南大通用 PostgreSQL 适配

分支：`feature/gbase-postgresql-adapter`，起点为服务端 `main` 的 `33b357b`。

目标为用户指定的南大通用 PostgreSQL 测试环境。产品、内核版本与兼容模式须用实际驱动和 `SELECT version()` 确认，不能从文件名推定为 8s/8c，也不能沿用 Informix/gbasedbt 的实验结果。本目录只保存配置说明与工程证据；真实地址、账号、口令、JAR 和日志放 `.local/`，不入 Git。

## 驱动准备与构建

当前使用用户后续提供的 `gbase-jdbc-5.0.0(1).jar`，文件大小 903,427 字节，SHA-256 为 `7d9343fa86a0446eff4b1af0ce845f5509b1e78ea6934e8e37d07e4984700a2b`。SPI 和实际类为 `cn.gbase.Driver`，`acceptsURL` 接受 `jdbc:gbase:`，拒绝 `jdbc:postgresql:` 与 `jdbc:opengauss:`；class major 为 52（Java 8）。包内残留 Maven 元数据为 `org.opengauss:opengauss-jdbc:5.0.3`，驱动版本字符串仍为未展开的构建占位符，不能据文件名或该字符串推定实际数据库版本。以原件 SHA-256 和下述实库 `version()` 共同识别；JAR 不入 Git 或公共 Maven 仓库。

本工程将该用户文件安装为本机坐标 `cn.gbase:gbase-jdbc:5.0.0`（按用户文件名标识），不覆盖 `org.opengauss` 下的官方驱动。该别名不表示厂商公开发布过此 Maven 坐标，也不掩盖上述元数据差异。

安装到本机 Maven 仓库：

```bash
mvn install:install-file \
  -Dfile='/path/to/gbase-jdbc-5.0.0(1).jar' \
  -DgroupId=cn.gbase -DartifactId=gbase-jdbc \
  -Dversion=5.0.0 -Dpackaging=jar -DgeneratePom=true
mvn -f server/pom.xml -Pgbase-postgresql package
```

`gbase-postgresql` Maven profile 以厂商 JAR 替换 openGauss JDBC，避免两个驱动混入同一运行包；类、测试报告及可执行包输出到独立 `server/target/gbase-postgresql/`，不复用默认 `target/classes` 或覆盖默认 JAR。默认 Maven 命令仍使用原 openGauss 依赖，无需厂商 JAR。

## 运行配置

| 配置 | 取值/边界 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `gbase-postgresql` |
| `FB_DB_TYPE` | `gbase-postgresql`，选择 PG SQL 与 PostgreSQL 分页 |
| `FB_DB_DRIVER` | 默认 `cn.gbase.Driver`，与提供介质 SPI 一致 |
| `FB_DB_URL` | 必填，使用厂商驱动确认接受的 URL；database 为 `phis`，schema 单独核实 |
| `FB_DB_USERNAME` / `FB_DB_PASSWORD` | 必填，仅环境变量或私有 additional-location 配置 |
| `FB_SERVER_PORT` | 独立测试端口，默认 18090 |
| `FB_LOG_PATH` | 默认 JVM 临时目录下 `pcie-gbase-postgresql/logs` |

不要使用 Informix 的 `gbase/gbase8s` 分页类型。该 Spring profile 不设置自动 SQL 初始化，不关闭 schema readiness、设备令牌或 ECDSA 签名，不覆盖已有数据库/模型配置。

## 验证顺序

1. 默认执行 `mvn -f server/pom.xml test`：管理端构建、Java 回归、方言/分页/配置/schema 与 JSON 统计检查。
2. 厂商 JAR 安装后，在私有环境注入连接变量（本机私有配置为 `.local/runtime.env`）并执行只读 JDBC 检查：

   ```bash
   PCIE_GBASE_JDBC_TEST=true mvn -f server/pom.xml -Pgbase-postgresql \
     -Dtest=GbasePostgresqlJdbcIntegrationTest test
   ```

   该测试只取产品、驱动、版本、当前 schema、兼容模式与事务能力，不读取业务记录或修改对象。默认 CI 不启用它。
3. 当前测试库实际 `sql_compatibility=A`。DBA 明确隔离空 schema 后，以同一账号和确定的 search_path 执行 `server/src/main/resources/sql/gbase-postgresql/init.sql`；首错即停。须在此实际兼容模式下核对类型、NULL/空字符串、日期及唯一索引，不能因为运行代码走 PG 方言就当作 PG 模式已验收。脚本仅包含业务 DDL/种子，不创建库/schema，不针对现有 `phis` schema 自动执行。逐项验证条件唯一索引的启用重复、停用多行、NULL、更新及并发冲突，并检查实际事务回滚。
4. 在隔离对象上验证 JDBC/ORM 中文、长 TEXT、NULL、空字符串、数值和日期往返、分页/统计/导出，保留有效 P-256 请求及缺签名/错误签名/重放拒绝证据。真实接口和管理端浏览器验证另记。

## 当前环境核对（2026-10-21）

本轮按用户要求运行服务并逐项核验现有功能。先以现有 `rbmh_ai` schema 启动，SQL 自动初始化和管理员密码重置均关闭，保留 schema readiness 与签名校验；本机仅监听 `127.0.0.1:18090`，运行日志和文件目录独立放 `.local/runtime/`。现有 schema 的表结构、只读查询、合成资料写入和真实上游业务分别记录，任何现有对象缺失或权限不足都不通过自动建表、改约束或关闭检查绕过。

首次运行发现 `logback-spring.xml` 没有匹配 `gbase-postgresql` 的 root appender，服务虽能启动、readiness 为 UP，但没有正常应用日志。该 profile 复用 GaussDB 的日志级别和控制台/滚动文件输出，日志不记录连接口令；修复后重新启动核验。

有数据的实测还发现：全局 MyBatis-Plus 逻辑删除字段为 `fgActive`，普通 `updateById` 不会写该字段；旧代码设置实体标志后更新的路径因此没有真正停用角色关系/配置/提示词/设备。用户编辑重建角色关系时触发唯一冲突；设备停用后原令牌仍可心跳。修复使用明确的逻辑删除操作或显式标志更新；设备封禁查找和重置删除使用专用 SQL，分别保留“封禁后不得重注册”与“管理员重置后允许重新注册”的既有契约。该问题由真实 ORM 运行发现，不作为 GBase 独有内核差异报告。所有复测仅使用本轮带 `pcie-gbase-verify` 标识的合成对象。

- 分支已从 `main` 新建，既有功能分支及 stash 保留。
- 用户先后提供 V6 与 5.0.0 两份介质；分别核实 SHA-256、SPI 和 Java 8 字节码，原件保留，测试时未混入另一份 JAR。
- 默认 Maven 构建及管理端构建通过：562 项测试中 561 项通过，真实库检查 1 项按默认条件跳过；候选 schema 的实体/定向 DDL/种子顺序回归通过。
- 首个 V6 候选包曾使用本机 JDK 23 完成构建及上述测试；其 SHA-256 为 `b3197336b649b9532eb221487b2d6223c2aea4798f25a36c22cd73363657d133`，备份在 `.local/artifacts/pcie-server-driver-V6-20261021.jar`，历史核对记录为 `.local/package-verification-v6.json`。
- 当前适配包已在运行缺陷修复后使用 5.0.0 用户介质重新构建，位于 `server/target/gbase-postgresql/pcie-server-0.2.0-SNAPSHOT.jar`，大小 66,633,122 字节，SHA-256 为 `4c0d5345e1ca12628cad36f49701bdfe24c34b5efe67f1cb8e914112ed54c4a4`。完整 `mvn -o -f server/pom.xml -Pgbase-postgresql package`（含管理端构建）通过，576 项默认测试中 575 项通过、真实 JDBC 1 项按默认条件跳过；新驱动真实连接及下述运行功能另行实测。包内只有 `gbase-jdbc-5.0.0.jar`，摘要与用户原件一致，未混入 V6/openGauss/PostgreSQL JDBC，管理端资源和 GBase 日志 profile 已核对，真实连接口令未进入包或代码差异。核对记录为 `.local/package-verification.json`。
- 网络调整前 TCP 超时。网络任务结束后的首次 V6 JDBC 检查在认证阶段返回 `FATAL: Invalid username/password,login denied.`，原始日志 `.local/jdbc-check-20261021.log` 和 `.local/jdbc-verification-20261021.json` 仅作为该旧驱动的历史失败证据。该 JAR 的 SHA-256 为 `a0485a7699b081e8dffac7a4e853d4ed3d2f52ec0b53749f624e417c85a07306`，驱动自报 `GBase8c JDBC Driver V6-1.0.0B06`。
- 用户要求尝试新 JAR 后，以同一 URL、账号和口令、仅加载新 JAR 的独立只读探针连接成功。URL 使用 `jdbc:gbase`；实际 `version()` 为 `(single_node GBase8sV8.8 S5.0.0B77 build 498d6db5)`，编译日期 2026-07-31，平台 aarch64。数据库为 `phis`，`current_schema()` 为 `rbmh_ai`，`sql_compatibility=A`。
- JDBC 元数据自报产品 `PostgreSQL`、版本 `9.2.4`，因此实际产品身份以完整 `version()` 中的 GBase 标识为准。元数据报告支持事务，当前隔离级别为 JDBC `2`（READ_COMMITTED）；未据此声称业务写事务或约束已实测。成功探针日志为 `.local/jdbc-driver-5.0.0-check-20261021.log`，结构化记录为 `.local/jdbc-driver-5.0.0-verification-20261021.json`。
- 前后两次检查未修改账号、口令、服务端认证、网络、模型配置或数据库对象/数据。观察到更换驱动后认证成功，具体认证实现差异未作根因分析。原自动跟进已删除，本轮新驱动测试由用户直接授权。
- 真实驱动连接、版本/兼容模式以及现有 schema 上的 ORM/接口/签名/浏览器已按下一节分项实测；独立空 schema 初始化、完整约束/回滚/并发及真实上游业务仍待验证，不能表述为全部商业产品验收完成。

## 逐项运行验证（2026-10-21 至 2026-10-22）

2026-10-22 追加用户授权的真实模型与原生客户端联调：先验证内网 Qwen3-32B 的真实响应，再为独立 `pcie-gbase-client` 测试机构保存模型配置。SenseVoiceSmall 批量转写、原生 PCIE、生产 SDK 模拟 HIS 及逐项功能结果另记；未完成前不沿用此前 Fake AI 的通过状态。原有机构模型配置不覆盖，供应商配置仅存服务端，模拟页面只含合成资料与模拟回执。

真实 Qwen3 服务的 A/B 检查发现：根级 `enable_thinking=false` 仍输出 `<think>`；同请求增加 `chat_template_kwargs.enable_thinking=false` 后直接返回正文。代理对实际 Qwen3 模型名同步模板参数，保留其余模型原协议，补普通与 SSE 的 HTTP 载荷回归。该修复属于模型协议兼容，不作为 GBase 内核修复报告。

使用真实 `GBase8sV8.8 S5.0.0B77`、兼容模式 A、厂商 JAR 和既有 `rbmh_ai` schema。启动及复测全程保留鉴权、P-256 签名和 schema readiness，未运行初始化或迁移脚本。合成资料统一带 `pcie-gbase-verify` 前缀，不包含真实居民资料；新增的临时 AI 配置只属于合成机构，原有模型配置与 AES 参数未更改。

首轮覆盖 46 项管理端查询/分页/统计/导出接口，全部返回成功；后续加入非空合成资料核对实际写入、更新、幂等和统计，未以空表 HTTP 200 作为完整功能通过的依据。浏览器检查覆盖 24 个可见菜单页面及通用辅诊统计入口，共 25 页；页面展示与 API 操作分别记录。浏览器实际完成合成用户编辑、保存并在列表读取新姓名。

| 功能 | 实测结果 | 已验证范围 / 尚未覆盖 |
| --- | --- | --- |
| 服务启动、健康、readiness | 通过 | `127.0.0.1:18090` 运行；清理后仍为 `UP`；GBase 控制台与滚动文件日志正常 |
| 管理员登录、用户身份、未登录拒绝 | 通过 | 本地登录、`auth/me`、缺少管理员令牌返回 401；未重置管理员口令 |
| 区域、机构 | 通过 | 合成新增/修改/分页查询/停用/启用，重复机构编码拒绝 |
| 用户、角色 | 修复后通过 | 新增、编辑、角色关联、用户启停；连续两次替换原角色关联及浏览器保存成功；测试结束后逻辑删除 |
| 模型配置存取 | 修复后通过 | 合成机构独立配置新增/修改、密钥响应脱敏、加密值经代理实际解密使用、逻辑删除；原有配置未更改 |
| 提示词 | 修复后通过 | 超 5 万字符中文 TEXT 原样往返、新增/修改/发布/归档、机构可见增量及逻辑删除 |
| 症状模板 | 通过 | 结构化 JSON 新增/修改、分页查询、事务变更日志、客户端增量、逻辑删除；未覆盖全量内置模板导入 |
| 设备注册、心跳、停用、重置 | 修复后通过 | 真实 P-256 公钥注册、心跳；停用后旧令牌 401，同编码重注册拒绝；管理员物理重置后新 ID/新令牌注册成功 |
| 请求签名 | 通过 | 有效签名、缺令牌、缺签名、错误签名、重放及请求体摘要篡改拒绝；拒绝记录可查询与展示 |
| bootstrap / 增量 | 通过（部分） | 合成机构 bootstrap 不泄露上游密钥，Prompt/症状模板及版本增量；映射仅验证当前空包/版本路径，非空映射数据包待验证 |
| 操作审计、反馈 | 通过 | 签名审计事件写入、合成反馈写入和后台查询/展示；未上传真实截图或音频 |
| 问诊日志 | 通过 | 2 条完成态合成智能问诊及 1 条生成态合成语音问诊，JSON、详情、时间线、列表和导出；未提交真实 PHIS |
| 综合统计、活跃度、辅诊统计 | 通过 | 区域/HIS 机构范围、趋势/分布/医生、当前通用及萧山统计路径；两条完成态记录的诊断修改数为 0 和 1，符合率返回 `50%`；事件幂等后不重复计数 |
| Excel 导出 | 通过 | 综合统计、通用辅诊、萧山辅诊、活跃度、客户端使用、用户日志 6 类；检查 XLSX 压缩包、工作表及合成数据内容 |
| 患者记忆 | 通过 | 合成观察/事实同步、幂等、resolve、管理员更正/屏蔽/恢复、版本递增及治理审计；结束后屏蔽合成事实 |
| 推荐偏好 | 通过 | 合成事件、医生/科室/机构聚合、重复上报跳过、候选排序与后台展示；不代表医学推荐有效性 |
| 住院病历模板 | 通过 | resolve/缓存复用、详情、字段 `aiSuitable` 设置、提示词维护、启停及逻辑删除 |
| 门诊病历模板 | 通过 | HTML/定义双哈希快照保存与 resolve、详情、字段映射更新/清除；审计快照保留 |
| AI 普通代理与 SSE | 合成及真实上游分别通过 | 前轮为本机 Fake；本轮独立机构的 Qwen3-32B 普通主/快/Reviewer 路由及 SSE 已实际返回，见下节；不据此认定医学内容通过 |
| 业务调试 | 合成上游通过 | 合成语音问诊上下文及 8 个节点执行；统一使用临时机构的本机回显配置，Reviewer 独立上游仍待验证 |
| 检验检查回写 | 仅查询通过 | 列表/分页及浏览器展示通过；无隔离合成申请单，未写既有 LIS/PACS/PHIS 记录 |
| 版本发布 | 仅查询/展示通过 | 独立本机文件目录的渠道列表和历史查询；未发布真实安装包、升级策略或执行客户端升级 |
| BBP、AI 使用权限、实时语音、知识库 | 待验证 | 当前 `local` 登录模式；本轮新增真实批量 ASR 和 Qwen3 路由检查，BBP、实时 ASR、WeKnora、PMPHAI 及独立 Reviewer 模型验收仍待完成 |

本轮修复三个方面：GBase profile 的 Logback root 缺失；用户角色替换及配置/提示词/用户/角色停用未真正写入逻辑删除标志；设备封禁/重置受全局逻辑删除 SQL 影响。新增回归在真实 MyBatis-Plus 全局配置下生成 Mapper SQL，并用实际 GBase API 复测；没有关闭鉴权、约束或 readiness。

清理通过现有生产 API 完成，仅操作本轮创建的 ID：配置、提示词、症状/住院模板、用户与角色已逻辑删除；设备封禁，合成机构与区域停用，患者事实屏蔽。缺少删除入口的门诊快照、问诊/反馈、功能/推荐事件、聚合及治理审计保留为明确标注的合成证据，不声称整库已恢复至初始行数。临时 Fake 服务已停止，PCIE Server 保留运行供查看。

详细证据均在忽略目录：`.local/runtime/read-verification.json`、`workflow-before-fixes.json`、`workflow-verification.json`、6 份 `*-verified.xlsx`、`browser/checks.json` 与页面截图。初期探针中的错误请求字段单独保留为输入校验拒绝记录；按源码修正字段后的复测均另有成功记录，不将测试脚本输入问题归为数据库缺陷。

仍需单独完成：账号当前没有数据库 CREATE 权限，未取得可用独立空 schema，未执行完整初始化、全部 20 个唯一索引的 NULL/更新/并发冲突和显式回滚测试；本轮真实模型及客户端合成 HIS 的范围见下节，真实 PHIS 和医学验收仍未完成。跨夜后首次 readiness 曾超时，日志显示连接关闭及连接池获取超时，后续连接重建后恢复 UP；本轮不据此认定长时间运行和断线恢复已验收。

## 真实模型与原生客户端验证（2026-10-22）

用户授权后，独立机构 `pcie-gbase-client-20261022` 配置 Qwen3-32B 为主、快及 Reviewer 路由，SenseVoiceSmall 为批量转写。三条文本路由均实际返回 `PCIE_REAL_MODEL_OK`，SSE 拼接得到 `PCIE_SSE_OK` 并以 `[DONE]` 结束；合成语音经真实批量 ASR 得到转写。三条文本路由使用同一模型，不代表独立审阅模型或医学审核。知识检索、公卫和实时语音配置保持未启用；内网地址和服务端配置仅留私有运行目录，客户端和模拟页不保存上游凭据，既有机构配置未覆盖。

PCIE 从当前 `main` 的 `ec81b9b9e99773bfe7962d792fbfba42949518b0` 编译本机 macOS debug 测试候选 `1.4.9`，未拉取或合并该 checkout 落后的 12 个提交。源文件版本已还原 `1.4.8`，未打标签、发布安装包或修改升级渠道。应用名 `PCIE GBase Test`，独立 Bundle Identifier `com.med-hermes.gbase-client-test`，保留原桌面应用及其资料。标准前端候选包位于 `pcie/src-tauri/target/debug/bundle/macos/PCIE GBase Test.app`，可执行文件 SHA-256 为 `8e56e1a8d68aef9b6aab4fab52a90a82c84e207acda959df2fb56d97d337d347`，另存于 `pcie/output/gbase-client-runtime/.local/artifacts/`。结束时运行的是标准前端候选；语音合成输入变体另存摘要，未保留录音进行中状态。

`http://127.0.0.1:4260/` 为新增合成 HIS 工作台，复用生产 SDK、医疗握手、8081 Bridge、设备 P-256 签名与本机 18090 后台。患者、就诊、目录及病历均有 `pcie-gbase-client` 标识；写回只存网页内存并发送原请求回执，不执行真实 PHIS 保存。17 个页面入口均已实际调用；成功受理、原生生成、确认回执和医学结果分别记录：

| 功能 | 本轮观察结果 | 边界 |
| --- | --- | --- |
| 智能问诊、病历生成 | 呼吸道结构化问诊与独立病历入口均完成真实生成；问诊仅选择主诉/诊断回写并收到成功回执 | 模板中的未核实内容未作为本次临床事实验收 |
| 门诊模板 | 4 个字段实际生成，修改主诉后确认，原 requestId 模拟保存/回执成功 | 合成 HTML 与定义双哈希，不是真实医生站模板验收 |
| 住院病历 | 2 个解析字段中 1 个适用 AI 字段生成、预览和确认回执成功 | 不能声称两字段均由模型生成 |
| 诊断建议 | 同步合成标准目录后匹配 `J02.900`，仅选择诊断回写成功 | 医学正确性另审 |
| 鉴别诊断 | 原生面板完成生成，本病例无待复核方向 | 有阳性鉴别建议的病例待补充 |
| 用药推荐 | 真实推荐，经医生补齐数量和药房/库存核对后模拟回写成功 | 仅工程病例，不构成用药建议 |
| 检查、检验 | 新接诊匹配项目，`sdSrv=31/41`、科室/部位/标本 JSON/互认编码经 SDK 返回，模拟回执成功；独立检验入口亦生成结果 | 首轮旧缓存曾使用错误测试目录码，已另建新接诊复测，旧证据不计通过 |
| 处置、诊疗方案 | 处置入口完成；独立方案成功回写所选血常规，未匹配的雾化无法选中回写 | 合成目录无可用处置，阳性处置回写待补充 |
| 诊疗提醒、风险提示 | 按既有契约执行风险同步，SDK 风险文本在原生胶囊展示 | 未将风险同步称为独立 AI 提醒正文生成 |
| 检验报告 | 原生生成和展示完成，但模型把参考范围内 WBC `6.2`、CRP `3` 误判为升高 | **医学内容验证失败**，不能因接口成功记为功能内容通过 |
| 检查报告 | 原生展示真实模型对正常胸片及其局限的解读 | 医学验收待完成 |
| 语音动态模板 | 单独 DEV 输入变体使用合成音频的真实 ASR 转写，再由真实模型生成；医生修订后合并 4 模板字段、1 诊断、2 医嘱，SDK 同一 requestId 成功回执 | 非真实麦克风/实时 ASR；服务端语音日志仍为 generated，终态统计一致性待复核 |
| 真实麦克风试音（15:34） | 用户实际讲话，标准前端候选采集 13 秒并停止，SenseVoiceSmall 转写为“喂喂喂，听得到吗？喂。”；后台 `speech/transcribe` 与 `ai_proxy/speech_transcribe` 均有成功审计 | 批量转写通过，当前停在文字确认页；实时字幕、实时 ASR 与真实录音病历生成仍待验证 |
| 慢病续方 | 新合成就诊正确匹配历史氨氯地平和有效库存；确认 3 项复诊事实，仅选择药品回写，30 天/30 片及原请求成功回执已核对 | 原测试目录曾漏库存；修正合成资料后以新就诊复测，未绕过匹配或库存门禁 |
| 聊天、知识库 | 标准前端候选真实聊天返回 `PCIE_CHAT_OK`，有用反馈成功；知识入口明确提示当前机构未启用资料检索 | 续方结束后立即切换曾出现一次 `No active session`，重新展开恢复；切换稳定性及真实召回待复核 |

真实并发 HIS 调用暴露客户端日志多线程写入拼行，导致列表跳过非法 JSON 行。修复对追加/轮转/读取/清理使用同一锁，每条完整 JSON 与换行一次写入；16 线程各 64 条的测试在修复前失败、修复后通过。新原生包运行区段 192 条、192 个唯一 ID、0 条非法 JSON，历史问题行保留。该问题为客户端并发缺陷，不归为 GBase 差异。前端类型检查、187 文件共 1,134 项单测、原生 37 项测试、`cargo check`、原生包构建和模拟服务 3 项协议测试均通过；服务端完整 package 结果仍为上述 570 项。未以 Mock 实体赋值代替实库 SQL 核对。

本轮另在 GBase 查询到独立机构 240 条匹配审计和 7 条问诊记录；语音 generated 与 SDK 成功回执的边界如上，不将其算作服务端完成态。临时 API 设备已停用；16 条合成患者事实通过现有屏蔽 API 处理，1 份合成住院模板逻辑删除并核对列表已不可见。门诊快照、问诊/反馈与治理审计保留，独立测试机构、模型配置和原生设备绑定作为后续演示配置保留。清理清单 `.local/runtime/client-cleanup-manifest.json`，模型实测 `.local/runtime/real-model-verification.json`，审计/问诊快照为 `client-audit-final-snapshot.json` 和 `client-consultations-final.json`；客户端原生截图、SDK 回执、观察清单与构建来源见 `pcie/output/gbase-client-runtime/.local/`。

用户复核实时字幕后，进一步核对客户端：本轮 `openai-compatible` / SenseVoiceSmall 配置走批量分支，`RealtimeSpeechService.start` 不建 WebSocket，PCM 缓存到 `finish` 时才调用转写。该路径的最终文字成功不能记为实时识别成功；当前尚未取得经核实的内网实时 WebSocket 服务地址。原客户端和服务端已有 `funasr-websocket` 实时协议接入，须以实际地址完成握手、连续音频及停止前字幕验证后才启用；不猜测端口、不把模型名含 FunASR 当作已提供实时服务。本次只读诊断的管理端配置查询超时，保留已存配置及 15:34 成功审计，未更改模型配置；记录见 `.local/runtime/realtime-speech-diagnosis.json`。

## SenseVoice 分段识别配置与构建（2026-10-22）

用户明确要求 ASR 继续使用现有模型并提供配置项后，新增后台“AI 配置 → 语音识别 → 录音识别方式”：默认“录音结束后识别”（`batch`），可选“分段识别（准实时）”（`segmented`）；分段时长默认 4 秒，可调 2–15 秒。仅对 OpenAI 兼容语音提供方生效，模型继续由 `audioModel`（本轮为 SenseVoiceSmall）决定，后台地址和密钥留在服务端。保存后重新启动客户端获取 bootstrap，下一次录音使用新配置。

配置以 `featuresJson.speechTranscription` 保留对象存储，管理 API 提供显式模式/时长字段，bootstrap 只下发模式/时长，Boolean features 不含该对象。未改数据库对象、初始化脚本或原有模型配置。更新后台和客户端后再启用；回滚到不支持该保留对象的旧后台前须移除该对象，恢复 Boolean-only featuresJson。

分段逻辑复用生产签名 HTTP 转写代理，500ms 音频重叠，草稿请求串行且积压窗口有界；停止后完整录音再转写并替换草稿。关闭/切换会话隔离迟到的草稿和最终回调；仅消费最终文字的聊天录音不额外请求草稿。新增默认 Fake 回归覆盖重叠、背压、失败/空结果、旧 batch 默认、最终校正、迟到串线和配置的保存/列表/bootstrap 往返、旧客户端更新保留设置、浮点/范围拒绝。前端 188 文件共 1,143 项测试通过，后台 576 项中 575 项通过、1 项真实 JDBC 默认跳过；管理端构建和原生候选 1.4.9 构建通过，源码版本已恢复 1.4.8，未发布或提交。

当前新原生可执行文件 SHA-256 为 `903909de9ad7bbedc19da83baef37c1597e0a6af563b1c008394be4fa74514b2`，后台新包身份见上方当前包信息。生产配置组件先在独立合成页面验证了模式切换、分段时长与 3000ms 配置值投影，截图位于 `pcie/output/gbase-client-runtime/.local/segmented-asr-config-preview.png`；该预览没有写入数据库。新旧构建来源分别保留。

10 月 22 日内网不可达时，模型 HTTP 超时，GBase TCP 超时或认证握手 EOF，新后台启动被现有 schema readiness 阻止；当时未启用分段模式，未绕过检查或执行错误提示中的更新 SQL。

### 真实麦克风分段验证（2026-10-23）

网络恢复后，SenseVoice `/v1/models` 返回 HTTP 200，后台使用厂商驱动启动，health/readiness 均为 UP。通过现有管理 API 只将 `pcie-gbase-client-20261022` 独立测试机构设为 `segmented / 4000ms`，其余模型字段和已有密钥保留；重启上述标准前端候选，刷新模拟 HIS 页面重新握手。旧页面曾因模拟服务重启后仍持有过期临时授权返回 HIS 401，刷新握手后接诊恢复，没有放宽鉴权。

用户真实麦克风采集 14 秒；服务端同机构审计确认停止前完成 3 次分段转写，停止后完成 1 次完整转写：

| 本机时间 | 阶段 | 上游耗时 | SenseVoiceSmall 返回 |
| --- | --- | --- | --- |
| 13:46:28.278 | 录音中第 1 段 | 336ms | `hello hello,听得到吗？` |
| 13:46:32.165 | 录音中第 2 段 | 225ms | 空文本 |
| 13:46:36.300 | 录音中第 3 段 | 190ms | `给个结论。` |
| 13:46:38.585 | 停止后的完整校正 | 394ms | `hello hello,听得到吗？给个结论。` |

开始采集事件为 13:46:23.449，停止事件为 13:46:38.004；首段结果约在开始后 4.83 秒到达，停止至完整校正约 0.58 秒。最终文本在原生“音频采集完成”确认页实际展示，麦克风已停止；用户反馈“我暂停讲话时才会出现文字片段”。本轮没有截取停止前画面，录音中可见片段由用户观察与停止前真实请求审计共同佐证；不据此认定连续讲话的逐字流式体验。此实现固定时长提交 HTTP 音频片段，不以静音作为客户端提交条件。

后台真实配置页面已回显“分段识别（准实时）/ 4 秒”，截图 `pcie/output/gbase-client-runtime/.local/segmented-asr-config-live-20261023.png`；最终文字截图 `segmented-asr-final-20261023.png`。配置快照、真实请求审计和 `segmented-asr-verification.json` 保存在本分支 `.local/runtime/`，客户端私有目录保留同份验证清单。此次试音没有确认进入医疗生成或模拟回写；长录音、连续讲话效果、多用户并发、真实语音到病历闭环及医学/真实 PHIS 验收仍待验证，原真实空库和约束验收边界保持不变。
