# 全医慧助（PCIE）服务端需求基线

> 正式英文名称：Primary Care Intelligent Expert
>
> 工程名：`floating-ball-server`

## 两慢病真实业务系统边界

全医慧助（PCIE）医生桌面端继续提供高血压与 2 型糖尿病查询、随访、健康处方和年度评估能力，但正式业务数据只进入既有慢病系统。

边界如下：

1. 取得患者身份证号后，桌面端通过 HIS Adapter 的 `api/phis.aiAdapterService/queryPatientVisitHistoryData` 查询正式数据，请求体为 `[{"idCard":"..."}]`。
2. 医生确认后的随访通过 HIS Adapter 的 `api/phis.aiAdapterService/saveTcdForm` 直接保存，严格采用正式接口的 `TcdVisitForm` 字段和结构，不增加翻译层。
3. `floating-ball-server` 不接收、不校验、不保存慢病随访，也不提供自定义慢病保存响应。
4. 健康处方和年度评估只在桌面端生成、确认和打印，不向区域后端保存快照。
5. 服务端不建设慢病表、慢病 Mapper/Service/Controller 或慢病管理端 CRUD；正式历史记录和保存结果以业务系统为准。
6. AI 不是规则发布者。AI 只生成医生确认前的建议草稿，不修改正式路径、历史随访记录或临床规则。
