# 达梦 DM8 定向升级说明

当前仓库不维护达梦全量初始化基线；现场 DM8 仍以 Oracle 兼容模式运行，定向升级脚本需使用应用 schema 账号执行。

两慢病正式数据通过 HIS Adapter 进入真实业务系统，当前仓库不再提供 `c_ai_chronic_followup`、`c_ai_chronic_artifact` 的建表或升级脚本。已部署 DM8 库中的历史冗余表由 DBA 在备份和变更审批后一次性删除。
