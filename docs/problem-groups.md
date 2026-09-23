# 同一问题，不同原因：实现复核

## 模型与边界

- **经验族**只承载同一判断规则的修订及 Supersession；一次新原因发布为新的经验族。问题组按领域和问题聚合经验族，不改变任何原有结论、证据、版本、时态或验证状态。
- `exp_problem_group` 保存问题导航名称；`exp_problem_group_event` 追加 `LINK/UNLINK`，包含决定归组时的版本、关联类型、理由及操作者。跨空间外键和 RLS 隔离；人或受信流程才能归组。关联事件不能更新或删除。
- 组的当前成员取每个经验族最近一次事件，内容取该族当前有效且已验证的版本；旧版本、失效版本不会冒充当前结论。组不代替 Compact，不把不同原因的证据合成一条结论。
- 草稿按问题与标题的全文匹配推荐案例，同时展示根因和适用范围；默认独立发布，没有匹配时不阻断。用户明确选“另一种原因”或“相同原因的独立案例”后才归组。
- Agent `/api/v2/context` 仍执行 Policy、适用条件、验证状态、证据与预算筛选。被选中的分组经验只增加“同一问题可能有多个原因，核对判别条件”的提示与问题组 ID；**不**自动暴露其他尚未通过策略筛选的案例。

## 交互与接口

- 审核草稿的 `similar[]` 增加 `root_cause`、`group_id`、`matchReason`、`relationHint`。`POST /api/v2/drafts/{id}/accept` 可传 `relatedFamilyId` 与 `problemRelation=ALTERNATIVE_CAUSE|SAME_CAUSE_CASE`；不传时维持独立发布。仅支持 `CREATE_NEW_FAMILY` 归组。发布完成后若关联失败，可重试同一请求：幂等读取已发布版本并补写关联。
- `GET /api/v2/problem-groups/suggestions/{familyId}`、`GET /api/v2/problem-groups/by-family/{familyId}` 用于详情页发现、查看不同原因及证据数量。
- `POST /api/v2/problem-groups/attach` 接收 `familyId,referenceFamilyId,relation,reason`；引用一个尚未归组的经验时自动创建问题组并关联双方。已归组的引用则直接追加新成员。
- `POST /api/v2/problem-groups/{id}/members`、`POST /api/v2/problem-groups/{id}/members/{familyId}/unlink` 用于事后归组和纠正；`GET /api/v2/problem-groups/{id}` 返回当前成员。已有其他组的经验要先撤销关联，再选择新的组。

## 复核结论与后续

- 相似匹配仅用于推荐，不能独立认定症状或根因一致；审核者需要核对型号、环境、适用条件和证据。当前采用已有全文索引，无外部嵌入服务依赖。
- 一个组可能只有一个有效成员（另一个被撤销或失效）；这代表有效案例的现状，不删除历史事件。
- 后续如需真正的 Agent 差异诊断摘要，应设计单独可审核、可版本化、带各原因证据引用的组摘要，并在 Agent Policy/预算内生成；目前没有把未经审核的汇总当作可信经验供给。
