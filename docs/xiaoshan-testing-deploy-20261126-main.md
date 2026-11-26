# 主分支合并与萧山测试重新发布

2026-11-26（Asia/Shanghai），用户明确授权将此前质控修改全部合并main并重新部署萧山测试环境。目标192.168.204.122:9090，目录/data/floating-ball-server-testing，profile=xiaoshan-test。

## 本次合并范围

- 任务113主诉初版5847539及第1轮返工07b33c4：未带规则码的明确加号问题只由确定性发现扣1分。
- 任务114过敏史7951a19：空/缺类互斥计分。
- 任务115辅助检查f65e866：有项目无实际结果每份最多扣5分。
- 整合d7e94f9及任务116代配药eec72ed：当前主诉肯定代配药先于全部规则及AI配置/模型调用免检，九维100分、零问题、独立来源/标记。
- 带入116返工中尚未提交的截图确切主诉“家长代配药”单份/批量与预览/结构化回退复现测试。未修改主目录中既有ASR、配置、SQL及IDE等未提交内容。
- 统一组合版本emr-qc-rules-v12，防止旧v11及其他独立分支结果直接复用；各原始提交保留在合并历史。九维100分/主诉5分和所有原授权边界不变，无新增数据库结构、权限、PHIS上游或统计功能。

## 执行和验收边界

先运行完整Maven测试与管理端质控测试，合并核对后推送main。发布使用scripts/publish-xiaoshan.sh testing，SKIP_TESTS=0执行全新Maven clean package及JAR内管理端入口/引用资源检查，保留测试service.env。记录两环境PID、JAR/脚本/私有配置摘要与配置权限，发布后核对测试readiness、管理端入口及静态资源逐字节一致，并核对正式环境基线。凭据只进入部署进程环境，不写入仓库或日志。

本机Fake/合成测试不能代替真实PHIS病例或医学验收。任务116截图中的旧结果原因仍需运行版本/实际输入核验，不能单凭重新部署宣布缺陷或医学验收通过。当前文件为合并和发布准备记录，实际结果在完成后补充，未执行步骤不宣称成功。


## 合并前验证结果

- 完整`mvn -f server/pom.xml test`：BUILD SUCCESS，912项、0失败、0错误、1项既有MySQL环境测试跳过（911通过），包含npm ci/Vite。
- 管理端`test:emr-qc`：28项全通过。发布脚本回归`python3 scripts/test_publish_xiaoshan.py`：13项全通过；脚本未修改。
- 差异和冲突标记检查通过，113返工与116免检用例均包含在本组合验证内；原工作区其他未提交修改仍保留。

## 实际合并及发布结果

- 合并提交`02fabed9939311671ec450fb794eb0c838a8d934`，父提交为eec72ed与07b33c4；已推送GitHub和Gitea的main，并逐个核验113/114/115/116及113返工提交均为main祖先。
- 原`/Users/yangl/IdeaProjects/floating-ball-server`的34项未提交文件指纹未变，保存在`codex/preserve-main-workspace-20261126`；main位于独立工作树`/Users/yangl/.codex/worktrees/qc-main-integration/floating-ball-server`。116复现测试准确合入后已从原返工工作树移除重复未提交改动。
- 发布前只读提取确认9090运行`emr-qc-rules-v10`，JAR SHA-256为`536aac06f5a253205a7785df783ff42af514595be1f1425b3566015c2e2834f0`，确实未包含代配药v11及113返工。求助64因用户在当前聊天授权重新部署并提供接入条件、版本已核明而撤销，不伪造人工回复。
- `SKIP_TESTS=0 scripts/publish-xiaoshan.sh testing`实际退出0，执行全新Maven clean package，912项/0失败/0错误/1既有MySQL环境跳过（911通过），emrqc+phisemr相关327项全通过，npm ci/Vite及JAR内入口/4个资源检查通过。未修改发布脚本。
- 发布后PID为`241220`，profile=xiaoshan-test，端口9090；远端类文件确认`emr-qc-rules-v12`。本地和远端JAR SHA-256均为`4bd4019d3f9021189fd1c37b9445e5c7f557c5cbf4b91e6fa97bac7c86a1366d`。
- 外部readiness为HTTP200/UP；`/admin/`、4个入口资源及EmrQcView的JS/CSS均HTTP200且逐字节等于本次JAR。未登录访问质控session/records均401。
- 测试service.env发布前后摘要一致，所有者UID0、权限600保持；测试启动脚本摘要一致。正式环境readiness为UP，PID`211445`及JAR、启动脚本、service.env摘要与配置600/UID0均未变化。未读取或输出私有配置内容。
- 回滚备份：`/data/floating-ball-server-testing/floating-ball-server.jar.backup-20261126144636-9008`、`/data/floating-ball-server-testing/start.sh.backup-20261126144636-9008`；日志归档`/data/floating-ball-server-testing/logs/archive/20261126144636-9008`。
- 本地验证日志：`/tmp/qc-main-integration-tests.log`、`/tmp/qc-main-frontend-tests.log`、`/tmp/qc-main-publish-script-tests.log`、`/tmp/qc-main-publish.log`；只读基线/发布后核验在`/tmp/qc-main-remote-before.log`及`/tmp/qc-main-remote-after.log`，外部资源证据`/tmp/qc-main-external-verification.json`。

技术发布核验完成。重启后进程内PHIS会话需重新BBP登录，再查询测试病历触发当前页质控，确认v12、代配药100分零问题和标记；普通加号主诉仅扣1分。未实际读取患者病历或调用真实AI作病例质控，真实PHIS/模型业务及医学验收仍由用户另验，不自行验收。后续发布记录提交仅改文档，部署的代码对应上述合并提交。
