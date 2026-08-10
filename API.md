# 全医慧助服务端（PCIE Server）API 说明

> 更新日期：2026-08-04
> 范围：全医慧助（PCIE）桌面端当前唯一远程业务契约 `/v1/*`；桌面端已取消本地/区域双模式
>
> 仓库与 Maven 工程已更名为 `pcie-server`；为兼容既有部署，`/v1/*`、`/admin/api/*`、`floating-ball.*` 配置键、`FB_*` 环境变量和数据库结构保持不变。

## 1. 约束说明

1. 本文档只描述远端接口，不包含全医慧助（PCIE）桌面端本地 `/api/consultation/*`。
2. 接口实现必须同时兼容以下调用方：
   - `floating-ball/src/services/regionalClient.ts`
   - `floating-ball/src/services/llm.ts`
   - `floating-ball/src/services/templateService.ts`
   - `floating-ball/src/services/medicalData.ts`
   - `floating-ball/src/services/promptOverride.ts`
   - `floating-ball/src/services/auditUploader.ts`
3. 安全模式下，仓库内 `application.yml` 不再内置真实数据库地址、账号口令或 AES key；部署前必须注入 `FB_DB_URL`、`FB_DB_USERNAME`、`FB_DB_PASSWORD`、`FB_AES_KEY`。
4. 统一响应结构如下：

```json
{
  "code": "0",
  "message": "success",
  "data": {},
  "requestId": "uuid",
  "timestamp": 1770000000000
}
```

错误响应仍使用同一结构，`code` 为非 `0`，`requestId` 必须随响应返回。`message` 面向医生或管理员展示，默认不得包含 Java 类名、SQL/Oracle 错误、堆栈、文件系统路径、上游原始响应体、token 或签名细节；需要排障时通过 `requestId` 在服务端日志、审计日志或安全拒绝日志中查找完整原因。客户端展示失败信息时应优先使用 `message`，并在存在 `requestId` 时附加“请求ID：xxx”作为排障线索。

`timestamp` 始终表示服务端生成响应时的 epoch 毫秒时间，可作为桌面端校准签名时钟偏移的参考；客户端不得把本地时区换算结果写入签名，只能使用 epoch 毫秒。

## 2. 认证与签名

### 2.1 客户端接口

- 路径前缀：`/v1/*`
- 认证方式：`Authorization: Bearer {deviceToken}` + ECDSA P-256 请求签名
- 例外：`POST /v1/client/register` 与 `/v1/client/releases/*` 无需设备令牌和请求签名

除上述例外外，所有 `/v1/*` HTTP 请求必须携带：

| Header | 必填 | 说明 |
| --- | --- | --- |
| Authorization | 是 | `Bearer {deviceToken}` |
| X-Timestamp | 是 | 毫秒时间戳，服务端默认允许 5 分钟时钟偏移；桌面端可使用响应体 `timestamp` 校准本机到服务端的签名时钟偏移 |
| X-Nonce | 是 | 每次请求唯一随机数，服务端会拒绝重放 |
| X-Signature | 是 | ECDSA P-256 / SHA-256 签名，base64 编码 |
| X-Body-SHA256 | 有 body 时是 | 请求体 SHA-256 hex；服务端会用实际 body 重算并比对 |

签名原文固定为：

```text
METHOD
PATH
TIMESTAMP
NONCE
BODY_SHA256
```

说明：

1. `METHOD` 使用大写 HTTP 方法，例如 `POST`。
2. `PATH` 只包含路径，不包含 query，例如 `/v1/ai/chat`。
3. `BODY_SHA256` 必须使用实际请求体字节计算；空 body 使用空字符串 SHA-256：`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。
4. 服务端不会信任客户端声明的 `X-Body-SHA256`，会用实际收到的 body 重算并参与验签。
5. 签名失败返回 `SIG-401`；令牌缺失或无效返回 `AUTH-401`；客户端版本过低返回 `UPDATE-REQUIRED`。
6. 客户端收到 `SIG-401` 且响应带 `timestamp` 时，应先用该服务端时间刷新本地签名偏移并重签重试一次；仍失败时再按设备令牌或密钥异常处理。
7. 管理端停用设备令牌后，服务端必须同时阻止同机构同 `cdDevice` 通过 `/v1/client/register` 匿名重新注册，避免旧客户端在令牌失效后自动领取新令牌继续使用。
8. 管理端删除设备令牌只用于异常设备重置：删除会移除该令牌记录并释放同机构同 `cdDevice`，允许客户端重新注册并领取新令牌；若目标是封禁旧客户端，必须使用停用而不是删除。

### 2.2 管理端接口

- 当前采用 `Authorization: Bearer {adminToken}` 方案
- 例外：`POST /admin/api/auth/login` 无需鉴权
- `GET /admin/api/auth/me` 用于管理端恢复当前登录用户
- `PUT /admin/api/auth/password` 用于当前管理员登录后修改自己的密码
- 管理端页面默认由同一 Spring Boot 服务托管，入口为 `/admin/`
- 浏览器访问管理端时，默认采用同源调用；CORS 仅保留给本地独立调试使用

### 2.3 管理员密码维护

#### 2.3.1 PUT `/admin/api/auth/password`

用途：当前已登录管理员修改自己的密码。

请求：

```json
{
  "oldPassword": "admin123",
  "newPassword": "newPassword123",
  "confirmPassword": "newPassword123"
}
```

约束：

- 需要携带有效 `Authorization: Bearer {adminToken}`
- `oldPassword` 必须与当前账号现有密码匹配
- `newPassword` 与 `confirmPassword` 必须一致
- 新密码长度至少 6 位，且不能与旧密码相同

响应 `data`：

```json
{
  "status": "ok"
}
```

#### 2.3.2 启动期受控密码重置

用途：在管理员忘记密码、无法登录时，通过服务启动配置重置指定账号密码。

配置项：

- `floating-ball.admin.bootstrap-reset.enabled`
- `floating-ball.admin.bootstrap-reset.username`
- `floating-ball.admin.bootstrap-reset.password`

环境变量映射：

- `FB_ADMIN_BOOTSTRAP_RESET_ENABLED`
- `FB_ADMIN_BOOTSTRAP_RESET_USERNAME`
- `FB_ADMIN_BOOTSTRAP_RESET_PASSWORD`

约束：

- 仅在服务启动阶段执行，不提供公开匿名 HTTP 接口
- 默认账号可填 `admin`，重置完成后应立即关闭该配置并重启服务

### 2.4 检验检查结果手动录入

#### 2.4.1 GET `/admin/api/exam-result-entry/applies`

用途：查询 `hi_ods_apply` 中待第三方执行/回写的检验或检查申请单，供管理端模拟第三方录入结果。兼容旧路径 `/admin/api/lis-result-entry/applies`。

鉴权：`Authorization: Bearer {adminToken}`

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| current | number | 否 | 页码，默认 `1` |
| size | number | 否 | 每页条数，默认 `10` |
| keyword | string | 否 | 匹配申请单号、申请单名称、患者主键、就诊主键、诊断名称 |
| dispType | string | 否 | 申请单类别：`1` 检验，`2` 检查；为空时查询检验和检查 |
| businessType | string | 否 | 申请类别：`1` 门诊，`2` 住院；为空时查询全部业务类型 |
| status | string | 否 | 申请单状态；默认查未报告/未作废，常用 `1` 提交、`2` 已执行 |
| idOrg | string | 否 | 机构编号 |
| dateFrom | string | 否 | 申请单创建开始日期，格式 `yyyy-MM-dd`；为空时默认近 3 天 |
| dateTo | string | 否 | 申请单创建结束日期，格式 `yyyy-MM-dd`；为空时默认近 3 天 |

说明：列表默认只查询近 3 天待执行数据，并按申请单创建时间倒序返回，最新申请单显示在前。

响应 `data.records[]` 主要字段：

```json
{
  "idApply": "APPLY001",
  "cdApply": "LIS202606170001",
  "naApply": "血常规",
  "sdDisp": "1",
  "sdBusiness": "1",
  "sdApply": "1",
  "idPi": "P001",
  "idVis": "VIS001",
  "nasDiag": "上呼吸道感染",
  "naDeptExec": "检验科",
  "naDocExec": "张医生",
  "fgUrgent": "0",
  "insertTime": "2026-06-17T09:00:00",
  "idResult": null
}
```

#### 2.4.2 GET `/admin/api/lis-result-entry/applies/{idApply}/reports`

用途：查看指定检验申请单已录入的检验明细。新路径也支持 `/admin/api/exam-result-entry/applies/{idApply}/reports`。

#### 2.4.3 POST `/admin/api/exam-result-entry/applies/{idApply}/lis-report`

用途：录入检验指标，模拟第三方系统把报告明细写回。

请求：

```json
{
  "reportDoctor": "系统管理员",
  "auditDoctor": "系统管理员",
  "instrumentCode": "MANUAL",
  "instrumentName": "手工录入",
  "items": [
    {
      "cdResult": "WBC",
      "naResult": "白细胞计数",
      "testResult": "6.2",
      "resultQualitative": "",
      "referenceRange": "3.5-9.5",
      "referenceLow": "3.5",
      "referenceHigh": "9.5",
      "resultUnit": "10^9/L",
      "resultHint": ""
    }
  ]
}
```

处理规则：

1. 只允许检验类申请单（`sd_disp='1'`）回写；已作废或已报告申请单不能重复回写。
2. 服务端生成 24 位 ObjectId 作为报告组 ID，写入 `hi_ods_apply.id_result`，每条明细写入 `hi_ods_apply_lis_report.id_report_group` 与 `resultid`；每条 `hi_ods_apply_lis_report.id_report` 也使用 24 位 ObjectId。
3. 回写成功后，`hi_ods_apply.sd_apply` 更新为 `3`（已报告），`dt_exec/update_time/update_user/revision` 同步更新。
4. 旧路径 `/admin/api/lis-result-entry/applies/{idApply}/reports` 仍兼容检验回写。
5. 当前功能只模拟第三方回写检验常规报告，不创建 `hi_ods_lis_result` 等未纳入本项目基线的中间表。

#### 2.4.4 GET `/admin/api/exam-result-entry/applies/{idApply}/pacs-report`

用途：查看指定检查申请单已录入的检查报告。

#### 2.4.5 POST `/admin/api/exam-result-entry/applies/{idApply}/pacs-report`

用途：录入检查报告，模拟第三方 PACS/检查系统把报告写回。

请求：

```json
{
  "result": "胸廓对称，双肺纹理增多，未见明确实变影。",
  "clinicalImpression": "咳嗽待查",
  "diagnosticImaging": "双肺纹理增多，请结合临床。",
  "negativePositive": "阴性",
  "remark": "手工模拟第三方回写",
  "cdStudy": "PACS202606170001",
  "idDept": "A1",
  "naDept": "放射科",
  "reportDoctor": "系统管理员",
  "auditDoctor": "系统管理员"
}
```

处理规则：

1. 只允许检查类申请单（`sd_disp='2'`）回写；已作废或已报告申请单不能重复回写。
2. 服务端生成 24 位 ObjectId 作为报告 ID，写入 `hi_ods_apply.id_result` 与 `hi_ods_apply_pacs_report.id_report`。
3. 回写成功后，`hi_ods_apply.sd_apply` 更新为 `3`（已报告），`dt_exec/update_time/update_user/revision` 同步更新。
4. PACS 表保留数据库列名 `"RESULT"`，用于存储检查结果正文。

## 3. 客户端版本发布与内网更新

### 3.1 管理端批量上传客户端版本

`POST /admin/api/releases/upload/batch`

用途：管理员一次选择多个发布通道和多个 Tauri 平台安装包，用同一份 `latest.json` 同步发布测试/正式等内网环境的桌面端更新。

鉴权：`Authorization: Bearer {adminToken}`

请求类型：`multipart/form-data`

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| channels | string[] | 是 | 可重复字段，发布通道集合：`production` 正式内网，`testing` 测试内网 |
| metadataFile | file | 是 | Tauri 发布产物中的 `latest.json`，服务端从中解析 `version`、`platforms.{target}.signature`、`notes`、`pub_date` |
| version | string | 否 | 客户端版本号；默认从 `metadataFile.version` 读取，手工填写时覆盖文件值 |
| notes | string | 否 | 更新说明；默认从 `metadataFile.notes` 读取，手工填写时覆盖文件值 |
| pubDate | string | 否 | 发布时间，ISO-8601；默认从 `metadataFile.pub_date` 读取，均为空时由服务端生成 |
| forceUpdate | boolean | 否 | 是否对所有目标通道开启强制更新；为 `true` 时，低于本次发布版本的客户端只能访问更新检查和安装包下载 |
| files | file[] | 是 | 一个或多个安装包/更新包文件；服务端按文件名匹配 `latest.json.platforms.{target}.url` 自动识别 target 和签名 |

说明：

1. 批量发布适合一次性把同一客户端版本发布到测试、正式等多个通道，并同时上传 macOS / Windows 等多个平台包。
2. 平台 target 不需要管理员手工填写；服务端按上传安装包文件名匹配 `latest.json.platforms.{target}.url` 中的文件名，自动取得 target 和签名。
3. 若多个 target 的 URL 指向同一个文件名，服务端会把同一个上传文件发布到这些 target；典型场景是 macOS universal 包 `PCIE_universal.app.tar.gz` 同时匹配 `darwin-aarch64` 与 `darwin-x86_64`。
4. 安装包文件名必须与对应 `latest.json` 平台 URL 指向的文件名一致，否则 Tauri updater 会签名校验失败。
5. 同一次批量发布内的所有安装包必须解析为同一个版本号；不同版本应拆成多次发布。
6. 若目标通道当前版本与本次版本不同，服务端会先保存该通道当前快照，再用本次安装包集合生成新的 `latest.json`；若版本相同，则合并或覆盖对应平台。
7. 勾选强制更新前，必须确认本次目标通道的所有实际部署平台安装包均已上传，否则旧客户端会被禁止使用但无法下载对应平台更新。

响应 `data`：目标通道的当前发布列表，每条结构同 `GET /admin/api/releases` 的 `ReleaseView`。

```json
[
  {
    "channel": "production",
    "version": "1.2.13",
    "platforms": [
      {
        "target": "darwin-aarch64",
        "fileName": "PCIE_1.2.13_aarch64.app.tar.gz",
        "fileSize": 12345678,
        "downloadUrl": "http://127.0.0.1:8080/v1/client/releases/production/files/darwin-aarch64/PCIE_1.2.13_aarch64.app.tar.gz"
      },
      {
        "target": "windows-x86_64",
        "fileName": "PCIE_1.2.13_x64-setup.nsis.zip",
        "fileSize": 23456789,
        "downloadUrl": "http://127.0.0.1:8080/v1/client/releases/production/files/windows-x86_64/PCIE_1.2.13_x64-setup.nsis.zip"
      }
    ],
    "target": "darwin-aarch64",
    "fileName": "PCIE_1.2.13_aarch64.app.tar.gz",
    "downloadUrl": "http://127.0.0.1:8080/v1/client/releases/production/files/darwin-aarch64/PCIE_1.2.13_aarch64.app.tar.gz",
    "latestJsonUrl": "http://127.0.0.1:8080/v1/client/releases/production/latest.json",
    "policyUrl": "http://127.0.0.1:8080/v1/client/releases/production/policy.json",
    "pubDate": "2026-04-24T10:00:00Z",
    "forceUpdate": true,
    "minSupportedVersion": "1.2.13"
  }
]
```

### 3.2 管理端兼容上传单个客户端安装包

`POST /admin/api/releases/upload`

用途：兼容旧管理端或联调脚本，上传单个通道、单个平台的 Tauri updater 安装包、签名和版本元数据。

鉴权：`Authorization: Bearer {adminToken}`

请求类型：`multipart/form-data`

字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| channel | string | 是 | 发布通道：`production` 正式内网，`testing` 测试内网 |
| metadataFile | file | 是 | Tauri 发布产物中的 `latest.json`，服务端从中解析 `version`、`platforms.{target}.signature`、`notes`、`pub_date` |
| version | string | 否 | 客户端版本号；默认从 `metadataFile.version` 读取，手工填写时覆盖文件值 |
| target | string | 否 | Tauri updater target；默认按安装包文件名匹配 `metadataFile.platforms`，多平台无法匹配时需手工填写 |
| signature | string | 否 | 对安装包生成的 Tauri/minisign 签名内容；默认从 `metadataFile.platforms.{target}.signature` 读取 |
| notes | string | 否 | 更新说明；默认从 `metadataFile.notes` 读取，手工填写时覆盖文件值 |
| pubDate | string | 否 | 发布时间，ISO-8601；默认从 `metadataFile.pub_date` 读取，均为空时由服务端生成 |
| forceUpdate | boolean | 否 | 是否强制更新；为 `true` 时，低于本次发布版本的客户端只能访问更新检查和安装包下载 |
| file | file | 是 | 安装包或更新包文件，通常为 Tauri bundle 产物 |

说明：运维推荐只选择 `latest.json` 与对应安装包文件；`version`、`target`、`signature` 仅作为解析失败或多平台歧义时的兜底覆盖项。安装包文件名必须与 `latest.json.platforms.{target}.url` 中的文件名一致，例如签名对应 `PCIE.app.tar.gz` 时不能上传 `PCIE.dmg`，否则 Tauri updater 会签名校验失败。勾选强制更新前，必须确认该通道所有实际部署平台的安装包均已上传到当前 `latest.json`，否则旧客户端会被禁止使用但无法下载对应平台更新。

部署说明：生产/内网环境推荐设置 `FB_RELEASE_PUBLIC_BASE_URL=http://后端内网IP:8080`，确保管理端展示、复制的更新源以及 `latest.json` 内下载地址都不出现 `localhost`。

首次安装说明：管理员上传当前通道安装包后，管理端“版本发布”列表会展示 `downloadUrl`，可直接复制给新电脑浏览器下载；也可访问 `/client-download?channel=production` 打开公开下载页，由使用者按平台选择安装包。

响应 `data`：

```json
{
  "channel": "production",
  "version": "1.2.13",
  "target": "darwin-aarch64",
  "fileName": "PCIE_1.2.13_aarch64.dmg",
  "fileSize": 12345678,
  "downloadUrl": "http://127.0.0.1:8080/v1/client/releases/production/files/darwin-aarch64/PCIE_1.2.13_aarch64.dmg",
  "latestJsonUrl": "http://127.0.0.1:8080/v1/client/releases/production/latest.json",
  "policyUrl": "http://127.0.0.1:8080/v1/client/releases/production/policy.json",
  "pubDate": "2026-04-24T10:00:00Z",
  "forceUpdate": true,
  "minSupportedVersion": "1.2.13"
}
```

### 3.3 管理端查询发布状态

`GET /admin/api/releases?channel=production`

用途：返回指定通道当前可见版本；`channel` 为空时返回所有通道。

返回的 `platforms` 为当前版本下所有平台安装包；兼容字段 `target/fileName/downloadUrl` 指向首个平台安装包，可用于旧页面展示。`latestJsonUrl` 仍用于 Tauri updater 自动更新。

### 3.4 管理端切换强制更新策略

`POST /admin/api/releases/policy`

用途：独立开启或关闭当前通道的强制更新策略，不需要重新上传安装包。

请求：

```json
{
  "channel": "production",
  "forceUpdate": true
}
```

响应 `data`：同 `GET /admin/api/releases` 的单条 `ReleaseView`。

说明：

1. 开启强制更新时，`minSupportedVersion` 固定为当前通道正在发布的 `latestVersion`。
2. 关闭强制更新时，`minSupportedVersion` 清空，旧客户端不再因版本低被业务接口拦截。
3. 切换策略会同步更新当前版本的历史快照，确保后续回滚能恢复该版本当时的策略状态。

### 3.5 管理端查询历史版本

`GET /admin/api/releases/history?channel=production`

用途：返回指定通道的历史发布快照；`channel` 为空时返回所有通道。历史快照由服务端在上传与回滚时自动维护，不依赖数据库表。

响应 `data`：

```json
[
  {
    "channel": "production",
    "version": "1.2.13",
    "active": false,
    "forceUpdate": false,
    "minSupportedVersion": null,
    "targets": ["darwin-aarch64", "windows-x86_64"],
    "fileNames": ["PCIE_1.2.13_aarch64.app.tar.gz", "PCIE_1.2.13_x64-setup.nsis.zip"],
    "notes": "修复内网升级流程",
    "pubDate": "2026-04-24T10:00:00Z",
    "updatedAt": 1777250000000
  }
]
```

### 3.6 管理端回滚到历史版本

`POST /admin/api/releases/rollback`

用途：把指定通道的当前发布回滚到历史快照，同时恢复该版本当时的强制更新策略。回滚只切换 `latest.json` / `policy.json` 指针，不重新上传安装包。

请求：

```json
{
  "channel": "production",
  "version": "1.2.13"
}
```

响应 `data`：同 `GET /admin/api/releases` 的单条 `ReleaseView`。

说明：

1. 回滚前服务端会校验历史快照中每个平台的安装包文件仍存在。
2. 如果当前发布目录中没有历史快照，服务端会把当前 `latest.json` 作为兼容历史记录展示；但只有已写入快照的版本才能执行回滚。
3. 从一个版本切换到另一个版本时，服务端会先保存当前版本快照，确保刚发错的新版本也能被再次回滚回来。

### 3.7 公开首次安装下载页

`GET /client-download?channel=production`

用途：公开展示指定通道当前已上传的客户端安装包下载入口，便于首次安装机器直接通过浏览器下载，不需要管理员登录后台或使用 U 盘拷贝。

查询参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| channel | string | 否 | 发布通道：`production` 或 `testing`，默认 `production` |

说明：

1. 该页面不需要设备令牌或管理员令牌，只展示当前通道的版本号、发布时间、平台 target、文件名和下载按钮。
2. 若当前通道尚未上传安装包，页面展示“暂无可下载客户端”，并保留正式/测试通道切换入口。
3. 下载按钮指向同一套公开文件接口：`/v1/client/releases/{channel}/files/{target}/{fileName}`。

### 3.8 客户端检查更新策略

`GET /v1/client/releases/{channel}/policy.json`

用途：公开返回指定通道的客户端更新策略。该接口不需要设备令牌，强制更新状态下仍允许旧客户端访问。

示例：

```json
{
  "channel": "production",
  "latestVersion": "1.2.13",
  "forceUpdate": true,
  "minSupportedVersion": "1.2.13",
  "latestJsonUrl": "http://127.0.0.1:8080/v1/client/releases/production/latest.json",
  "notes": "修复内网升级流程",
  "pubDate": "2026-04-24T10:00:00Z",
  "updatedAt": 1777250000000
}
```

说明：

1. `forceUpdate=false` 时，客户端可提示可选更新，但服务端不因版本较低拦截业务接口。
2. `forceUpdate=true` 时，低于 `minSupportedVersion` 的客户端只能访问 `/v1/client/releases/**`。
3. 桌面端每个 `/v1/*` 业务请求应携带 `X-Client-Version` 与 `X-Update-Channel`；服务端在缺失请求头时回退设备表 `client_version` 与 `production` 通道策略。

强制更新拦截响应：

```http
HTTP/1.1 426 Upgrade Required
Content-Type: application/json
```

```json
{
  "code": "UPDATE-REQUIRED",
  "message": "当前客户端版本过低，请升级到 1.2.13 或更高版本后继续使用",
  "data": null,
  "requestId": "..."
}
```

### 3.9 客户端检查更新元数据

`GET /v1/client/releases/{channel}/latest.json`

用途：公开返回 Tauri updater 兼容的 `latest.json`。该接口不需要设备令牌，避免 updater 无法附带自定义鉴权头。

当指定通道尚未通过管理端上传任何可用版本时，接口返回 `204 No Content`，表示当前没有可用更新，不记录业务异常。

示例：

```json
{
  "version": "1.2.13",
  "notes": "修复内网升级流程",
  "pub_date": "2026-04-24T10:00:00Z",
  "platforms": {
    "darwin-aarch64": {
      "signature": "...",
      "url": "http://127.0.0.1:8080/v1/client/releases/production/files/darwin-aarch64/PCIE_1.2.13_aarch64.dmg"
    }
  }
}
```

### 3.10 客户端下载安装包

`GET /v1/client/releases/{channel}/files/{target}/{fileName}`

用途：公开下载指定通道和平台的安装包文件，供 Tauri updater 下载。

## 4. 客户端接口

### 4.1 POST `/v1/client/register`

用途：客户端首次启动时注册设备。

请求：

```json
{
  "cdDevice": "9C:4E:36:AA:BB:CC",
  "naDevice": "PCIE-win32",
  "cdOrg": "ORG001",
  "clientVersion": "0.1.0",
  "updateChannel": "production",
  "osInfo": "Windows 10",
  "publicKey": "base64-spki-public-key"
}
```

说明：

1. 当前桌面端优先使用设备 MAC 地址作为 `cdDevice`；仅在当前环境无法读取 MAC 时才回退到本地兜底编码。
2. `publicKey` 为 ECDSA P-256 公钥，SPKI DER 后 base64 编码。
3. `cdOrg` 必须对应唯一的激活机构编码；管理端机构维护会校验 `cdOrg` 必填且激活记录内唯一，存量库若存在重复激活机构编码，注册接口会返回业务错误并提示先清理机构数据。
4. 首次注册不需要 `Authorization`；已存在且已绑定公钥的激活设备再次以同机构同 `cdDevice` 注册时，客户端应在 `Authorization: Bearer {deviceToken}` 中携带原设备令牌作为同终端证明。服务端验证令牌匹配后视为版本升级或本地密钥重建后的密钥轮换：更新设备名称、版本、系统信息、来源 IP 与 `device_public_key`，并返回该设备令牌，客户端无需人工更新密钥或生成新的兜底设备编码。
5. 已存在但未绑定公钥的激活历史设备同样允许在注册时补录公钥，用于兼容旧数据或管理员重新发放令牌后的客户端接管。
6. 服务端在注册时从请求来源记录 `registerIp`，并把同一地址写入 `lastSeenIp`；后续心跳会刷新 `lastSeenIp`，用于管理端排查旧版本客户端是否从固定终端或网段继续尝试连接。
7. 管理端停用令牌后，同机构同 `cdDevice` 视为被服务端封禁；该客户端即使清理本地 token 或旧版本触发自动重注册，也会被拒绝。若确需恢复，管理员应重新新增同 `cdDevice` 的激活令牌占位，再由客户端注册补录公钥；若设备已有公钥和令牌但本地令牌也丢失，应由管理员删除令牌执行重置，而不是匿名覆盖公钥。
8. 管理端删除令牌用于特殊排障重置，会移除旧令牌、公钥和状态记录；删除后同机构同 `cdDevice` 可重新执行注册，服务端生成新的 `idDevice` 与 `deviceToken`。
9. 同一机构下激活设备的 `cdDevice` 必须唯一；服务端用数据库唯一索引兜底并发注册，重复注册会返回业务错误。

响应 `data`：

```json
{
  "idDevice": "uuid",
  "deviceToken": "32-char-token",
  "heartbeatInterval": 30,
  "hasPublicKey": true
}
```

### 3.2 POST `/v1/client/heartbeat`

用途：设备心跳保活。

请求体允许为空，兼容当前客户端也允许带 `idDevice`。

响应 `data`：

```json
{
  "status": "ok",
  "serverTime": 1770000000000
}
```

### 3.3 GET `/v1/client/bootstrap`

用途：返回当前设备可见的 AI 配置、功能开关和版本号。

约束：

1. 只返回桌面端需要感知的非敏感配置
2. `baseUrl/audioBaseUrl`、`apiKey`、审查模型地址/密钥、知识库地址、PMPHAI `appKey/appSecret` 不得出现在响应中；上游连接信息只保留在服务端

响应 `data` 结构必须兼容 `regionalClient.ts`：

```json
{
  "llm": {
    "model": "deepseek-chat",
    "fastModel": "deepseek-chat-lite",
    "enableThinking": true,
    "audioModel": "whisper-1"
  },
  "speech": {
    "provider": "openai-compatible",
    "model": "whisper-1"
  },
  "knowledgeBase": {
    "enabled": true
  },
  "pmphai": {
    "enabled": true
  },
  "reviewer": {
    "enabled": false,
    "model": "gpt-4o-mini",
    "checkExaminationEnabled": true
  },
  "features": {
    "regionalMode": true,
    "aiProxyEnabled": true,
    "auditEnabled": true
  },
  "templateVersion": "2026.04.20.1",
  "dataPackageVersion": "2026.04.20.1",
  "promptVersion": "2026.04.20.1"
}
```

语音配置字段说明：

- `llm.fastModel`：供 `floating-ball/src/services/llm.ts` 的 `chatFast()` 使用的独立快速模型；未单独配置时回退 `llm.model`
- `llm.enableThinking`：由服务端统一托管的思考模式开关；`floating-ball` 只读消费该值，`/v1/ai/chat` 代理转发时会据此决定是否向上游传 `enable_thinking`
- `reviewer.checkExaminationEnabled`：控制是否启用 `check_examination` 独立审查；未显式配置时默认开启，保证旧配置行为不变
- `llm.audioModel`：服务端实际提交给上游的语音模型；`openai-compatible` 默认 `whisper-1`，`aliyun-dashscope` 默认 `qwen3-asr-flash`
- `speech.provider`：下发给 `floating-ball` 的语音提供方标识，当前兼容 `openai-compatible`、`aliyun-dashscope`、`funasr-websocket`
- `speech.model`：下发给 `floating-ball` 的实时语音模型标识；`aliyun-dashscope` 默认 `qwen-audio-3.0-asr-flash-streaming`，服务端会把管理端填写的兼容模型名原样提交给 `/api-ws/v1/inference` `run-task` 协议，不做模型名白名单回退；`funasr-websocket` 默认 `funasr-2pass`。是否启用 `/v1/ai/speech/realtime/ws` 由 `speech.provider` 决定
- 上游 `baseUrl`、`audioBaseUrl`、`speechRealtimeUrl`、知识库地址、`apiKey`、`audioApiKey` 均不下发给桌面端，由全医慧助服务端（PCIE Server）统一托管

配置优先级：机构级 > 区域级 > 全局级。

生效方式：`/v1/client/bootstrap` 与 `/v1/ai/chat` 每次请求都会按设备当前作用域实时解析 AI 配置；管理端修改配置后，无需重启服务，客户端下一次 `bootstrap` 或 AI 请求即可看到最新结果。

服务端出站安全约束：

1. `apiBaseUrl`、`audioBaseUrl`、`speechRealtimeUrl`、`reviewerBaseUrl`、`pmphaiBaseUrl` 只是候选上游地址；当前默认开启 `floating-ball.outbound-security.allow-all-hosts` / `FB_OUTBOUND_ALLOW_ALL_HOSTS=true`，不要求维护 host 白名单。
2. 当前默认允许 HTTP / WS 与私网地址，以适配医院内网模型服务；如需收紧安全边界，应显式关闭 `allow-all-hosts`、`allow-private-network` 与 `allow-insecure-http`，并配置 `allowed-hosts`。
3. 服务端按 host 进行本地限流与熔断；限流或熔断命中时，对客户端返回可理解的业务错误，不继续访问上游。
4. `application.yml`、`development`、`test`、`product` profile 当前均默认放开 host 白名单、HTTP / WS、私网地址与代理 fake-ip；host 被拒绝通常表示部署环境显式覆盖了对应开关。

### 3.4 GET `/v1/client/prompts/delta`

请求参数：`version`

响应 `data`：

```json
{
  "version": "2026.04.20.1",
  "prompts": [
    {
      "cdPrompt": "medicalRecordGeneration",
      "sysPrompt": "你是一名全科辅助诊疗助手",
      "userTemplate": "请根据{{chiefComplaint}}生成病历",
      "versionNum": "v1.0"
    }
  ]
}
```

### 3.5 GET `/v1/client/templates/delta`

请求参数：`version`

解析规则：

1. 优先从 `c_ai_symptom_template` 读取当前设备可见范围内已启用的症状模板
2. 合并优先级为机构级 > 区域级 > 全局级；同一 `cdSymptom` 由更高优先级记录覆盖
3. 若症状模板表当前作用域没有任何启用记录，则兼容回退到已发布的 legacy `template` 数据包
4. 若既无症状模板记录，也无 legacy `template` 数据包，则返回服务端内置症状模板基线
5. 当请求 `version` 与服务端当前版本一致时，仅返回最新 `version`，`western/tcm` 可为空数组

响应 `data` 必须兼容 `templateService.ts`：

```json
{
  "version": "2026.04.20.1",
  "western": [],
  "tcm": []
}
```

### 3.5.1 POST `/v1/client/inpatient-emr/templates/resolve`

用途：住院病历辅助书写前，按 HIS 传入的病历模板主键 `templateId` 复用服务端缓存，并返回管理端维护过的 AI 字段提示词。

请求：

```json
{
  "templateId": "emr_tpl_daily_course",
  "templateHash": "sha256-or-client-hash",
  "templateName": "日常病程记录",
  "htmlContent": "<div data-id=\"病程记录文本\"></div>",
  "fields": [
    {
      "id": "病程记录文本",
      "name": "病程记录文本",
      "meaning": "病程记录正文",
      "article": "",
      "type": "text",
      "readonly": false,
      "key": false,
      "defaultValue": "",
      "aiSuitable": true,
      "rule": {
        "source": "ai",
        "dependencies": ["registration", "orders", "temperatureChart"],
        "promptIntent": "inpatientRecordSection",
        "constraints": ["仅依据已提供 HIS 数据生成"]
      }
    }
  ]
}
```

响应 `data`：

```json
{
  "templateId": "emr_tpl_daily_course",
  "templateHash": "sha256-or-client-hash",
  "cacheHit": true,
  "templateName": "日常病程记录",
  "fields": [],
  "updatedAt": 1770000000000
}
```

说明：

1. `templateId` 相同且缓存启用时，服务端直接返回缓存字段，避免桌面端重复解析同一病历模板；若请求携带的 `templateName` 或 `htmlContent` 与缓存记录不同，服务端会用本次传入值更新缓存展示名和原生模板内容。
2. 未命中时，服务端保存请求中的 `templateId`、`templateName`、原生 `htmlContent`、内容 hash 与完整 `fields`；`fieldCount` 表示缓存字段总数。
3. 响应给桌面端时，若请求携带了字段列表，服务端以本次字段列表为基线合并已缓存字段的 AI 生成类型、提示词和生成规则，避免丢失页眉、姓名、床号等 HIS/系统字段；若请求未携带字段列表，则返回缓存中的完整字段。
4. 管理端维护的字段提示词覆盖会写入 `fields[*].rule.prompt`，桌面端生成住院病历时优先使用该提示词；未维护自定义提示词时，服务端返回的默认提示词会包含模板名称、记录类型、字段名称、所属段落和字段含义。管理端手动调整的 AI 生成类型会写入 `fields[*].aiSuitable` 与 `fields[*].rule.source`。
4. 本接口是桌面端模板缓存的唯一远程契约；后端不可用或未返回字段时，桌面端仅做确定性模板解析兜底，未知字段分类仍走服务端 LLM，不恢复第三方直连。

### 3.6 GET `/v1/client/mappings/delta`

请求参数：`version`

响应 `data` 必须兼容 `medicalData.ts` 当前解析逻辑，返回 CSV 原文字符串：

```json
{
  "version": "2026.04.20.1",
  "diagnoses": "id,code,name,keywords\n1,J00,感冒,咳嗽|流涕",
  "medicines": "id,name,spec\n1,阿莫西林,0.25g*24",
  "items": "id,name,category\n1,血常规,lab_test",
  "tcmDiagnoses": "",
  "tcmSyndromes": "",
  "tcmTreatments": ""
}
```

### 3.7 POST `/v1/client/audit/events/batch`

请求结构以 `auditUploader.ts` 当前实现为准：

```json
{
  "events": [
    {
      "eventId": "uuid",
      "eventType": "operation",
      "hisOrgId": "HIS-ORG-001",
      "hisOrgName": "市第一医院",
      "payload": {
        "module": "consultation",
        "action": "reference_feedback_diagnosis",
        "title": "接收 PHIS 引用回执",
        "sourceModule": "consultation_reference",
        "scene": "consultation-reference",
        "result": "success",
        "operationType": "api_call",
        "operationName": "reference_feedback:diagnosis",
        "details": {
          "consultationId": "CONSULT-001",
          "traceId": "TRACE-001",
          "requestSummary": "1 条消息，最新输入：请分析以下初步诊断的鉴别排查需求",
          "responseSummary": "{\"isNeeded\":true,\"severity\":\"critical\"}",
          "requestPayload": {
            "configProfile": "fast",
            "messages": [
              { "role": "system", "content": "..." },
              { "role": "user", "content": "..." }
            ],
            "stream": false,
            "traceId": "TRACE-001",
            "scene": "standalone-differential-diagnosis-checklist",
            "sourceModule": "differential_diagnosis_modal",
            "sessionId": "SESSION-001"
          },
          "responsePayload": {
            "content": "{\"isNeeded\":true,\"severity\":\"critical\"}"
          }
        }
      },
      "timestamp": 1770000000000
    }
  ]
}
```

说明：审计事件用于链路排障、操作追踪和反馈关联，不作为辅诊功能调用次数的统计事实源。

落表约定：

- `sdLogType` ← `eventType`
- `id_his_org` / `na_his_org` ← 事件外层 `hisOrgId` / `hisOrgName`；两者来自桌面端 SDK handshake，不从后台 `idOrg` 兜底
- `naModule` ← `payload.module`，若缺失则回退 `operationType / metricType / targetType / sessionType`
- `opAction` / `desOp` ← `payload.action`，若缺失则回退 `operationName / feedbackType / recType`
- `opResult` ← `payload.result`，若缺失则回退 `success`
- `traceId` / `consultationId` 优先从顶层字段提取，缺失时回退 `details.traceId / details.consultationId`
- `payloadJson` 保留完整原始 payload，供管理端详情查看和兼容后续扩展

审计可靠性约束：服务端自身产生的 AI / 语音代理审计日志属于核心证据链。成功代理调用若无法写入 `c_ai_op_log`，接口必须返回业务失败，不得继续向客户端报告成功；失败代理调用的补充审计日志若写入失败，必须以 error 级别记录完整异常和上下文，避免掩盖原始业务失败。

AI 调用类 `operation` 事件补充约束：

1. `details.requestSummary` / `details.responseSummary` 只作为列表与详情摘要，不得替代完整排障数据。
2. `details.requestPayload` / `details.responsePayload` 必须记录实际业务入参和业务出参；聊天代理场景记录发给 `/v1/ai/chat` 的请求体和返回 `content`，流式场景记录 `stream: true` 请求体和完整拼接后的响应文本。
3. API Key、Bearer Token、Cookie、签名、身份证号、手机号等敏感字段不得进入 payload；语音原始 base64 / 二进制音频不得进入 payload，语音日志只记录文件名、MIME、大小、转写文本和上游回文。
4. 管理端日志详情必须分区展示“完整入参”“完整出参”“原始 payload”，不能只展示 `requestSummary/responseSummary`。

### 3.8 POST `/v1/client/feature-events/batch`

用途：桌面端批量提交“用户实际调用功能”的业务事件。该接口是辅诊功能统计的唯一事实入口；一次用户明确调用一个功能只提交一条事件，底层 AI 代理、语音识别、回写、反馈等审计日志继续写入 `/v1/client/audit/events/batch`。

请求：

```json
{
  "events": [
    {
      "eventId": "uuid",
      "featureCode": "voice_consultation",
      "eventAction": "open_voice_consultation",
      "idempotencyKey": "voice_consultation:open_voice_consultation:EVENT-001",
      "traceId": "TRACE-001",
      "consultationId": "CONSULT-001",
      "sessionId": "SESSION-001",
      "sourceModule": "voice_consultation",
      "scene": "voice-consultation",
      "status": "success",
      "doctorId": "DOC-001",
      "doctorName": "张医生",
      "deptId": "DEPT-001",
      "deptName": "全科",
      "hisOrgId": "HIS-ORG-001",
      "hisOrgName": "市第一医院",
      "payload": {
        "patientId": "PAT-001"
      },
      "timestamp": 1770000000000
    }
  ]
}
```

响应 `data`：

```json
{
  "accepted": 1,
  "skipped": 0,
  "rejected": 0,
  "rejections": []
}
```

约束：

1. `idempotencyKey` 必填；服务端以 `idDevice + idempotencyKey` 幂等，重复上报只计入 `skipped`，不重复计数。
2. `featureCode` 当前固定支持：`voice_consultation`、`smart_consultation`、`report_interpretation`、`chat`、`diagnosis_checklist`、`diagnosis_recommendation`、`medication_recommendation`、`examination_recommendation`、`lab_test_recommendation`、`procedure_recommendation`、`treatment_plan_recommendation`、`knowledge_usage`。
3. 后台展示名称由服务端按 `featureCode` 统一映射为：语音问诊、智能问诊、报告单解读、聊天、AI诊断鉴别、AI推荐诊断、AI推荐用药、AI推荐检查、AI推荐检验、AI推荐处置、AI推荐治疗方案、知识库使用。
4. `traceId`、`consultationId`、`sessionId` 只用于关联审计链路，不参与调用次数累加。
5. 统计口径按用户显式功能入口统一：智能问诊、语音问诊、报告单解读、聊天、知识库使用按主功能入口计数；知识库批量检索只按一次用户检索动作计数，不按内部拆开的多个查询词累加；诊断鉴别和推荐诊断/用药/检查/检验/处置/诊疗方案推荐只统计医生显式触发的独立辅助入口。来自 HIS Bridge 的完整问诊、语音问诊和 `assist` 入口在桌面端接诊上下文校验通过并准备打开目标界面时即记录一次成功调用；同一就诊再次显式触发入口按新调用计数，后续 AI 生成、问诊提交、PHIS 回写和审计日志不重复拆分计数。
6. 不支持的 `featureCode`、缺失 `idempotencyKey`、不可序列化的 `payload` 计入 `rejected`，并在 `rejections[]` 返回 `index/eventId/featureCode/reason`；服务端不得把这类数据静默计入 `skipped`。
7. `hisOrgId/hisOrgName` 由桌面端在事件产生时从 SDK handshake 固化，服务端分别写入 `c_ai_feature_event.id_his_org/na_his_org`；`id_org/id_region` 仍以设备鉴权为准。旧版客户端未上报 `hisOrgId` 的事件继续接收，但无法进入指定 HIS 机构的筛选结果。

### 3.9 POST `/v1/client/recommendation-preferences/events/batch`

用途：桌面端批量提交“医生在标准候选项中的选择偏好”。该接口只接收已完成本地/HIS 目录匹配后的诊断和医嘱标准项，不学习 AI 原始文案。

请求：

```json
{
  "events": [
    {
      "eventId": "uuid",
      "idempotencyKey": "recommendation-preference:CONSULT-001:diagnosis:D001",
      "recommendationType": "diagnosis",
      "action": "final_select",
      "itemKey": "diagnosis:D001",
      "itemId": "D001",
      "itemCode": "J06.900",
      "itemName": "急性上呼吸道感染",
      "selected": true,
      "primary": true,
      "traceId": "TRACE-001",
      "consultationId": "CONSULT-001",
      "sessionId": "SESSION-001",
      "sourceModule": "voice_consultation",
      "scene": "voice-consultation",
      "doctorId": "DOC-001",
      "doctorName": "张医生",
      "deptId": "DEPT-001",
      "deptName": "全科",
      "promptVersion": "prompt-20260616",
      "templateVersion": "template-20260616",
      "modelVersion": "qwen-plus",
      "payload": {
        "matchStatus": "exact"
      },
      "timestamp": 1770000000000
    }
  ]
}
```

响应 `data`：

```json
{
  "accepted": 1,
  "skipped": 0,
  "rejected": 0,
  "rejections": []
}
```

约束：

1. 该接口必须走 `Authorization: Bearer {deviceToken}` 与 ECDSA P-256 请求签名。
2. `eventId` 或 `idempotencyKey` 至少提供一个；服务端以 `idDevice + idempotencyKey` 幂等，未传 `idempotencyKey` 时使用 `event:{eventId}` 兜底，重复上报计入 `skipped`。
3. `recommendationType` 首版固定支持：`diagnosis`、`medicine`、`exam`、`lab_test`、`procedure`。
4. `action` 首版固定支持：`final_select`、`manual_match`、`confirm_match`。
5. `itemKey` 必须是标准候选项身份：诊断使用 `diagnosis:{id}`，无标准 ID 时使用 `diagnosis-code:{code}`；医嘱使用 `order:{type}:{matchedItem.id || idSrv}`。
6. 服务端只聚合 `selected=true` 或 `manual_match / confirm_match` 的标准项；缺少 `itemKey`、`itemKey` 与类型不匹配、类型不支持、action 不支持、payload 不可序列化计入 `rejected`。

### 3.10 POST `/v1/client/recommendation-preferences/rank`

用途：桌面端传入当前已匹配候选项，服务端按“医生级 > 科室级 > 机构级”返回偏好分和名次 boost。服务端不新增候选、不替换候选、不根据 AI 原文匹配。

请求：

```json
{
  "recommendationType": "diagnosis",
  "scene": "voice-consultation",
  "doctorId": "DOC-001",
  "deptId": "DEPT-001",
  "candidates": [
    {
      "itemKey": "diagnosis:D001",
      "itemId": "D001",
      "itemCode": "J06.900",
      "itemName": "急性上呼吸道感染",
      "originalRank": 0
    }
  ]
}
```

响应 `data`：

```json
{
  "enabled": true,
  "recommendationType": "diagnosis",
  "items": [
    {
      "itemKey": "diagnosis:D001",
      "preferenceScore": 0.8,
      "boost": 0.7561,
      "sampleCount": 10,
      "scope": "doctor",
      "reason": "doctor_preference"
    }
  ]
}
```

约束：

1. 该接口必须走设备鉴权与 ECDSA P-256 请求签名。
2. `recommendationPreferenceRerank=false` 或候选样本不足时，服务端返回 `enabled=false` 或 `boost=0`，客户端必须静默回退原排序。
3. `boost` 表示可抵扣的原始排序位置数，客户端按 `originalRank - boost` 做稳定排序；默认最大名次 boost 为 1.2，强偏好医生级候选最多约上移 1 个相邻名次。
4. 服务端默认样本阈值为 3；样本不足时返回 `sampleCount` 与 `boost=0`，不参与重排。
5. 默认公式：`boost = 1.2 * preferenceScore * sampleConfidence * scopeWeight`，其中 `sampleConfidence = min(1, log(1 + sampleCount) / log(21))`，`scopeWeight` 为医生级 `1.0`、科室级 `0.7`、机构级 `0.45`。
6. 首版通过 `c_ai_config.features_json` 的 `recommendationPreferenceRerank` 开关灰度启用；`recommendationPreferenceRerankMinCount=3` 与 `recommendationPreferenceRerankMaxBoost=1.2` 作为服务端默认常量，不要求管理端首版可配置。
7. rank 响应只包含请求候选的偏好信息，不返回患者信息或历史原始事件。

### 3.11 POST `/v1/client/user-logs/consultations`

用途：桌面端提交运维用户日志快照。该接口独立于原始操作日志，用于按"一名患者一次问诊轮次"聚合首版 AI 生成内容和医生最终修改内容。

请求：

```json
{
  "consultationId": "CONSULT-001",
  "consultationRoundId": "550e8400-e29b-41d4-a716-446655440000",
  "consultationType": "voice",
  "consultationTime": 1770000000000,
  "patientId": "P001",
  "patientName": "王某",
  "patientGender": "男",
  "patientAge": "45岁",
  "doctorId": "D001",
  "doctorName": "张医生",
  "orgCode": "HIS_ORG_CODE",
  "hisOrgId": "HIS_ORG_001",
  "orgName": "区域中心医院纯名",
  "deptId": "DEPT001",
  "deptName": "全科",
  "speechText": "医生您好，我发热一天，最高39度，伴咳嗽、咽痛。",
  "audio": "base64-audio",
  "audioMimeType": "audio/wav",
  "audioFormat": "wav",
  "audioFileName": "voice-consultation-1770000000000.wav",
  "firstSnapshot": {
    "chiefComplaint": "咳嗽3天",
    "historyOfPresentIllness": "患者3天前出现咳嗽...",
    "pastMedicalHistory": "2型糖尿病；系统性红斑狼疮",
    "personalHistory": "否认吸烟史、饮酒史及职业暴露史",
    "familyHistory": "否认家族重大遗传病史",
    "physicalExam": "双肺呼吸音清，未闻及干湿啰音",
    "precautions": "监测体温及血糖，症状加重时及时复诊",
    "diagnoses": [{ "name": "急性上呼吸道感染", "selected": true }],
    "medicines": [{ "name": "氨溴索", "selected": true }],
    "examinations": [{ "name": "胸部CT", "selected": false }],
    "labTests": [{ "name": "血常规", "selected": true }],
    "procedures": [{ "name": "雾化吸入", "selected": true }]
  },
  "finalSnapshot": {
    "chiefComplaint": "咳嗽、咳痰3天",
    "historyOfPresentIllness": "医生修改后的最终现病史...",
    "pastMedicalHistory": "2型糖尿病；系统性红斑狼疮",
    "personalHistory": "否认吸烟史、饮酒史及职业暴露史",
    "familyHistory": "否认家族重大遗传病史",
    "physicalExam": "双肺呼吸音清，未闻及干湿啰音",
    "precautions": "监测体温及血糖，症状加重时及时复诊",
    "diagnoses": [{ "name": "急性支气管炎", "selected": true }],
    "medicines": [{ "name": "氨溴索", "selected": true }],
    "examinations": [],
    "labTests": [{ "name": "血常规", "selected": true }],
    "procedures": [{ "name": "雾化吸入", "selected": true }]
  },
  "selectionSnapshot": {
    "selectedDiagnosisNames": ["急性支气管炎"],
    "selectedMedicineNames": ["氨溴索"],
    "selectedExaminationNames": [],
    "selectedLabTestNames": ["血常规"],
    "selectedProcedureNames": ["雾化吸入"]
  }
}
```

约束：

1. `consultationType` 取值：`voice`（语音问诊）、`smart`（智能问诊）。
2. `consultationRoundId` 为必填字段，由客户端在每轮问诊开始时生成 UUID。`consultationId` 是就诊锚点（visitId/patientId），同一患者多次问诊共用，仅用于 timeline 聚合和展示；`consultationRoundId` 是问诊轮次标识，每轮问诊唯一，贯穿该轮所有提交（speech → firstSnapshot → finalSnapshot/abandoned）。
3. 服务端按 `consultationRoundId` 合并同一轮问诊的多次提交；数据库通过唯一索引 `uk_c_ai_user_log_round_active` 保证同一 `consultationRoundId` 同时只有一条 `generated` 记录。先收到首版快照则创建记录，后收到最终快照则更新同一条记录为 `completed` 或 `abandoned`。
4. 若同一就诊在回写或放弃后再次发起智能问诊/语音问诊，客户端必须生成新的 `consultationRoundId`，服务端据此创建新的用户日志记录。客户端不需要上报每一次中间编辑，最终快照只代表医生提交/回写或放弃时的最终状态。
5. `speechText` / `audio` 仅用于语音问诊输入复盘；`audio` 为 base64，不带 Data URL 前缀。`audioFormat` 可选，用于在 `audioMimeType` 缺失时辅助推断文件扩展名。服务端把音频落到 `floating-ball.audit.speech-file-dir`，数据库只保存文件路径、MIME、文件名和大小，不把原始 base64 写入快照 JSON。
6. `idOrg` 由设备鉴权解析出的后台机构 ID 持久化，用于后台配置、权限范围和平台机构筛选；`hisOrgId` 表示 HIS 端机构 ID，桌面端只能取 SDK handshake `urt.userRoleDepts.orgId`，服务端持久化到 `id_his_org`，用于问诊来源追踪和 HIS 机构统计，不再覆盖 `id_org`，也不再用 `orgCode` 兜底。
7. `orgName` 桌面端取 SDK handshake `urt.orgPureName`；`deptId` 取 `urt.userRoleDepts.deptId`。
8. `firstSnapshot` 与 `finalSnapshot` 的病历字段统一包含 `chiefComplaint`、`historyOfPresentIllness`、`pastMedicalHistory`、`personalHistory`、`familyHistory`、`physicalExam`、`precautions`；管理端按这 7 个字段展示首版内容与最终差异。旧客户端缺失新增字段时按空文本兼容。

### 3.12 POST `/v1/client/feedbacks`

用途：桌面端提交问题反馈，统一覆盖**通用反馈（设置入口）**、**语音推荐反馈**、**语音病例字段反馈**、**语音整页评分反馈**四种场景。支持评分、说明、内置截图、问题标签、医生/机构/科室身份回填，以及最近一次 AI 调用链路上下文。

版本语义：当 `chainContext.feedbackScopeKey` 存在时，服务端按“同一设备 + 同一反馈槽位”保留修订历史。新提交会生成一条新记录，并把上一条最新记录标记为历史版本；默认列表与统计仅看最新版本。

请求：

```json
{
  "sessionId": "session-123",
  "traceId": "trace-123",
  "sourceModule": "voice_record_field",
  "kind": "record_field",
  "severity": "medium",
  "score": 3,
  "comment": "主诉漏掉了发热三天",
  "tags": ["data_accuracy", "workflow"],
  "hasCorrection": true,
  "doctorId": "10001",
  "doctorName": "张医生",
  "orgName": "示例社区卫生服务中心",
  "deptId": "DEPT001",
  "deptName": "全科诊疗",
  "screenshot": {
    "fileName": "feedback-2026-04-22.png",
    "mimeType": "image/png",
    "dataUrl": "data:image/png;base64,iVBORw0KGgoAAA..."
  },
  "chainContext": {
    "kind": "record_field",
    "consultationId": "...",
    "aiTrace": { "traceId": "...", "model": "...", "requestSummary": "..." },
    "recordField": { "fieldKey": "chiefComplaint", "originalValue": "...", "currentValue": "...", "correctedValue": "..." }
  }
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kind` | enum | `general` \| `recommendation` \| `record_field` \| `session`，默认 `general` |
| `severity` | enum | `low` \| `medium` \| `high`，默认 `medium`；通用反馈按评分推导（≤2 high / 3 medium / ≥4 low） |
| `tags` | string[] | 问题标签，最多 20 条，去重并修剪空白；通用反馈使用预置标签 (`recommendation_quality`, `data_accuracy`, `workflow`, `stability`, `ui`, `other`)，语音反馈使用 `issueTags` |
| `hasCorrection` | boolean | 是否包含医生修正（语音 `record_field` / `recommendation` 反馈用） |
| `doctorId` / `doctorName` | string | 医生身份；桌面端从 SDK handshake `urt.idDoctor / naDoctor` 缓存 |
| `orgName` | string | 机构名称；桌面端从 SDK handshake `urt.orgPureName` 缓存 |
| `deptId` / `deptName` | string | 科室身份；`deptId` 从 SDK handshake `urt.userRoleDepts.deptId` 缓存 |
| `sourceModule` | string | 反馈入口标识。常见取值：`settings_feedback`、`voice_session`、`voice_recommendation`、`voice_record_field` |
| `chainContext.consultationId` | string | 可选，当前问诊锚点；用于把同一问诊的多次反馈修订归到同一槽位 |
| `chainContext.feedbackScopeKey` | string | 可选，反馈槽位唯一键，建议由客户端按“问诊锚点 + 模块”生成 |
| `chainContext.feedbackRevision` | number | 可选，客户端感知到的修订号；服务端会以库内最新版本为准继续递增 |
| `chainContext.previousFeedbackId` | string | 可选，客户端上次提交成功返回的反馈 ID；用于辅助串联修订历史 |

约束：

1. `score` 范围为 `1-5`
2. `comment` 必填（通用反馈若仅勾选标签则前端拼成 `问题标签：xxx` 兜底），建议不超过 2000 字
3. `screenshot` 为可选；若存在，必须是 `data:image/*;base64,...` 形式
4. `traceId` 为可选，但若桌面端能拿到最近一次 AI 代理调用上下文，必须优先回传
5. 设备鉴权携带的 `orgCode` 与请求体 `orgName` 不冲突时同时持久化

响应 `data`：

```json
{
  "feedbackId": "uuid",
  "status": "accepted"
}
```

版本持久化约定：

- 若 `feedbackScopeKey` 缺失，则按普通单条反馈处理，`fg_latest=1`、`revision_no=1`
- 若 `feedbackScopeKey` 存在，则同一 `id_device + feedback_scope_key` 下仅一条激活记录 `fg_latest=1`，该约束由数据库唯一索引兜底
- 新版本会在同一事务内保留历史记录、降级旧最新版，并写入 `id_feedback_root`、`previous_feedback_id`、`revision_no`

响应 `data`：

```json
{
  "accepted": 1
}
```

## 4. AI 代理接口

### 4.1 POST `/v1/ai/chat`

用途：OpenAI 兼容聊天代理。

约束：

1. 服务端按设备所属机构 / 区域解析当前生效 AI 配置
2. 当 `stream=true` 时，先完成配置校验，再按 OpenAI 风格 `data: ...` SSE 帧逐段转发上游模型输出，而不是把完整结果一次性封装后再返回
3. 实际生效的主模型 / 快速模型 / 审查模型与 `enableThinking` 开关以服务端当前配置解析结果为准；客户端不应依赖缓存的 `model` 值覆盖服务端配置
4. 若上游模型服务返回 4xx / 5xx，服务端应尽量提取上游响应体中的可读错误消息，并作为当前接口错误消息返回，避免只暴露 WebClient 堆栈
5. 实际访问上游前必须通过出站 host allowlist、私网拦截、限流与熔断校验；流式 SSE 由服务端有界线程池转发，上游或线程池拥塞时返回错误帧并结束流。

请求：

```json
{
  "configProfile": "default",
  "model": "deepseek-chat",
  "consultationId": "CONSULT-001",
  "messages": [
    { "role": "system", "content": "你是医生助手" },
    { "role": "user", "content": "患者咳嗽三天" }
  ],
  "stream": false,
  "temperature": 0.2
}
```

字段说明：

- `configProfile`：可选，默认 `default`
- 当值为 `fast` 时，服务端优先使用当前设备可见 AI 配置中的 `fastModelName`；未配置时回退主模型配置
- 当值为 `reviewer` 时，服务端优先使用当前设备可见 AI 配置中的独立审查模型地址 / 密钥 / 模型；缺失项回退主模型配置
- `consultationId`：可选，当前问诊或病历生成运行的业务锚点；服务端会写入 `c_ai_op_log.consultation_id`，供调用排障和业务关联查询使用
- `enable_thinking` 是否开启由服务端当前 AI 配置统一决定；桌面端不单独透传该开关覆盖服务端配置

非流式响应 `data`：

```json
{
  "content": "建议考虑上呼吸道感染。"
}
```

流式响应：

- `Content-Type: text/event-stream`
- 每帧：`data: {"choices":[{"delta":{"content":"..."}}]}\n\n`
- 结束：`data: [DONE]\n\n`

说明：`floating-ball` 当前 SSE 解析逻辑直接消费 OpenAI 风格 `choices[0].delta.content`。

### 4.2 POST `/v1/ai/speech/transcribe`

请求：

```json
{
  "audio": "base64-audio",
  "mimeType": "audio/webm",
  "fileName": "chat-input-1713686400000.webm",
  "scene": "chat-input"
}
```

字段说明：

- `audio`：必填，`floating-ball` 端录音文件的 base64 内容，不带 Data URL 前缀
- `mimeType`：可选，录音 MIME 类型；服务端会据此构造上游 multipart 文件内容类型
- `fileName`：可选，录音文件名；未传时服务端按 MIME 类型自动补默认扩展名
- `scene`：可选，录音来源场景，如 `chat-input`、`voice-consultation`

服务端处理约束：

1. 先接收 `floating-ball` 上传的 base64 录音
2. 在服务端解码为真实字节数组
3. 若收到 `audio/pcm` / `format=pcm`，服务端会先补 WAV 头再标准化为 `.wav`
4. `speechProvider=openai-compatible` 时，以 `multipart/form-data` 的 `file` 字段转发到上游 `/audio/transcriptions`
5. `speechProvider=aliyun-dashscope` 时，将标准化后的音频组装为 Data URL，调用 DashScope 兼容模式 `/chat/completions`
6. `speechProvider=funasr-websocket` 时，批量接口仍按 OpenAI 兼容方式调用独立 `audioBaseUrl`；FunASR 原生 WebSocket 只承担实时识别，未配置批量 HTTP 上游时实时连接失败后不保证批量降级成功
7. 不把原始 base64 音频直接原样透传给上游 OpenAI 兼容接口

响应 `data`：

```json
{
  "text": "转写结果"
}
```

### 4.3 POST `/v1/ai/speech/realtime`

用途：实时语音识别批量兜底代理。桌面端实时流式优先使用 `4.3.1` WebSocket 通道；若 WebSocket 不可用，再在录音结束后调用本接口上传整段录音。

### 4.3.1 WebSocket `/v1/ai/speech/realtime/ws`

用途：DashScope Qwen-Audio-3.0-ASR-Flash-Streaming、Paraformer 或自建 FunASR 实时语音识别代理。

连接：

```text
ws(s)://{server}/v1/ai/speech/realtime/ws?token={deviceToken}&clientVersion={version}&updateChannel={channel}&ts={timestamp}&nonce={nonce}&sig={signature}
```

约束：

1. 浏览器 WebSocket 无法设置 `Authorization` 请求头，因此本通道通过 `token` query 参数携带设备令牌；服务端握手阶段按 `DeviceService.findActiveByToken` 校验。
2. `ts / nonce / sig` 为 WebSocket 握手签名参数，签名原文与 HTTP 一致：`GET`、路径 `/v1/ai/speech/realtime/ws`、空 body SHA-256。
3. `clientVersion / updateChannel` 用于握手阶段强制更新门禁；版本过低时拒绝连接。
4. `speechProvider=aliyun-dashscope` 时，服务端连接 `speechRealtimeUrl`；该字段留空时默认使用中国内地地址 `wss://dashscope.aliyuncs.com/api-ws/v1/inference`，并使用 `audioApiKey` 或主模型 `apiKey` 作为 Bearer Token。国际站使用 `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference`，密钥必须与服务地域匹配。
5. `speechProvider=funasr-websocket` 时，`speechRealtimeUrl` 必填且必须为 `ws://` 或 `wss://`；服务端不发送 DashScope Bearer Token，连接后先发送 FunASR 初始化帧 `{mode:"2pass",chunk_size:[5,10,5],chunk_interval:10,wav_name:"microphone",wav_format:"pcm",is_speaking:true,itn:true}`，结束时发送 `{is_speaking:false}`。
6. FunASR 上游返回的 `2pass-online` 文本作为实时临时结果，`2pass-offline` / `offline` 或 `is_final=true` 文本作为句末结果；部分原生部署在收到 `{is_speaking:false}` 后仍返回 `is_final=false`，服务端必须把结束请求后的首个 offline 结果视为最终结果并立即收口。桌面端协议保持不变。
7. `qwen-audio-3.0-asr-flash-streaming` 使用本通道现有 `/api-ws/v1/inference` `run-task` 协议，是 DashScope 默认实时模型；模型名必须填写在 `speechModel`，不得填写在批量 `audioModel`。DashScope `qwen3-asr-flash-realtime` 属于另一套 `/api-ws/v1/realtime` session 协议，不属于当前代理通道；管理端保存该模型时必须明确提示协议不兼容，不得静默替换模型。
8. 上游 WebSocket 地址同样走出站安全门；当前默认允许 `ws` / `wss`、私网地址与空 host 白名单，以适配医院内网实时语音上游；如需收紧，可显式关闭 `allow-insecure-http`、`allow-private-network` 并配置 `allowed-hosts`。
9. DashScope `run-task.payload.parameters` 固定包含 `heartbeat: true`，与生产实时链路和管理端模型可用性探测保持一致。服务端收到上游 `task-failed` 或非主动关闭时返回一次 `error` 后关闭客户端连接；正常 `task-finished` 返回一次 `final` 后关闭连接。桌面端在仍处于录音状态时收到这些终止信号会自动重新建立本接口，重连期间暂存 PCM；发生过中断的整段录音在停止后通过 `POST /v1/ai/speech/realtime` 批量补录。

客户端发送：

- 二进制帧：PCM 16k 单声道音频 chunk
- 文本帧：`{"type":"finish"}` 表示结束录音

服务端返回：

```json
{ "type": "text", "text": "患者咳嗽", "isSentenceEnd": false }
```

```json
{ "type": "final", "text": "完整识别文本" }
```

```json
{ "type": "error", "message": "错误说明" }
```

### 4.4 POST `/v1/knowledge/pmphai/search`

用途：人卫 Inside 智能检索代理。

请求：

```json
{
  "query": "急性上呼吸道感染",
  "type": 1,
  "limit": 5,
  "score": 0.7,
  "enableAbstract": true
}
```

响应 `data`：兼容 `pmphai.ts` 的 `SearchResult[]`。

### 4.5 POST `/v1/knowledge/pmphai/clip`

用途：获取文献详情。

请求：

```json
{
  "id": "000000000000000001"
}
```

响应 `data`：兼容 `pmphai.ts` 的 `ClipData`。

### 4.6 POST `/v1/knowledge/pmphai/list`

用途：文档浏览 / 传统列表搜索。

请求：

```json
{
  "key": "感冒",
  "kgBaseId": "0001AA100000000EKLSX",
  "pageSize": 10,
  "page": 1,
  "sortField": "initials",
  "sortRule": "asc"
}
```

响应 `data`：兼容 `pmphai.ts` 的 `ListSearchResponse`。

### 4.7 POST `/v1/knowledge/pmphai/page-url`

用途：生成人卫 Inside 页面的签名跳转地址。

请求：

```json
{
  "pageName": "home",
  "kgBaseId": "0001AA100000000EKLSX",
  "id": "000000000000000001",
  "contentId": "content-001",
  "kgFields": "适用性别,用法用量",
  "muluId": "mulu-001",
  "catalogueId": "catalogue-001",
  "originUrl": "https://www.pmphai.com"
}
```

响应 `data`：

```json
{
  "url": "https://pmphai.example.com/aip/oauth/authorize?..."
}
```

### 4.8 GET `/v1/knowledge/pmphai/kgbases`

用途：获取知识库列表。

请求参数：

- `kgBaseId`：可选

### 4.9 GET `/v1/knowledge/pmphai/categories`

用途：获取指定知识库的分类树。

请求参数：

- `kgBaseId`：必填

用途：实时语音代理。

请求：

```json
{
  "audio": "base64-audio",
  "format": "pcm",
  "mimeType": "audio/pcm",
  "fileName": "voice-consultation-1713686400000.pcm",
  "scene": "voice-consultation"
}
```

说明：

1. `floating-ball` 当前会在语音录制结束后批量上传整段录音，而不是逐帧 WebSocket 透传。
2. 服务端处理方式与 `/v1/ai/speech/transcribe` 一致：先接收 base64 录音，再解码为真实文件后转发给上游语音转写接口。
3. 对 `pcm` 原始录音，服务端会补 WAV 头后再上传，兼容常见 OpenAI 兼容转写服务。
4. `format` 主要用于标记原始采样格式，当前常见值为 `pcm`。

响应 `data`：

```json
{
  "text": "实时识别结果"
}
```

## 5. 管理端接口

> 路径前缀：`/admin/api/*`
>
> 说明：当前包含管理员鉴权、基础治理能力和首期 CRUD 契约。

### 5.1 POST `/admin/api/auth/login`

用途：管理员登录。

请求：

```json
{
  "username": "admin",
  "password": "admin123"
}
```

响应 `data`：

```json
{
  "token": "admin-token",
  "expiresAt": 1770000000000,
  "user": {
    "idUser": "ADMIN001",
    "cdUser": "admin",
    "naUser": "系统管理员",
    "idOrg": "ORG001",
    "roles": ["SYSTEM_ADMIN"]
  }
}
```

### 5.2 GET `/admin/api/auth/me`

用途：返回当前登录管理员信息。

### 5.3 POST `/admin/api/auth/logout`

用途：退出登录；首期可为轻量无状态实现。

### 5.4 GET `/admin/api/stats/overview`

用途：返回管理端首页概览统计。

响应 `data`：

```json
{
  "regionCount": 1,
  "orgCount": 1,
  "deviceCount": 1,
  "configCount": 1,
  "symptomTemplateCount": 1,
  "logCount": 1,
  "userCount": 1,
  "roleCount": 1
}
```

### 5.5 GET `/admin/api/analytics/summary`

用途：返回综合概况统计分析核心指标卡片数据。

请求参数：

- `dateFrom` — 开始日期（yyyy-MM-dd）
- `dateTo` — 结束日期（yyyy-MM-dd）
- `idRegion` — 区域ID（可选）
- `idOrg` — 后台平台机构ID（可选）
- `hisOrgId` — HIS机构ID（可选）

筛选约束：

1. 统计分析、辅诊功能、用户活跃度三组统计接口只统计启用的平台范围内数据：区域与后台机构均需满足 `fg_active='1' AND sd_status='1'`。
2. 当同时传入 `idRegion` 与 `idOrg` 时，后台机构必须归属该区域；若不匹配，查询返回空统计结果。
3. `idOrg` 与 `hisOrgId` 是两个独立维度：前者来自设备鉴权并关联 `c_ai_org`，后者来自 SDK handshake 并匹配业务事实表的 `id_his_org`；同时传入时取交集，不允许互相兜底。
4. 管理端统计分析、辅诊功能和用户活跃度页面仅展示启用的区域与业务事实汇总的 HIS 机构下拉，不展示平台机构下拉；`idOrg` 仅作为后台/API 兼容筛选保留，旧版或其他调用方传入时仍适用上述启用状态、区域归属和双机构交集约束。

响应 `data`：

```json
{
  "aiServiceTotal": 12345,
  "avgDailyAiService": 412,
  "aiAdoptionRate": "78.5",
  "diagnosisMatchRate": "65.2",
  "activeDoctorCount": 86,
  "consultationTotal": 2345,
  "aiServiceGrowth": "12.5",
  "avgDailyGrowth": "8.3",
  "adoptionRateGrowth": "-1.2",
  "matchRateGrowth": "3.7",
  "activeDoctorGrowth": "5",
  "consultationGrowth": "15.8"
}
```

指标计算口径：

| 指标 | 分子 | 分母 | 说明 |
|------|------|------|------|
| `aiServiceTotal` | `COUNT(c_ai_feature_event WHERE event_status='success')` | — | 统计用户实际调用辅诊功能次数，不按底层 AI 代理日志累加 |
| `avgDailyAiService` | `aiServiceTotal` | 查询天数 | |
| `aiAdoptionRate` | `COUNT(status='completed')` | `COUNT(全部问诊)` | 仅"一键回写"计为采纳 |
| `diagnosisMatchRate` | `COUNT(JSON_VALUE(change_summary_json,'$.diagnosisChanges')=0)` | `COUNT(status IN ('completed','abandoned'))` | 比较 AI 最初诊断与医生最终诊断是否一致 |
| `activeDoctorCount` | `COUNT(DISTINCT id_doctor)` | — | |
| `consultationTotal` | `COUNT(全部问诊)` | — | |

### 5.6 GET `/admin/api/analytics/trend`

用途：返回功能调用量与问诊量按日聚合的趋势数据。

请求参数同 5.5。

响应 `data`：

```json
{
  "days": ["2026-05-01", "2026-05-02", "..."],
  "aiServiceValues": [400, 420, 415, "..."],
  "consultationValues": [75, 82, 78, "..."]
}
```

### 5.7 GET `/admin/api/analytics/distribution`

用途：返回功能调用量和问诊量的 HIS 机构分布与后台区域分布数据。功能调用量机构分布按 `c_ai_feature_event.id_his_org` 分组，问诊量机构分布按 `c_ai_user_consultation_log.id_his_org` 分组，均不按后台 `id_org` 分组；区域分布分别按事实记录的后台机构关联启用区域后聚合。

请求参数同 5.5。

响应 `data`：

```json
{
  "orgDistribution": [
    { "name": "市第一医院", "value": 600 },
    { "name": "市第二医院", "value": 800 }
  ],
  "regionDistribution": [
    { "name": "东城区", "value": 4938, "percentage": "40" },
    { "name": "西城区", "value": 3704, "percentage": "30" }
  ],
  "totalService": 12345,
  "consultationOrgDistribution": [
    { "name": "市第一医院", "value": 120 },
    { "name": "市第二医院", "value": 80 }
  ],
  "consultationRegionDistribution": [
    { "name": "东城区", "value": 140, "percentage": "70" },
    { "name": "西城区", "value": 60, "percentage": "30" }
  ],
  "totalConsultation": 200
}
```

### 5.7.1 GET `/admin/api/analytics/export`

用途：按当前统计分析筛选条件导出 Excel 文件，包含核心指标、趋势明细、功能调用量机构/区域分布和问诊量机构/区域分布。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：同 5.5。

响应：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

响应头：

- `Content-Disposition: attachment; filename*=UTF-8''analytics-*.xlsx`

### 5.8 GET `/admin/api/analytics/function-modules`

用途：返回所有辅诊功能展示名称列表，供辅诊功能应用统计页的功能多选下拉使用。该接口返回产品功能维度，不返回底层 AI 操作名或审计日志来源模块名。

无请求参数。

响应 `data`：

```json
["语音问诊", "智能问诊", "报告单解读", "聊天", "AI诊断鉴别", "AI推荐诊断", "AI推荐用药", "AI推荐检查", "AI推荐检验", "AI推荐处置", "AI推荐治疗方案", "知识库使用"]
```

### 5.8.1 GET `/admin/api/analytics/his-org-options`

用途：返回三个统计页面共用的 HIS 机构筛选选项。选项合并 `c_ai_feature_event.id_his_org/na_his_org` 与 `c_ai_user_consultation_log.id_his_org/na_org`，同一 `hisOrgId` 只返回一项。

响应 `data`：

```json
[
  { "hisOrgId": "HIS-ORG-001", "hisOrgName": "市第一医院" }
]
```

只返回非空 `hisOrgId`；名称缺失时管理端显示 ID。该接口不从 `c_ai_org.id_org/cd_org` 推导或伪造 HIS 机构。

### 5.9 GET `/admin/api/analytics/function-usage`

用途：返回辅诊功能应用统计数据，包含汇总指标、功能使用排行、趋势与分页明细。统计口径为 `/v1/client/feature-events/batch` 提交的“用户实际调用功能事件”，不按底层 AI 操作日志条数累加。

请求参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `dateFrom` | string | 起始日期 yyyy-MM-dd |
| `dateTo` | string | 截止日期 yyyy-MM-dd |
| `idRegion` | string | 区域 ID（可选） |
| `idOrg` | string | 后台平台机构 ID（可选） |
| `hisOrgId` | string | HIS 机构 ID（可选） |
| `functionModules` | string[] | 功能筛选（可选，多选），支持传功能展示名称，也兼容历史 `source_module / op_action / scene_code / na_module` 原始编码 |
| `current` | int | 当前页（默认 1） |
| `size` | int | 每页条数（默认 20） |

响应 `data`：

```json
{
  "totalCallCount": 52800,
  "avgDailyCalls": 1760,
  "usageRate": "83%",
  "ranking": [
    {
      "moduleName": "语音问诊",
      "callCount": 18500,
      "doctorCount": 42,
      "avgPerDoctor": 440,
      "growthRate": "12.5"
    }
  ],
  "total": 6,
  "records": [],
  "trend": {
    "modules": ["语音问诊", "AI推荐诊断", "知识库使用"],
    "days": ["2026-04-01", "2026-04-02"],
    "values": [[120, 135], [98, 102], [75, 80]]
  }
}
```

约束：

1. `ranking` 按 `callCount` 倒序排列，已计算增长率（与上一等长周期对比）
2. `moduleName`、`trend.modules` 和 `function-modules` 接口返回的名称均为产品功能维度，当前固定归并为：语音问诊、智能问诊、报告单解读、聊天、AI诊断鉴别、AI推荐诊断、AI推荐用药、AI推荐检查、AI推荐检验、AI推荐处置、AI推荐治疗方案、知识库使用
3. 调用次数来自 `c_ai_feature_event`，同一 `idDevice + idempotencyKey` 只入库一次，避免离线重传、接口重试和底层多条审计日志造成重复统计
4. `c_ai_op_log` 仅用于审计与排障，不再作为辅诊功能统计的事实源
5. 主流程内部自动 AI 推荐不重复拆分为 AI 推荐诊断/用药/检查/检验/处置/诊疗方案推荐；这些子功能只在医生显式触发对应独立辅助入口时计数，HIS Bridge 入口成功打开目标界面即计一次；同一条功能事件离线重试或接口重试不重复入库，同一就诊再次显式触发入口按新调用计数
6. `doctorCount` 按事件中的医生 ID 优先统计；医生 ID 为空时回退设备 ID
7. `trend` 仅包含排名前 5 的功能的逐日调用趋势
8. `records` 为当前页数据，支持分页
9. 接口继续兼容区域、平台机构（`idOrg`）和 HIS 机构（`hisOrgId`）筛选，并遵循 5.5 的双机构维度约束；当前管理端页面只展示区域与 HIS 机构筛选。

### 5.9.1 GET `/admin/api/analytics/function-usage/export`

用途：按当前辅诊功能统计筛选条件导出 Excel 文件，包含汇总指标、功能排行明细和趋势数据。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：同 5.9；导出忽略分页参数，导出当前筛选条件下的完整排行与趋势。

响应：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

响应头：

- `Content-Disposition: attachment; filename*=UTF-8''function-usage-*.xlsx`

### 5.10 GET `/admin/api/users`

用途：分页查询用户列表。

请求参数：

- `current`
- `size`
- `keyword`
- `sdStatus`
- `idOrg`
- `idRole`

### 5.11 POST `/admin/api/users`
用途：新增用户。

请求：

```json
{
  "cdUser": "zhangsan",
  "naUser": "张三",
  "password": "123456",
  "idOrg": "ORG001",
  "roleIds": ["ROLE_ADMIN"],
  "sdStatus": "1"
}
```

约束：激活用户的 `cdUser` 必须唯一；用户主表写入与角色映射写入在同一事务内完成，并由数据库唯一索引兜底并发重复。

### 5.12 PUT `/admin/api/users/{idUser}`
用途：修改用户资料、角色和状态；`password` 为空时保留原值。

说明：该接口用于完整保存用户资料，会校验登录账号唯一性、所属机构和角色有效性。列表中的启用/停用操作不复用该接口，避免状态切换被资料校验或角色映射校验误伤。

### 5.12.1 POST `/admin/api/users/{idUser}/enable`
用途：启用用户，仅把 `c_ai_user.sd_status` 更新为 `1`，不修改账号、密码、机构或角色映射。

### 5.12.2 POST `/admin/api/users/{idUser}/disable`
用途：停用用户，仅把 `c_ai_user.sd_status` 更新为 `0`，不修改账号、密码、机构或角色映射。

### 5.13 DELETE `/admin/api/users/{idUser}`
用途：逻辑作废用户，并同步作废其激活角色映射；该接口不作为日常启用/停用入口。

### 5.14 GET `/admin/api/roles`
用途：分页查询角色列表。

请求参数：

- `current`
- `size`
- `keyword`

### 5.15 POST `/admin/api/roles`
用途：新增角色。

请求：

```json
{
  "cdRole": "SYSTEM_ADMIN",
  "naRole": "系统管理员",
  "desRole": "拥有全部后台权限",
  "sdStatus": "1"
}
```

### 5.16 PUT `/admin/api/roles/{idRole}`
用途：修改角色信息。

### 5.17 DELETE `/admin/api/roles/{idRole}`
用途：逻辑停用角色。

### 5.18 GET `/admin/api/regions`
用途：分页查询区域列表。

请求参数：

- `current`
- `size`
- `keyword`

说明：默认返回 `fg_active='1'` 的区域，包括启用与停用状态；引用下拉可传 `sdStatus=1` 仅取启用区域。

### 5.19 POST `/admin/api/regions`
用途：新增区域。

请求：

```json
{
  "cdRegion": "REG001",
  "naRegion": "章贡区",
  "idParent": "REG_PARENT",
  "sdRegionType": "district",
  "sdStatus": "1",
  "sortOrder": 10,
  "desRegion": "示例区域"
}
```

### 5.20 PUT `/admin/api/regions/{idRegion}`
用途：修改区域信息。

### 5.21 DELETE `/admin/api/regions/{idRegion}`
用途：停用区域，等价于把 `sdStatus` 改为 `0`，不修改 `fgActive`。

### 5.21.1 POST `/admin/api/regions/{idRegion}/enable`
用途：启用区域，等价于把 `sdStatus` 改为 `1`，不修改 `fgActive`。

### 5.22 GET `/admin/api/orgs`
用途：分页查询机构列表。

请求参数：

- `current`
- `size`
- `keyword`
- `idRegion`（可选）
- `sdStatus`（可选）

说明：默认返回 `fg_active='1'` 的机构，包括启用与停用状态；引用下拉可传 `sdStatus=1` 仅取启用机构，传 `idRegion` 时只返回该区域下机构。

### 5.23 POST `/admin/api/orgs`
用途：新增机构。

请求：

```json
{
  "cdOrg": "ORG001",
  "naOrg": "章贡区人民医院",
  "idRegion": "REG001",
  "idParent": "ORG_PARENT",
  "sdOrgType": "community",
  "sdStatus": "1",
  "sortOrder": 10,
  "desOrg": "示例机构"
}
```

说明：`cdOrg` 为客户端注册使用的机构编码，必填，激活机构内必须唯一；重复时返回“机构编码已存在”。

### 5.24 PUT `/admin/api/orgs/{idOrg}`
用途：修改机构信息。

### 5.25 DELETE `/admin/api/orgs/{idOrg}`
用途：停用机构，等价于把 `sdStatus` 改为 `0`，不修改 `fgActive`。

### 5.25.1 POST `/admin/api/orgs/{idOrg}/enable`
用途：启用机构，等价于把 `sdStatus` 改为 `1`，不修改 `fgActive`。

### 5.26 GET `/admin/api/devices`
用途：分页查询令牌列表。接口路径保持 `/devices` 以兼容既有管理端调用，页面呈现为“令牌管理”。

请求参数：

- `current`
- `size`
- `keyword`

响应 `data`：

```json
{
  "current": 1,
  "size": 10,
  "total": 1,
  "records": [
    {
      "idDevice": "uuid",
      "cdDevice": "9C:4E:36:AA:BB:CC",
      "naDevice": "诊室 1 号机",
      "naUser": "张医生",
      "idOrg": "ORG001",
      "idRegion": "REGION001",
      "deviceTokenMasked": "abcd****wxyz",
      "sdStatus": "1",
      "clientVersion": "0.1.0",
      "osInfo": "Windows 10",
      "dtLastHeartbeat": "2026-04-20T12:00:00",
      "dtRegistered": "2026-04-20T10:00:00"
    }
  ]
}
```

字段说明：`naUser` 为该设备最近一次问诊日志中的非空医生姓名；设备尚未产生问诊记录时返回 `null`。该字段仅用于令牌列表展示，不表示设备与后台管理员账号建立了绑定关系。

### 5.27 POST `/admin/api/devices`
用途：手工创建令牌记录。服务端会生成 `deviceToken`，列表仅返回脱敏值。

请求：

```json
{
  "cdDevice": "9C:4E:36:AA:BB:CC",
  "naDevice": "诊室 1 号机",
  "idOrg": "ORG001",
  "clientVersion": "0.1.0",
  "osInfo": "Windows 10",
  "sdStatus": "1"
}
```

### 5.28 PUT `/admin/api/devices/{idDevice}`
用途：修改令牌对应终端名称、机构、状态和客户端信息。

### 5.29 POST `/admin/api/devices/{idDevice}/disable`
用途：停用令牌并作为同机构同 `cdDevice` 的封禁记录保留。停用后旧令牌不可继续访问，客户端也不能通过匿名重新注册绕过封禁。

### 5.29.1 DELETE `/admin/api/devices/{idDevice}`
用途：删除令牌，用于异常设备重置。删除会移除该令牌记录并释放同机构同 `cdDevice`，客户端可重新注册领取新令牌；不应用作日常封禁手段。

### 5.30 GET `/admin/api/configs`
用途：分页查询 AI 配置列表。

### 5.31 POST `/admin/api/configs`
用途：新增 AI 配置。

请求：

```json
{
  "cdConfig": "default-llm",
  "naConfig": "默认模型配置",
  "provider": "deepseek",
  "apiBaseUrl": "https://example.com/v1",
  "apiKey": "sk-xxx",
  "modelName": "deepseek-chat",
  "fastModelName": "deepseek-chat-lite",
  "enableThinking": true,
  "audioBaseUrl": "https://example.com/v1",
  "audioApiKey": "sk-audio",
  "audioModel": "whisper-1",
  "speechProvider": "openai-compatible",
  "speechRealtimeUrl": "wss://speech.example.com/realtime",
  "speechModel": "whisper-1",
  "knowledgeBaseEnabled": true,
  "knowledgeBaseBaseUrl": "https://pmphai.example.com",
  "pmphaiEnabled": true,
  "pmphaiBaseUrl": "https://pmphai.example.com",
  "pmphaiAppKey": "pmph-app-key",
  "pmphaiAppSecret": "pmph-app-secret",
  "reviewerEnabled": false,
  "reviewerBaseUrl": "https://reviewer.example.com/v1",
  "reviewerApiKey": "sk-reviewer",
  "reviewerModel": "gpt-4o-mini",
  "reviewerCheckExaminationEnabled": true,
  "featuresJson": "{\"regionalMode\":true,\"aiProxyEnabled\":true}",
  "idOrg": "ORG001",
  "idRegion": "REGION001",
  "sdStatus": "1"
}
```

语音配置最小要求：

0. `fastModelName` 为服务端 `chatFast` 独立模型；可留空，留空时回退 `modelName`。
1. `enableThinking` 用于控制服务端代理主模型 / `chatFast` / 审查模型时是否附带上游 `enable_thinking`；默认关闭。
2. `apiKey` 为主模型密钥，也是 `audioApiKey` 留空时的语音密钥回退值。
3. `audioApiKey` 为批量语音上游独立密钥；可留空，留空时复用 `apiKey`。FunASR 原生实时连接不发送该密钥；仅当批量转写与主模型供应商或账号不一致时填写。
4. `audioBaseUrl` 为服务端实际语音转写地址；可留空，留空时复用 `apiBaseUrl`。
5. `audioModel` 为服务端实际转写模型；可留空，`openai-compatible` 默认 `whisper-1`，`aliyun-dashscope` 默认 `qwen3-asr-flash`。
6. `speechProvider` / `speechModel` 用于 `/v1/client/bootstrap` 下发给桌面端；支持 `openai-compatible`、`aliyun-dashscope`、`funasr-websocket`。`aliyun-dashscope` 默认实时模型为 `qwen-audio-3.0-asr-flash-streaming`，通过 `/api-ws/v1/inference` `run-task` 协议调用；`qwen3-asr-flash-realtime` 因使用不同 session 协议会被明确拒绝。`funasr-websocket` 默认标识为 `funasr-2pass`，实际模型由 FunASR WebSocket 服务部署决定。
7. `speechRealtimeUrl` 为服务端连接的实时 WebSocket 上游地址，与批量 HTTP `audioBaseUrl` 分离。`funasr-websocket` 时必填，例如 `ws://funasr.internal:10095`；`aliyun-dashscope` 时可留空并回退官方地址。

语音上游路径：

1. `speechProvider=openai-compatible`：服务端将录音文件转为 multipart，调用 `{audioBaseUrl}/audio/transcriptions`。
2. `speechProvider=aliyun-dashscope`：服务端将录音转为 Data URL，调用 DashScope 兼容模式 `{audioBaseUrl}/chat/completions`；`audioBaseUrl` 建议配置为 `https://dashscope.aliyuncs.com/compatible-mode/v1`。
3. `speechProvider=funasr-websocket`：实时 PCM 走 `speechRealtimeUrl` 的 FunASR 原生协议；聊天录音和实时失败后的批量兜底仍按 OpenAI 兼容协议调用 `{audioBaseUrl}/audio/transcriptions`。

DashScope 推荐组合：实时模型使用 `qwen-audio-3.0-asr-flash-streaming`，实时地址使用中国内地 `wss://dashscope.aliyuncs.com/api-ws/v1/inference`；批量兜底继续使用 `qwen3-asr-flash` 和 `https://dashscope.aliyuncs.com/compatible-mode/v1`。实时模型不能通过兼容模式 `/chat/completions` 调用。

上游地址安全要求：

1. 服务端默认不要求填写 `FB_OUTBOUND_ALLOWED_HOSTS`；代码默认值与 profile 配置均允许空白名单直接放行 host。
2. 服务端默认允许未加密协议与私网地址，以适配医院内网 HTTP 模型服务；如需收紧，应显式设置 `FB_OUTBOUND_ALLOW_ALL_HOSTS=false`、`FB_OUTBOUND_ALLOW_PRIVATE_NETWORK=false`、`FB_OUTBOUND_ALLOW_INSECURE_HTTP=false` 并配置允许 host。
3. 同一 host 的出站请求会被本地限流与熔断保护；超过阈值或短时间连续失败时，配置测试、AI 代理、语音代理和 PMPHAI 代理都会被拒绝访问上游。
4. `application.yml`、`development`、`test`、`product` profile 当前均默认放开 host 白名单、HTTP / WS、私网地址与代理 fake-ip；host 被拒绝通常表示部署环境显式覆盖了对应开关。

### 5.31.1 POST `/admin/api/configs/test`
用途：使用管理端当前草稿配置测试主模型连通性与可用性，不保存配置。

### 5.31.2 POST `/admin/api/configs/test/speech/realtime`
用途：测试实时语音地址、密钥、协议和模型是否可用，不保存配置。

处理规则：

1. `aliyun-dashscope` 会实际建立 WebSocket、发送 `run-task`，收到 `task-started` 才判定模型可用。
2. `funasr-websocket` 由上游部署决定模型，测试实际 WebSocket 握手是否成功。
3. `openai-compatible` 不启用实时 WebSocket，调用本接口返回明确的不支持提示。
4. 请求体复用 `AiConfigSaveRequest`；新建配置使用当前填写密钥，编辑已保存配置时可通过 `idConfig` 复用原有加密密钥。

### 5.31.3 POST `/admin/api/configs/test/speech/batch`
用途：测试批量转写地址、密钥和模型是否可用，不保存配置。

服务端生成一段极短的静音 WAV 作为测试载荷；`aliyun-dashscope` 调用兼容模式 `/chat/completions`，其他提供方调用 OpenAI 兼容 `/audio/transcriptions`。测试不写入语音审计文件，不返回上游原始响应或密钥。

### 5.32 PUT `/admin/api/configs/{idConfig}`
用途：修改 AI 配置；`apiKey`、`audioApiKey`、`reviewerApiKey`、`pmphaiAppKey`、`pmphaiAppSecret` 为空时保留原值。`speechRealtimeUrl` 为空表示清空自定义实时地址；当提供方为 `aliyun-dashscope` 时回退官方地址，当提供方为 `funasr-websocket` 时校验不通过。

### 5.33 DELETE `/admin/api/configs/{idConfig}`
用途：逻辑停用配置。

### 5.34 GET `/admin/api/prompts`
用途：分页查询 Prompt 列表。返回包含 `source` 与 `builtIn`；`builtIn=true` 表示服务端内置默认 Prompt，只能创建覆盖版本，不能直接修改。

请求参数：

- `current` / `size`：分页
- `keyword`：匹配编码、名称、类型
- `sdStatus`：`0` 草稿、`1` 已发布、`2` 已归档
- `idOrg` / `idRegion`：按作用域筛选

响应字段补充：

- `source`：`built_in` / `configured`
- `builtIn`：是否内置默认
- `sdStatus`：配置记录状态；内置默认按已发布展示

### 5.35 POST `/admin/api/prompts`
用途：新增 Prompt。新增后默认草稿；发布后进入桌面端 `/v1/client/prompts/delta`，并成为业务调试节点的默认 Prompt 候选。

请求：

```json
{
  "cdPrompt": "medicalRecordGeneration",
  "naPrompt": "病历生成",
  "sysPrompt": "你是一名全科辅助诊疗助手",
  "userTemplate": "请根据{{chiefComplaint}}生成病历",
  "versionNum": "2026.04.20.1",
  "sdPromptType": "consultation",
  "sdStatus": "0",
  "idOrg": "ORG001",
  "idRegion": "REGION001"
}
```

### 5.36 PUT `/admin/api/prompts/{idPrompt}`
用途：修改 Prompt 内容与可见范围。`builtin:*` 内置记录不能直接修改，应通过“创建覆盖”生成配置记录。

### 5.37 POST `/admin/api/prompts/{idPrompt}/publish`
用途：发布 Prompt；同编码、同作用域下其他已发布版本自动转归归档态。作用域解析优先级为机构级 > 区域级 > 全局级 > 内置默认。

### 5.38 POST `/admin/api/prompts/{idPrompt}/archive`
用途：归档 Prompt。

### 5.39 DELETE `/admin/api/prompts/{idPrompt}`
用途：逻辑删除 Prompt。

### 5.39.1 GET `/admin/api/inpatient-emr/templates`
用途：分页查询住院病历 HTML 模板解析缓存。

请求参数：

- `current`
- `size`
- `keyword`：匹配模板主键、模板名称或模板 hash
- `sdStatus`：`1` 启用，`0` 停用

### 5.39.2 GET `/admin/api/inpatient-emr/templates/{idCache}`
用途：查看单个模板缓存及字段解析结果；响应包含原生 `htmlContent`，管理端基于该字段提供源码查看和 HTML 预览。`fields` 包含完整模板字段，字段规则中会附带 `rule.resolvedPrompt` 和 `rule.promptSource`，分别表示当前展示提示词和来源（`custom` 已维护、`default` 按字段规则生成、`not_ai` 非 AI 生成字段）。默认提示词会结合模板名称、记录类型、字段名称、所属段落、字段含义和住院数据依赖生成。

### 5.39.2.1 PUT `/admin/api/inpatient-emr/templates/{idCache}/fields/{fieldId}/generation`
用途：手动调整指定字段是否由 AI 辅助生成。

请求：

```json
{
  "aiSuitable": true
}
```

### 5.39.3 PUT `/admin/api/inpatient-emr/templates/{idCache}/fields/{fieldId}/prompt`
用途：维护指定 `data-id` 字段的 AI 辅助生成提示词。

请求：

```json
{
  "prompt": "请结合住院登记诊断、当日医嘱和体温单生命体征生成日常病程记录正文，避免编造未提供的检查结果。",
  "generatorInstruction": "请根据字段含义、模板上下文和住院数据依赖，生成严谨可审查的字段回填提示词。"
}
```

### 5.39.3.1 POST `/admin/api/inpatient-emr/templates/{idCache}/fields/{fieldId}/prompt/generate`
用途：根据字段信息和可编辑的生成指令生成 AI 字段提示词草稿；生成结果不自动保存，管理员确认后再调用提示词保存接口。生成草稿会带入当前模板名称、记录类型、字段名称、所属段落和字段含义，便于区分每日病程录、医生查床录等不同文书语境。

请求：

```json
{
  "generatorInstruction": "请根据字段含义、模板上下文和住院数据依赖，生成严谨可审查的字段回填提示词。"
}
```

响应 `data`：

```json
{
  "prompt": "请围绕病程记录正文生成住院病历草稿，只依据住院登记、诊断、医嘱和体温单资料，不编造未提供的症状、查体或检查结果。",
  "generatorInstruction": "请根据字段含义、模板上下文和住院数据依赖，生成严谨可审查的字段回填提示词。"
}
```

### 5.39.4 POST `/admin/api/inpatient-emr/templates/{idCache}/enable`
用途：启用模板缓存。

### 5.39.5 POST `/admin/api/inpatient-emr/templates/{idCache}/disable`
用途：停用模板缓存；停用后客户端相同 `templateId` 会按未命中处理并重新上传解析结果。

### 5.39.6 DELETE `/admin/api/inpatient-emr/templates/{idCache}`
用途：逻辑删除模板缓存。

### 5.40 GET `/admin/api/data-packages`
用途：分页查询数据包列表。

请求参数：

- `current`
- `size`
- `keyword`
- `sdPackageType`
- `sdStatus`
- `idRegion`
- `idOrg`

### 5.41 POST `/admin/api/data-packages`
用途：新增数据包。

请求：

```json
{
  "cdPackage": "mapping-default",
  "naPackage": "默认映射包",
  "sdPackageType": "mapping",
  "versionNum": "2026.04.20.1",
  "contentJson": "{\"diagnoses\":\"id,code,name\\n1,J00,感冒\"}",
  "sdStatus": "0",
  "idOrg": "ORG001",
  "idRegion": "REGION001"
}
```

### 5.42 PUT `/admin/api/data-packages/{idPackage}`
用途：修改数据包内容、版本和可见范围。

### 5.43 POST `/admin/api/data-packages/{idPackage}/publish`
用途：发布数据包。

约束：

- 同类型、同作用域的其他已发布版本需自动转归归档态

### 5.44 POST `/admin/api/data-packages/{idPackage}/archive`
用途：归档数据包。

### 5.45 DELETE `/admin/api/data-packages/{idPackage}`
用途：逻辑删除数据包。

### 5.46 GET `/admin/api/data-packages/template-default`
用途：获取服务端内置症状模板基线，供 legacy `template` 数据包编辑或症状模板初始化导入时参考。

响应 `data`：

```json
{
  "version": "builtin-1a2b3c4d",
  "western": [],
  "tcm": []
}
```

### 5.47 GET `/admin/api/symptom-templates`
用途：分页查询症状模板列表，返回完整模板结构，供后台 disease editor 直接编辑。

说明：

- 仅返回 `fg_active=1` 的逻辑有效症状模板；执行删除成功后的记录不应再出现在列表中。

请求参数：

- `current`
- `size`
- `keyword`
- `medicalMode`
- `systemCategory`
- `sdStatus`
- `idRegion`
- `idOrg`

响应 `data.records[*]` 结构：

```json
{
  "id": "uuid",
  "medicalMode": "western",
  "key": "fever",
  "name": "发热",
  "description": "",
  "isCommonSymptom": true,
  "systemCategory": [
    "nervous"
  ],
  "bodyParts": [],
  "customScript": "",
  "config": {
    "title": "智能问诊",
    "sections": []
  },
  "applicablePopulation": {
    "genders": [],
    "ageGroups": []
  },
  "tcmMetadata": null,
  "sortOrder": 10,
  "sdStatus": "1",
  "idRegion": null,
  "idOrg": null,
  "createdAt": 1770000000000,
  "updatedAt": 1770000000000
}
```

### 5.35 POST `/admin/api/symptom-templates`
用途：新增症状模板。

说明：

- 请求体使用与桌面端 disease editor 基本一致的模板结构
- `medicalMode` 仅支持 `western`、`tcm`
- 成功后写入症状模板修改日志，`operationType=create`

### 5.36 PUT `/admin/api/symptom-templates/{id}`
用途：修改症状模板。

说明：

- 成功后写入症状模板修改日志，`operationType=update`
- 日志记录修改前后完整模板快照，并在 `diff` 中输出字段级 before/after

### 5.37 DELETE `/admin/api/symptom-templates/{id}`
用途：逻辑删除症状模板。

说明：

- 成功后写入症状模板修改日志，`operationType=delete`
- 日志保留删除前模板快照，`afterSnapshot` 为空

### 5.38 POST `/admin/api/symptom-templates/import-builtin`
用途：将服务端内置 `template-seeds` 症状模板按指定作用域导入 `c_ai_symptom_template`。

请求：

```json
{
  "medicalMode": "western",
  "idRegion": null,
  "idOrg": null,
  "overwriteExisting": true
}
```

响应 `data`：

```json
{
  "medicalMode": "western",
  "createdCount": 265,
  "updatedCount": 0
}
```

说明：

- 每条实际新增 / 覆盖更新的模板都会写入症状模板修改日志，`operationType=import_builtin`

### 5.39 POST `/admin/api/symptom-templates/import-json`
用途：将桌面端已有症状模板 JSON 文件写入 `c_ai_symptom_template`，支持 `templates.json`、`tcm-templates.json` 以及后台导出的当前模式症状数组。

请求：

```json
{
  "medicalMode": "western",
  "idRegion": null,
  "idOrg": null,
  "overwriteExisting": true,
  "contentJson": "[{\"key\":\"fever\",\"name\":\"发热\",\"config\":{\"sections\":[]}}]"
}
```

说明：

- 当 `contentJson` 为 JSON 数组时，按 `medicalMode` 解释为当前模式的症状列表
- 当 `contentJson` 为对象且包含 `symptoms` 数组时，按 `medicalMode` 读取其中症状列表，兼容 `tcm-templates.json`
- 当 `contentJson` 为对象且包含 `western` 或 `tcm` 数组时，按 `medicalMode` 读取对应字段

响应 `data`：

```json
{
  "medicalMode": "western",
  "createdCount": 12,
  "updatedCount": 3
}
```

说明：

- 每条实际新增 / 覆盖更新的模板都会写入症状模板修改日志，`operationType=import_json`

### 5.40 GET `/admin/api/symptom-templates/change-logs`
用途：分页查询症状模板修改日志，用于审计追踪“谁在什么时候改了什么”。

请求参数：

- `current`
- `size`
- `idTemplate`
- `keyword`：匹配症状名称、症状 Key、操作者账号 / 姓名、变更摘要
- `medicalMode`
- `operationType`：`create` / `update` / `delete` / `import_builtin` / `import_json`
- `operatorKeyword`
- `dateFrom`
- `dateTo`

响应 `data.records[*]` 结构：

```json
{
  "idLog": "uuid",
  "idTemplate": "template-id",
  "symptomKey": "fever",
  "symptomName": "发热",
  "medicalMode": "western",
  "idRegion": null,
  "idOrg": null,
  "operationType": "update",
  "operatorId": "USER001",
  "operatorCode": "admin",
  "operatorName": "系统管理员",
  "changeSummary": "修改症状名称、状态",
  "beforeSnapshot": {},
  "afterSnapshot": {},
  "diff": {
    "name": {
      "before": "发热",
      "after": "发热待查"
    }
  },
  "operationTime": "2026-05-22T10:30:00",
  "createdAt": 1770000000000,
  "updatedAt": 1770000000000
}
```

### 5.41 GET `/admin/api/logs`
用途：分页查询操作日志。

请求参数：

- `current`
- `size`
- `keyword`
- `logType`
- `module`
- `action`
- `title`
- `sourceModule`
- `scene`
- `traceId`
- `consultationId`
- `result`
- `dateFrom`
- `dateTo`

响应 `data`：

```json
{
  "current": 1,
  "size": 10,
  "total": 1,
  "records": [
    {
      "idLog": "uuid",
      "idDevice": "uuid",
      "idOrg": "ORG001",
      "sdLogType": "operation",
      "naModule": "consultation",
      "displayModule": "智能问诊",
      "opAction": "reference_feedback_diagnosis",
      "displayAction": "接收诊断引用回执",
      "opTitle": "接收 PHIS 引用回执",
      "displayTitle": "接收 PHIS 诊断引用回执",
      "sourceModule": "consultation_reference",
      "displaySourceModule": "问诊引用",
      "sceneCode": "consultation-reference",
      "displayScene": "问诊引用回写",
      "traceId": "TRACE-001",
      "consultationId": "CONSULT-001",
      "desOp": "接收 PHIS 引用回执",
      "opResult": "1",
      "payloadJson": "{\"module\":\"consultation\",\"action\":\"reference_feedback_diagnosis\",\"title\":\"接收 PHIS 引用回执\",\"sourceModule\":\"consultation_reference\",\"scene\":\"consultation-reference\",\"result\":\"success\",\"operationType\":\"api_call\",\"operationName\":\"reference_feedback:diagnosis\",\"details\":{\"consultationId\":\"CONSULT-001\",\"traceId\":\"TRACE-001\"}}",
      "audioFilePath": "/var/lib/floating-ball-server/speech-audit/20260422/chat-input-abc123.wav",
      "operationTime": "2026-04-20T16:00:00"
    }
  ]
}
```

补充约束：

1. `speech_proxy` 日志的 `payloadJson` 只保留录音元数据、请求摘要和上游回文，不再保存原始 base64 音频
2. 若存在录音文件落盘，返回记录中的 `audioFilePath` 会指向该文件
3. `desOp` 默认优先展示 `title`，缺失时回退 `action / operationName`
4. `traceId` 默认从顶层 `traceId` 提取；若顶层缺失则回退 `details.traceId`
5. `displayModule/displayAction/displayTitle/displaySourceModule/displayScene` 为服务端统一生成的中文展示字段；管理端应优先展示这些字段，同时保留 `naModule/opAction/opTitle/sourceModule/sceneCode` 原始码用于精准排障与复制检索

### 5.54 GET `/admin/api/user-logs/consultations`
用途：分页查询运维用户日志列表。该模块是专门给运维人员使用的问诊轮次聚合日志，不替代原有操作日志页面。

请求参数：

- `current`、`size`
- `keyword`：跨后台机构、HIS 机构 ID、医生、患者、问诊 ID 模糊搜索
- `consultationType`：`voice` / `smart`
- `dateFrom`、`dateTo`

响应 `data`：

```json
{
  "current": 1,
  "size": 10,
  "total": 1,
  "records": [
    {
      "idLog": "uuid",
      "consultationId": "CONSULT-001",
      "idOrg": "ORG001",
      "hisOrgId": "HIS_ORG_001",
      "naOrg": "区域中心医院",
      "naDoctor": "张医生",
      "consultationTime": "2026-04-27T10:00:00",
      "patientName": "王某",
      "patientGender": "男",
      "patientAge": "45岁",
      "consultationType": "voice",
      "hasAudio": true,
      "hasSpeechText": true,
      "status": "completed"
    }
  ]
}
```

### 5.55 GET `/admin/api/user-logs/consultations/{idLog}`
用途：查看一次问诊的运维用户日志详情。

响应 `data`：

```json
{
  "idLog": "uuid",
  "consultationId": "CONSULT-001",
  "idOrg": "ORG001",
  "hisOrgId": "HIS_ORG_001",
  "naOrg": "区域中心医院",
  "idDoctor": "D001",
  "naDoctor": "张医生",
  "patientName": "王某",
  "consultationType": "voice",
  "speechText": "医生您好，我发热一天，最高39度，伴咳嗽、咽痛。",
  "audioFileName": "voice-consultation-1770000000000.wav",
  "audioMimeType": "audio/wav",
  "audioSize": 128000,
  "hasAudio": true,
  "hasSpeechText": true,
  "firstSnapshotJson": "{\"chiefComplaint\":\"咳嗽3天\",\"pastMedicalHistory\":\"2型糖尿病\",\"physicalExam\":\"双肺呼吸音清\"}",
  "finalSnapshotJson": "{\"chiefComplaint\":\"咳嗽、咳痰3天\",\"pastMedicalHistory\":\"2型糖尿病\",\"physicalExam\":\"双肺呼吸音清\"}",
  "selectionJson": "{\"selectedMedicineNames\":[\"氨溴索\"],\"selectedProcedureNames\":[\"雾化吸入\"]}",
  "status": "completed",
  "consultationTime": "2026-04-27T10:00:00"
}
```

### 5.53.1 GET `/admin/api/user-logs/consultations/{idLog}/audio`
用途：后台用户日志详情播放语音问诊原始录音。

响应：

- `Content-Type`：优先使用日志中的 `audioMimeType`，缺失时为 `application/octet-stream`
- `Content-Disposition: inline`
- Body：录音文件二进制内容

约束：

1. 仅管理端鉴权后可访问。
2. 接口只根据 `idLog` 查表后读取服务端保存的音频文件，不接受任意文件路径参数。

### 5.56 GET `/admin/api/user-logs/consultations/{idLog}/timeline`
用途：查看一次问诊关联的操作时间线，按发生时间正序返回，便于快速识别关键步骤。

响应 `data`：

```json
[
  {
    "eventType": "operation",
    "module": "consultation",
    "displayModule": "智能问诊",
    "action": "完成智能问诊",
    "displayAction": "完成智能问诊",
    "result": "1",
    "operationTime": "2026-04-27T10:05:00",
    "details": {}
  }
]
```

补充约束：

1. `module/action` 保留原始入库值；`displayModule/displayAction` 为服务端统一生成的中文展示字段。
2. 管理端时间线应优先展示 `displayModule/displayAction`，同时保留原始字段用于精确定位与排障。

### 5.57 GET `/admin/api/business-workflow-debug/consultations`
用途：业务调试台的就诊记录列表。该接口按真实业务记录选择调试上下文，不依赖 AI trace 是否已经采集。

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| current | number | 否 | 页码，默认 `1` |
| size | number | 否 | 每页条数，默认 `10` |
| keyword | string | 否 | 匹配患者、医生、机构、问诊 ID |
| status | string | 否 | `generated` / `completed` / `abandoned` |

响应 `data.records[]`：

```json
{
  "idRun": "user-log-id",
  "consultationId": "CONSULT-001",
  "scene": "voice_consultation",
  "sceneName": "语音接诊",
  "patientName": "王某",
  "patientGender": "男",
  "patientAge": "56岁",
  "doctorName": "张医生",
  "orgName": "区域中心医院",
  "status": "completed",
  "startedAt": "2026-06-23T09:30:00",
  "hasSpeechText": true
}
```

### 5.58 GET `/admin/api/business-workflow-debug/consultations/{idRun}/context`
用途：加载一次语音接诊的业务上下文和可调试节点目录。节点 Prompt 按原设备所属机构/区域解析，优先级为机构级 > 区域级 > 全局级 > 内置默认。

响应 `data`：

```json
{
  "run": {},
  "context": {
    "speechText": "原始 ASR 文本",
    "patientContext": {},
    "firstSnapshot": {},
    "finalSnapshot": {}
  },
  "nodes": [
    {
      "nodeCode": "voice_transcript_calibration",
      "title": "语音文本校准",
      "promptCode": "voiceTranscriptCalibration",
      "promptSource": "built_in",
      "defaultConfigProfile": "fast",
      "defaultTemperature": 0.1,
      "systemPrompt": "...",
      "userPrompt": "...",
      "inputPresets": ["原始语音文本", "患者/医生/机构上下文"]
    }
  ]
}
```

首版语音接诊节点目录：`voice_transcript_calibration`、`medical_record_generation`、`diagnosis_recommendation`、`treatment_recommendation`、`examination_recommendation`、`lab_test_recommendation`、`procedure_recommendation`、`voice_safety_review`。

### 5.59 POST `/admin/api/business-workflow-debug/execute`
用途：重放业务节点。请求中的 `systemPrompt`、`userPrompt`、`inputPayload` 均为本次调试临时值，不会修改已发布 Prompt，也不会改写业务病历结果。

请求：

```json
{
  "idRun": "user-log-id",
  "nodeCode": "medical_record_generation",
  "systemPrompt": "你是一名专业的基层门诊病历生成助手...",
  "userPrompt": "请根据以下上下文生成门诊病历草稿...",
  "configProfile": "default",
  "temperature": 0.2,
  "inputPayload": {
    "input": "当前节点核心输入",
    "upstreamOutput": "上游节点输出"
  }
}
```

响应 `data`：

```json
{
  "nodeCode": "medical_record_generation",
  "traceId": "business-debug-uuid",
  "content": "{\"chiefComplaint\":\"...\"}",
  "parsedJson": {},
  "durationMs": 1200
}
```

约束：

1. `configProfile` 只允许 `default` / `fast` / `reviewer`。
2. 服务端复用原就诊记录设备所属机构/区域的 AI 配置，密钥不返回管理端。
3. 该接口只面向管理端调试；结果保存在页面内供下游节点引用，服务端仅写入普通 AI 审计日志用于排障。

### 5.51 GET `/admin/api/feedbacks`

用途：分页查询用户反馈列表。摘要字段面向非技术运营人员，技术列在管理端"高级筛选"中按需展开。默认只返回每个反馈槽位的最新版本；如需查看历史修订，可显式传 `includeHistory=true`。

请求参数：

- `current`、`size`
- `includeHistory`：是否包含历史修订，默认 `false`
- `keyword`：跨 `comment / sourceModule / traceId / sessionId / na_org / na_doctor / na_dept` 模糊搜索
- `scores`：评分多选过滤，逗号分隔，取值范围 `1-5`，例如 `1,3,5`；兼容旧版单值参数 `score`
- `sourceModule`、`kind`（`general | recommendation | record_field | session`）、`severity`（`low | medium | high`）
- `doctor`：医生模糊（先按 `id_doctor` 精确，再 `na_doctor` 模糊）
- `dept`：科室模糊（同上）
- `org`：机构模糊（按 `id_org` 精确 + `na_org` 模糊）
- `hasCorrection`、`hasTrace`：布尔过滤
- `dateFrom`、`dateTo`

响应 `data.records[*]` 重点字段：

```json
{
  "feedbackId": "uuid",
  "score": 2,
  "kind": "record_field",
  "severity": "medium",
  "comment": "主诉漏掉发热三天",
  "sourceModule": "voice_record_field",
  "displaySourceModule": "语音病例字段",
  "tags": ["data_accuracy"],
  "hasCorrection": true,
  "hasTrace": true,
  "hasScreenshot": false,
  "idDoctor": "10001",
  "naDoctor": "张医生",
  "idDept": "DEPT001",
  "naDept": "全科诊疗",
  "idOrg": "ORG-DEMO",
  "naOrg": "示例社区卫生服务中心",
  "traceId": "trace-123",
  "sessionId": "session-123",
  "idDevice": "device-uuid",
  "revisionNo": 2,
  "latest": true,
  "createdAt": "2026-04-22T10:11:12"
}
```

补充约束：

1. `sourceModule` 仍保留原始编码；`displaySourceModule` 为服务端统一生成的中文展示字段，管理端列表与详情应优先展示该字段。
2. `sourceModule` 查询兼容中文展示名和原始编码，两者都可命中同一批反馈记录。
3. `tags` 仍保留桌面端提交的原始标签编码；管理端列表与详情按桌面端标签目录展示中文文案，未知历史标签回退展示原始值。

### 5.57 GET `/admin/api/feedbacks/{feedbackId}`
用途：查看反馈详情。管理端将其拆分为「摘要」与「技术详情」两个 Tab，前者展示评分/说明/医生身份/标签/截图，后者展示 traceId/chainContext/sessionId 等技术字段，便于工程师排查。

响应 `data`：

```json
{
  "feedback": {
    "feedbackId": "uuid",
    "score": 2,
    "kind": "record_field",
    "severity": "medium",
    "comment": "主诉漏掉发热三天",
    "sourceModule": "voice_record_field",
    "displaySourceModule": "语音病例字段",
    "tags": ["data_accuracy"],
    "hasCorrection": true,
    "hasTrace": true,
    "hasScreenshot": false,
    "idDoctor": "10001",
    "naDoctor": "张医生",
    "idDept": "DEPT001",
    "naDept": "全科诊疗",
    "idOrg": "ORG-DEMO",
    "naOrg": "示例社区卫生服务中心",
    "traceId": "trace-123",
    "sessionId": "session-123",
    "revisionNo": 2,
    "latest": true,
    "rootFeedbackId": "uuid-root",
    "previousFeedbackId": "uuid-prev",
    "screenshotDataUrl": null,
    "chainContext": {
      "kind": "record_field",
      "aiTrace": { "traceId": "trace-123", "model": "deepseek-chat" },
      "recordField": { "fieldKey": "chiefComplaint", "originalValue": "...", "currentValue": "...", "correctedValue": "..." }
    }
  },
  "timeline": [
    { "type": "ai_proxy", "time": "2026-04-22T10:11:10", "title": "AI 对话请求", "displaySourceModule": "AI 对话代理", "result": "success", "payload": {} },
    { "type": "feedback", "time": "2026-04-22T10:11:12", "title": "用户提交反馈", "displaySourceModule": "语音病例字段", "result": "success", "payload": {} }
  ]
}
```

补充约束：

1. `timeline[*].title` 已按服务端统一展示目录优先中文化；管理端无需再自行猜测 `payload.action/operationName`。
2. `timeline[*].displaySourceModule` 仅用于快速识别来源模块，排障仍应结合 `payload` 原始字段。

### 5.57.1 GET `/admin/api/recommendation-preferences/summary`

用途：返回推荐偏好观测页的汇总指标，用于确认机构内偏好采集是否产生数据。该接口只读取推荐偏好聚合表和原始事件表，不修改偏好分、不触发重排。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| recommendationType | string | 否 | 推荐类型：`diagnosis` / `medicine` / `exam` / `lab_test` / `procedure` |
| scope | string | 否 | 聚合范围：`doctor` / `dept` / `org` |
| idRegion | string | 否 | 区域 ID |
| idOrg | string | 否 | 机构 ID |
| idDept | string | 否 | 科室 ID |
| idDoctor | string | 否 | 医生 ID |
| keyword | string | 否 | 匹配标准项名称、编码、ID、itemKey、医生、科室 |
| dateFrom | string | 否 | 事件开始时间，支持 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss` |
| dateTo | string | 否 | 事件结束时间，支持 `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss` |

响应 `data`：

```json
{
  "aggregateCount": 24,
  "eventCount": 128,
  "doctorScopeCount": 8,
  "deptScopeCount": 7,
  "orgScopeCount": 9,
  "averagePreferenceScore": 0.42
}
```

### 5.57.2 GET `/admin/api/recommendation-preferences/aggregates`

用途：分页查看机构/科室/医生维度的标准候选项偏好聚合结果。管理端只展示偏好分与计数，不提供编辑入口。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：同 `summary`，另支持：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| current | long | 否 | 页码，默认 1 |
| size | long | 否 | 每页条数，默认 10 |

响应 `data.records[]` 主要字段：

```json
{
  "idAgg": "uuid",
  "scope": "doctor",
  "idRegion": "REGION001",
  "idOrg": "ORG001",
  "idDept": "DEPT001",
  "idDoctor": "DOC001",
  "recommendationType": "diagnosis",
  "itemKey": "diagnosis:D001",
  "itemId": "D001",
  "itemCode": "J06.900",
  "itemName": "急性上呼吸道感染",
  "selectedCount": 6,
  "confirmCount": 2,
  "manualMatchCount": 1,
  "sampleCount": 9,
  "preferenceScore": 0.8,
  "lastEventTime": "2026-06-24T10:30:00",
  "insertTime": "2026-06-20T09:00:00",
  "updateTime": "2026-06-24T10:30:00"
}
```

排序规则：默认按 `lastEventTime DESC, preferenceScore DESC` 返回，优先展示近期有医生确认行为的数据。

### 5.57.3 GET `/admin/api/recommendation-preferences/events`

用途：分页查看推荐偏好原始事件，便于排查桌面端上报、幂等去重和医生最终选择来源。该接口不返回患者信息，不根据 AI 原文做匹配。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：同 `aggregates`，其中 `scope` 会按事件中是否存在 `idDoctor/idDept` 推导过滤。

响应 `data.records[]` 主要字段：

```json
{
  "idEvent": "uuid",
  "idDevice": "DEVICE001",
  "idRegion": "REGION001",
  "idOrg": "ORG001",
  "recommendationType": "diagnosis",
  "actionCode": "final_select",
  "itemKey": "diagnosis:D001",
  "itemId": "D001",
  "itemCode": "J06.900",
  "itemName": "急性上呼吸道感染",
  "selected": true,
  "primary": true,
  "traceId": "TRACE-001",
  "consultationId": "CONSULT-001",
  "sessionId": "SESSION-001",
  "sourceModule": "voice_consultation",
  "sceneCode": "voice-consultation",
  "idDoctor": "DOC001",
  "naDoctor": "张医生",
  "idDept": "DEPT001",
  "naDept": "全科",
  "promptVersion": "prompt-20260616",
  "templateVersion": "template-20260616",
  "modelVersion": "qwen-plus",
  "eventTime": "2026-06-24T10:30:00",
  "insertTime": "2026-06-24T10:30:02"
}
```

## 5. 管理端接口范围

首期管理端 API 只要求覆盖以下资源：

1. 区域管理
2. 机构管理
3. 令牌管理
4. AI 配置
5. Prompt 配置
6. 症状模板管理
7. 客户端版本发布
8. 操作日志查询
9. 管理员登录
10. 用户管理
11. 角色管理
12. 概览统计
13. 反馈、用户日志、统计分析与活跃度查询
14. 推荐偏好观测：聚合偏好分、原始事件、医生/科室/机构维度筛选

这些接口在第一轮脚手架阶段优先保证 CRUD 结构和分页查询能力，细节以实现文档和代码为准。Prompt 已恢复为管理端维护资源；数据包仍服务桌面端 delta 链路，但不再作为管理端维护资源暴露。

### 5.58 GET `/admin/api/user-activity/summary`

用途：返回指定时间范围与筛选条件下的用户活跃度汇总指标；当前管理端页面展示区域与 HIS 机构筛选，接口仍兼容后台平台机构 `idOrg`。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| dateFrom | string | 否 | 起始日期，yyyy-MM-dd |
| dateTo | string | 否 | 截止日期，yyyy-MM-dd |
| idRegion | string | 否 | 区域 ID，为空时统计全部 |
| idOrg | string | 否 | 后台平台机构 ID，为空时统计全部 |
| hisOrgId | string | 否 | HIS 机构 ID，为空时统计全部 |
| timeRange | string | 否 | 时间范围：today / week / month / quarter / year / custom |

说明：接口参数中的区域、平台机构（`idOrg`）和 HIS 机构（`hisOrgId`）筛选遵循 5.5 的双机构维度约束；当前管理端页面不展示平台机构筛选。传入 `hisOrgId` 时，总设备数只统计历史上曾在该 HIS 机构产生问诊记录的设备；所选时段存在该机构问诊的计为活跃，否则计为不活跃。

响应 `data`：

```json
{
  "activeUsers": 128,
  "inactiveUsers": 16,
  "activityRate": "88.9",
  "effectiveConsultationRate": "73.5",
  "activeUsersGrowth": "8",
  "inactiveUsersGrowth": "1",
  "activityRateGrowth": "2.5",
  "effectiveConsultationRateGrowth": "5.2"
}
```

字段说明：

- `activeUsers`：所选时段内有问诊记录的设备数
- `inactiveUsers`：所选时段内无问诊记录的设备数
- `activityRate`：活跃率百分比，活跃用户数 / 总设备数 × 100
- `effectiveConsultationRate`：有效问诊率百分比，有效问诊数 / 总问诊数 × 100，其中 `status='completed'`（一键回写）计为有效问诊
- `activeUsersGrowth` / `inactiveUsersGrowth`：较上期设备数差值，用于前端展示“增长/减少 N 人”
- `activityRateGrowth` / `effectiveConsultationRateGrowth`：较上期百分点差值

### 5.59 GET `/admin/api/user-activity/region-tree`

用途：返回区域层级树，每个节点包含该区域下的活跃用户数。该接口保留用于兼容旧版区域树视图，当前管理端用户活跃度页面默认使用顶部区域/HIS 机构查询条件。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：同 `summary` 接口。

响应 `data`：

```json
[
  {
    "id": "REGION001",
    "name": "北京市",
    "type": "province",
    "userCount": 16,
    "children": [
      {
        "id": "REGION002",
        "name": "北京市",
        "type": "city",
        "userCount": 16,
        "children": [
          {
            "id": "REGION003",
            "name": "东城区",
            "type": "district",
            "userCount": 5,
            "children": []
          }
        ]
      }
    ]
  }
]
```

### 5.60 GET `/admin/api/user-activity/users`

用途：返回用户活跃度明细列表，接口支持按区域、后台平台机构、HIS 机构、活跃状态筛选和分页；当前管理端页面仅展示区域、HIS 机构与活跃状态筛选。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| dateFrom | string | 否 | 起始日期 |
| dateTo | string | 否 | 截止日期 |
| idRegion | string | 否 | 区域 ID |
| idOrg | string | 否 | 后台平台机构 ID |
| hisOrgId | string | 否 | HIS 机构 ID |
| timeRange | string | 否 | 时间范围 |
| activeStatus | string | 否 | 活跃状态筛选：active / inactive |
| current | long | 否 | 页码，默认 1 |
| size | long | 否 | 每页条数，默认 10 |

说明：接口参数中的区域、平台机构（`idOrg`）和 HIS 机构（`hisOrgId`）筛选遵循 5.5 的双机构维度约束；当前管理端页面不展示平台机构筛选。响应中的 `idOrg` / `naOrg` 为兼容字段，当前管理端用户活跃度列表不展示平台机构列。列表默认按所选时段内的问诊次数降序返回，问诊次数相同时依次按有效问诊数、最后活跃时间降序排列，最后按设备 ID 升序保证分页顺序稳定。

响应 `data`：

```json
{
  "current": 1,
  "size": 10,
  "total": 144,
  "records": [
    {
      "idDevice": "uuid",
      "cdDevice": "9C:4E:36:AA:BB:CC",
      "naDevice": "PCIE-win32",
      "idOrg": "ORG001",
      "naOrg": "区域中心医院",
      "hisOrgId": "HIS-ORG-001",
      "hisOrgName": "市第一医院",
      "idRegion": "REGION003",
      "naRegion": "东城区",
      "activeStatus": "active",
      "consultationCount": 12,
      "effectiveConsultationCount": 8,
      "lastActiveTime": "2026-05-15T10:30:00"
    }
  ]
}
```

### 5.60.1 GET `/admin/api/user-activity/export`

用途：按当前用户活跃度筛选条件导出 Excel 文件，包含活跃度汇总指标和用户活跃明细。明细沿用 5.60 的默认活跃度排序；其中“有效问诊数”按 `status='completed'`（一键回写）统计，汇总中的“有效问诊率”按有效问诊数 / 总问诊数计算。

鉴权：`Authorization: Bearer {adminToken}`

请求参数：同 5.60；导出忽略分页参数，默认最多导出 10000 条用户明细。

响应：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

响应头：

- `Content-Disposition: attachment; filename*=UTF-8''user-activity-*.xlsx`

### 5.61 POST `/v1/client/chronic-disease/follow-ups`

用途：保存医生确认后的高血压、2 型糖尿病或两病联合融合随访记录。

本接口业务正文严格对应对接系统原始 `TcdVisitForm.getFormData()` 输出，不再接受 `diseaseType`、`systolicPressure`、`symptomCodes` 等平台替代字段。单病种和联合随访都只调用一次、保存一条融合记录。

鉴权：

- `Authorization: Bearer {deviceToken}`
- ECDSA P-256 请求签名头，规则同 2.3；接口必须经过 `DeviceAuthFilter`
- `X-Request-Id`：必填且最长 64，作为当前平台机构内的业务幂等键。客户端重试同一次保存时必须复用原值。

病种来源和切换：

| 上游公卫标记 | `sdVisitKind` 页面状态 | 提交值 | 显示字段 |
| --- | --- | --- | --- |
| `rqflStatus=3` | `["1"]` | `"1"` | 公共字段 + 高血压字段 |
| `rqflStatus=6` | `["2"]` | `"2"` | 公共字段 + 糖尿病字段 |
| `rqflStatus=3,6` | `["1","2"]` | `"1,2"` | 公共字段 + 两病字段并集 |

页面上的病种标记只读。原实例中 `sdVisitKind` 是复选数组；提交前按原 `getFormData()` 转为逗号字符串。

请求体：

```json
{
  "idPhr": "P10001",
  "idRecord": "V20260723001",
  "id": "",
  "status": "3",
  "sdVisitKind": "1,2",
  "dtHyPlan": "2026-10-23",
  "dtDbsPlan": "2026-10-23",
  "sdDataWay": "2",
  "stature": "160",
  "avoirdupois": "62",
  "advAdp": "60",
  "bmi": "24.22",
  "waistline": "87",
  "advWaistline": "84",
  "pressureH": "134",
  "pressureL": "83",
  "heartRate": "72",
  "glu": "7.2",
  "fbgMeal": "",
  "isGlu": "1",
  "inputUser": "D1001",
  "idUser": "D1001",
  "sdHySymptom": "1",
  "sdDbsSymptom": "1",
  "desOther": "",
  "sdArteriopalmus": "2",
  "sdProAct": "1",
  "sdPsychicAdj": "1",
  "fgCardiovascular": "0",
  "lowEffects": "0",
  "otherDisease": "",
  "note": "",
  "sdWehtherSmoke": "0",
  "daySmoke": "",
  "advDaySmoke": "",
  "sdWhetherDrink": "0",
  "dayDrink": "",
  "advDayDrink": "",
  "sdMainDrinking": "",
  "sportWeek": "5",
  "advSportWeek": "5",
  "sportMinute": "30",
  "advSportMinute": "30",
  "sdSalt": "6",
  "sdAdvSalt": "5",
  "rice": "300",
  "targRice": "250",
  "fgDrugChange": "1",
  "sdDrugPro": "1",
  "sdSideEffects": "1",
  "desSideEffects": "",
  "drugList": [
    {
      "id": "",
      "idDrug": "MED-001",
      "idPherec": "",
      "naDrug": "苯磺酸氨氯地平片",
      "sdDrugFreq": "1",
      "perDose": "1",
      "doseUnit": "片",
      "insulin": "2"
    }
  ],
  "fgRef": "1",
  "sdRefStatus": "",
  "desRef": "",
  "refDep": "",
  "desNoRef": "",
  "desAdr": "1",
  "sdComplications": "",
  "desComplications": "",
  "desComor": "1",
  "sdComorbidity": "",
  "desComorbidity": "",
  "sdMajorCc": "0",
  "targetOrganDamage": "0",
  "desPresAdvice": "<p>继续监测血压、血糖并按计划随访。</p>"
}
```

字段分组：

| 分组 | 原实例字段 |
| --- | --- |
| 锚点/阶段 | `idPhr`、`idRecord`、`id`、`status`、`sdVisitKind`、`dtHyPlan`、`dtDbsPlan` |
| 信息测量 | `sdDataWay`、`stature`、`avoirdupois`、`advAdp`、`bmi`、`waistline`、`advWaistline`、`pressureH`、`pressureL`、`heartRate`、`glu`、`fbgMeal`、`isGlu` |
| 随访信息 | `inputUser`、`idUser` |
| 病情问询 | `sdHySymptom`、`sdDbsSymptom`、`desOther`、`sdArteriopalmus`、`sdProAct`、`sdPsychicAdj`、`fgCardiovascular`、`lowEffects`、`otherDisease`、`note` |
| 生活习惯 | `sdWehtherSmoke`、`daySmoke`、`advDaySmoke`、`sdWhetherDrink`、`dayDrink`、`advDayDrink`、`sdMainDrinking`、`sportWeek`、`advSportWeek`、`sportMinute`、`advSportMinute`、`sdSalt`、`sdAdvSalt`、`rice`、`targRice` |
| 用药情况 | `fgDrugChange`、`sdDrugPro`、`sdSideEffects`、`desSideEffects`、`drugList[]` |
| 转诊情况 | `fgRef`、`sdRefStatus`、`desRef`、`refDep`、`desNoRef` |
| 健康评估 | `desAdr`、`sdComplications`、`desComplications`、`desComor`、`sdComorbidity`、`desComorbidity`、`sdMajorCc`、`targetOrganDamage`、`desPresAdvice` |

数组序列化：`sdArteriopalmus`、`sdComorbidity`、`sdComplications`、`sdDbsSymptom`、`sdHySymptom`、`sdMajorCc`、`sdVisitKind`、`targetOrganDamage` 在页面状态中为 `String[]`，请求正文中必须是逗号字符串。`drugList` 保持对象数组，仅提交 `idDrug` 非空的行。

原实例可确认的固定值：

- `status`：`1=before`、`2=clinic`、`3=after`；桌面端融合随访固定提交 `3`
- `sdDataWay`：`1=健康小屋`、`2=移动端`、`3=健康设备`
- `isGlu`：`1=空腹`、`0=餐后`
- `sdProAct`、`sdPsychicAdj`：`1=良好`、`2=一般`、`3=差`
- `fgCardiovascular`：`1=有`、`0=无`
- `lowEffects`：`0=无`、`1=偶尔`、`2=频繁`
- `sdWehtherSmoke`：`0=不吸`、`1=吸`、`2=已戒烟`
- `fgDrugChange`：`1=是`、`0=否`
- `sdSideEffects`：`1=无`、`2=有`
- `desAdr`：`1=无并发症`、`2=并发症稳定`、`3=并发症不稳定`
- `desComor`：`1=无合并症`、`2=合并症稳定`、`3=合并症不稳定`

`chis.dictionary.hySymptoms`、`chis.dbsSymptoms`、`chis.tcd.dpp`、`chis.tcd.drink`、`chis.tcd.drinkType`、`chis.tcd.medicationAdjustment`、`chis.tcd.complications`、`chis.tcd.comorbidity`、`chis.tcd.majorCc`、`chis.tcd.targetOrganDamage`、`chis.tcd.refStatus`、`chis.tcd.referralReason` 的权威值由原慢病系统字典服务提供。提供的 HTML/JS 没有内嵌完整码表，平台不得把猜测码值写成正式契约；服务端按字符串透传并保存。

核心校验与联动：

1. `idPhr`、`idRecord`、`status`、`sdVisitKind` 必填；`sdVisitKind` 只能由 `1`、`2` 组成。
2. 身高 `0～300`、体重/目标体重 `0～1000`、腰围/目标腰围 `0～999`；BMI 由体重和身高计算并保留 2 位小数。
3. 收缩压 `0～300`、舒张压 `0～200`，且收缩压不得小于舒张压；心率 `0～200`。
4. 糖尿病随访按 `isGlu` 只要求 `glu` 或 `fbgMeal`，范围 `0～100`。
5. 高血压必须填写 `sdHySymptom`、`fgCardiovascular`、`sdSalt`、`sdAdvSalt`；糖尿病必须填写 `sdDbsSymptom`、`sdArteriopalmus`、`lowEffects`、`rice`、`targRice`。
6. 症状编码 `1` 表示无症状，不能与同病种其它症状并存；`sdMajorCc`、`targetOrganDamage` 的 `0` 不能与其它值并存。
7. `status=3` 时 `inputUser`、`idUser` 必填；`status=2/3` 时 `fgDrugChange`、`sdDrugPro`、`sdSideEffects` 必填。
8. `fgDrugChange=0` 时清空 `drugList`；`sdSideEffects=2` 时 `desSideEffects` 必填，最长 255。
9. `sdComplications` 或 `sdComorbidity` 包含 `0` 时，对应的“其他描述”可填写；取消 `0` 时清空描述。
10. `desPresAdvice` 最长 5007；`<p><br></p>` 按空值归一化。
11. `X-Request-Id` 在当前平台机构激活记录内幂等；相同键但 `idPhr + idRecord + sdVisitKind` 不一致时返回 `CHRONIC-FOLLOWUP-CONFLICT`。

响应 `data`：

```json
{
  "followUpId": "01K12...",
  "requestId": "8a43ebce-3426-4eec-b62a-6ab9f93fef68",
  "status": "saved",
  "savedAt": "2026-07-23T11:20:31",
  "idPhr": "P10001",
  "idRecord": "V20260723001",
  "sdVisitKind": "1,2"
}
```

这里的响应是区域平台适配层保存确认，不冒充原 `chis.tcdService/saveTcdForm` 的未知响应。提供的实例代码没有给出该上游服务的完整出参 schema。

### 5.62 POST `/v1/client/chronic-disease/artifact-snapshots`

用途：健康处方或年度评估进入系统打印前，保存一份可追溯的医生确认快照。客户端只有在本接口成功返回后才可调用打印。

鉴权：

- `Authorization: Bearer {deviceToken}`
- ECDSA P-256 请求签名头，规则同 2.3；接口必须经过 `DeviceAuthFilter`

请求体：

```json
{
  "requestId": "a95f4c0e-a904-4ea3-a9dd-61c48e3a965d",
  "artifactType": "health_prescription",
  "hisOrgId": "HIS-ORG-001",
  "hisOrgName": "新城社区卫生服务中心",
  "patientId": "P10001",
  "visitId": "V20260723001",
  "patientName": "林女士",
  "diseaseTypes": ["hypertension", "type2_diabetes"],
  "dataAsOf": "2026-07-24T10:30:00+08:00",
  "assessmentYear": null,
  "templateVersions": ["HTN-FOLLOWUP-2026.1", "T2DM-FOLLOWUP-2026.1"],
  "pathVersions": ["HTN-PATH-2024.1", "T2DM-PATH-2022.1"],
  "evidenceVersions": ["中国高血压防治指南-2024", "国家基层糖尿病防治管理指南-2022"],
  "ruleVersion": "CHRONIC-RULE-2026.1",
  "summaryText": "已基于当前患者证据形成医生确认的健康处方。",
  "systolicPressure": 134,
  "diastolicPressure": 83,
  "bloodGlucose": 7.1,
  "bloodPressureRecordCount": 6,
  "bloodGlucoseRecordCount": 4,
  "acceptedItems": [
    {
      "itemId": "ai-test-0",
      "category": "test",
      "title": "复核糖化血红蛋白",
      "detail": "结合近期血糖记录决定是否补充检查。",
      "reason": "当前糖尿病管理证据需要年度复核。"
    }
  ],
  "doctorNotes": "患者同意先完成复查后复诊。",
  "doctorId": "D1001",
  "doctorName": "李医生"
}
```

固定枚举：

- `artifactType`：`health_prescription` / `annual_assessment`
- `diseaseTypes`：每项只能是 `hypertension` / `type2_diabetes`，至少一项且去重
- `acceptedItems[].category`：`test` / `medicine-review` / `lifestyle`

校验：

1. `requestId`、患者锚点、病种、数据截至时间、规则版本、摘要和医生姓名必填。
2. 模板、路径和依据版本必须覆盖请求中的全部病种，且只能使用当前已发布版本。
3. `health_prescription` 至少包含一条医生已确认建议；`annual_assessment` 必须提供 `assessmentYear`，并允许确认项为空。
4. 年度指标必须是非负记录数；血压和血糖存在时必须在随访接口相同的合理范围内。
5. `requestId` 在当前设备机构作用域内幂等；重复请求返回第一次保存的快照，不新增第二条。相同 `requestId` 若患者、就诊或快照类型不一致，返回 `CHRONIC-ARTIFACT-CONFLICT`。

响应 `data`：

```json
{
  "snapshotId": "01K12...",
  "requestId": "a95f4c0e-a904-4ea3-a9dd-61c48e3a965d",
  "status": "saved",
  "savedAt": "2026-07-24T10:30:03",
  "artifactType": "health_prescription"
}
```

错误语义：

- `400`：字段、病种规则或值域校验失败
- `401`：device token 或 ECDSA 签名无效
- `409`：相同 `requestId` 已存在但患者/病种关键字段冲突
- `500`：服务端保存失败；客户端保留表单现场并允许使用同一 `requestId` 重试
