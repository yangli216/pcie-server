# 萧山测试环境发布：门诊病历质控规则整合

日期：2026-11-26（Asia/Shanghai）。目标为 `192.168.204.122:9090`、`/data/floating-ball-server-testing`、profile `xiaoshan-test`。本次使用 `scripts/publish-xiaoshan.sh testing` 执行全新Maven clean package及测试、JAR内管理端资源校验、隔离发布与readiness验收。

## 发布源与范围

- 主诉规则：`584753933598d9c860e994510e08ca60a47bee1b`。
- 过敏史规则：`7951a19b2e051ee48dc13eeaf1993b7c4884a715`。
- 辅助检查规则：`f65e866d1d0389adcebf9e568767fff995e6b051`。
- 整合分支：`codex/xiaoshan-qc-testing-20261126`，统一规则版本 `emr-qc-rules-v10`。合并时同时保留过敏史互斥校验及辅助检查5分/去重，覆盖单份与批量组合情形。
- 修复人工调整入口硬编码v6：session返回当前ruleVersion，前端按服务端版本比较，保存端仍复核实际版本/正文/权限。
- 需求88的代配药免检与统计仍待范围确认，未实施或混入本次发布。
- 无数据库DDL、配置、权限、PHIS白名单或写回变更；现场私有service.env保持原内容、root所有和600权限。

## 发布前验证

- 管理端 `test:emr-qc`：23项通过，0失败。
- `python3 scripts/test_publish_xiaoshan.py`：13项通过，覆盖环境隔离、配置保护、失败与回滚。
- 合并冲突标记已清理，差异空白检查通过。
- 发布脚本以 `SKIP_TESTS=0` 执行 Maven `clean package`：813项测试，812通过、0失败、0错误、1项既有跳过，BUILD SUCCESS；构建同时执行管理端依赖安装与业务构建。
- JAR内管理端入口与4个引用资源校验通过；未复用旧构建产物。
- 发布前测试与正式readiness均为UP。已记录两环境的JAR、启动脚本、service.env摘要及PID；正式8080与旧路径文件摘要用于发布后复核，仅记录摘要/权限，不读取或输出私有配置内容。

## 实际发布结果

- `scripts/publish-xiaoshan.sh testing` 退出码0，远端提示 Deployment succeeded，测试进程PID `246745`，profile `xiaoshan-test`，端口 `9090`。
- 构建JAR：`pcie-server-0.2.0-SNAPSHOT.jar`；本地及远端SHA-256一致：`536aac06f5a253205a7785df783ff42af514595be1f1425b3566015c2e2834f0`。
- 外部 readiness 返回HTTP 200 / UP；`/admin/`及4个入口引用资源均HTTP 200，响应内容逐字节匹配本次JAR。
- 未登录访问 `/admin/api/emr-qc/session` 与 `/admin/api/emr-qc/records` 均HTTP 401。
- 测试 `service.env` 发布前后SHA-256一致，所有者UID 0、权限600均保持；测试启动脚本内容摘要不变。
- 正式8080仍UP，PID `211445`不变，JAR、启动脚本及私有配置摘要和配置权限均与发布前一致；旧生产路径文件摘要与PID状态也未变化。
- 回滚备份：`/data/floating-ball-server-testing/floating-ball-server.jar.backup-20261126102011-82769`、`/data/floating-ball-server-testing/start.sh.backup-20261126102011-82769`。
- 旧日志归档：`/data/floating-ball-server-testing/logs/archive/20261126102011-82769`。

技术发布验证已完成。未调用真实AI进行病例评估、未写回PHIS；业务效果及医学判断仍由用户人工验收，不以本次部署或自动化测试替代。
