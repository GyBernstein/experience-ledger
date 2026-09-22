# 人的判断规则与 Agent 共享：设计核对及本次实现

本次是现有项目的增量能力，不发布 V2、不更改产品/Maven/npm 版本号；Spring Boot 沿用上次交付的 4.1.1。V4 是 Flyway 数据库迁移序号，不是产品版本。

## 要留下的是什么

保留以后仍会改变决策的问题和判断规则：触发情境、该问的问题、判断准则、不可丢的约束、trade-off、检验 AI 方案的方法、适用边界、反例、重审信号。一次性的答案、容易重新查询的信息、无法说明未来决策价值的内容，不进入判断库。

终端用户在「人的判断库」中完成：保留检查 → 写判断卡 → 保存待确认 → 人工确认发布 → 另行审核跨侧共享 → 使用/结果反馈 → 需要时发布后继版本。没有接入 LLM 也可用，不产生外部模型费用。未保存表单仅存在组件内存，不写 localStorage；选择不记不会创建 Candidate、Episode 或业务审计正文。已保存候选需要明确拒绝/归档，不能物理删除。

保留检查是人的结构化自评加确定性规则，不是系统证明“经验有价值”。不从长度、词频或模型自信度推断价值，也不保留隐藏推理过程。

## 与 Frozen Specification 的核对

| 设计点 | 本次处理 |
| --- | --- |
| 经验类型 | 保留 Frozen 枚举；普通判断使用 DECISION，负向判断使用 WARNING。不增删旧类型。 |
| Claim 来源 | 保留 OBSERVED / HUMAN_ASSERTED / AGENT_DERIVED / SYSTEM_DERIVED。人写的判断是 HUMAN_ASSERTED，不伪装 OBSERVED。 |
| 双时态、Supersession | 沿用 V1 有效时间和记录时间；修订新建版本，正文、规则和关联在提交后冻结。 |
| Evidence 一等公民 | 判断可暂不附证据；要求证据的检索策略仍会排除它。已验证跨侧复用必须有测试/回放证据引用。 |
| 原生轨道 | 新增不可变 Family sidecar：HUMAN / AGENT。依据认证后的采集者身份，而非 sourceType、请求声明或发布人的身份。SYSTEM/TRUSTED_WORKFLOW 采集归 AGENT。 |
| 信心 | 作者自评与人工验证 assessedConfidence 分开。作者填 0.99 不提高检索验证分；未评估仍是原先的 0.5。 |
| 共享 | 共享不是第三种经验类型。使用绑定源版本的追加式授权事件，不改变原生轨道。 |
| 审计与隔离 | 新表均 FORCE RLS、复合空间外键、禁止 UPDATE/DELETE/TRUNCATE；保留检查不通过不写业务内容。 |
| Ontology | 不增加知识图谱、Kafka 或微服务。 |

原生归属表示形成入口，不替代每条 Claim 的来源。通过人类凭证导入 Agent 结论时仍需保留 AGENT_DERIVED Claim；组织级 Agent 原生经验应通过 Agent 采集入口产生。

## 跨侧共享边界

`NATIVE_ONLY`（默认或撤回）→ `CROSS_REFERENCE`（经审核，仅作参考）→ `CROSS_REUSABLE`（测试/回放、验证证据、人或受信工作流批准）。每次变更追加事件，带 `expectedPreviousId` 防止覆盖他人的审核。任意已授权状态都可追加 NATIVE_ONLY 撤回。

这里实现的是**源版本直接引用的审核授权**，并非完整的候选 Transfer 工作流。不会生成目标轨道经验、不会自动把规则转为 Prompt/工具指令，也不会由模型自动批准。CROSS_REUSABLE 表示审核者确认验证依据，系统本身没有执行该测试/回放。若要形成独立的目标经验，后续应新建目标 Family 并显式关联版本，不改原 Family 归属。

Memory Gateway `/api/v2/context` 始终是 AGENT 受众，包括人类使用 policyId 的预览。它读取 AGENT 原生经验，以及显式授权的 HUMAN 源版本；额外检查现有领域/任务/来源策略、范围、有效期、验证状态、矛盾、证据与预算。旧 V1 管理检索是人员治理视图，不是受限的 Human consumption gateway；其中可见未经跨侧共享的 Agent 原始记录，不能把“管理可见”理解为“批准组织复用”。

规则卡的全部问题、自然语言硬约束、权衡、检查方法、边界、反例和重审条件放在同一条预算候选中，不截断掉约束以凑预算。CROSS_REUSABLE 至少携带一个符合可靠性策略的验证证据引用，该引用计入 maxEvidence 和 contextText 的预算。原始 Claim 支持证据要求与共享验证证据要求分别检查。

授权撤回、源版本被替代/撤销、原始/验证证据被更正、验证状态成为 DISPUTED/DEPRECATED，都会影响实时供给。Compact 包含源版本、规则及共享状态指纹，每次检索重新验证；旧摘要不能绕过撤回。已交付给外部 Agent 的历史上下文无法远程收回，Agent 在新决策前应重新取包，证据下钻也会重新鉴权。

预算沿用 `UTF8_BYTES_UPPER_BOUND_V1` 保守计量，约束仅适用于 `contextText`；不是特定模型的精确 tokenizer 结果。整包 JSON 用于追溯，不应整体注入模型。文本边界由人/Agent 判断，真正需机器强制检查的条件必须写入结构化 applicability/constraints。

## 数据与 API

新增四表：`exp_experience_track`（Family 归属）、`exp_judgment_rule`（冻结 Version 判断卡）、`exp_reuse_event`（共享审核事件）、`exp_reuse_evidence`（该事件的验证证据引用）。不修改 V1/V2/V3 迁移文件。

| API | 用途 |
| --- | --- |
| POST /api/v2/judgments/gate | 无落库的保留检查 |
| POST /api/v2/judgments/candidates | HUMAN 保存判断候选；eventKey 幂等，同键异载荷 409 |
| POST /api/v2/judgments/candidates/{id} | HUMAN 编辑；expectedRevision 防覆盖 |
| POST /api/v2/judgments/candidates/{id}/publish | 确认发布；后继版本检查当前链尾 |
| GET /api/v2/judgments?query=&domain=&limit=30&offset=0 | 当前时间有效的规则列表；显示独立验证警示 |
| GET /api/v2/judgments/{versionId} | 规则、来源归属、验证及共享历史 |
| GET /api/v2/versions/{id}/reuse | 共享审核历史 |
| POST /api/v2/versions/{id}/reuse | 追加参考/复用授权或撤回 |

治理与读取规则详情沿用 HUMAN / TRUSTED_WORKFLOW 权限；专用判断创作/确认入口仅 HUMAN。AGENT/SYSTEM 仍只能采集 Candidate、走 Gateway、取已授权证据及上报反馈，不能调用判断编辑/共享接口。旧 V1 发布也执行规则一致性与原生轨道检查。

Save 的 `rule` 包含 title、futureDecision、situation、questions[]、judgment、hardConstraints[]、tradeoff、verification、boundaries、reviseWhen、counterexample、authorConfidence、negative。`gate` 包含 changesFutureDecision、reusable、readilyRecoverable、decisionImpact。其他字段为 domain、experienceKey、validFrom/validTo、applicability、constraints、evidenceIds[]、eventKey、expectedRevision，以及可选 sourceFamilyId/sourceVersionId（恢复草稿后仍保留后继版本目标）。可运行请求示例见 `frontend/tests/judgment-api-smoke.ts`。

```json
{
  "targetTrack": "AGENT",
  "mode": "CROSS_REFERENCE",
  "validationMethod": "HUMAN_REVIEW",
  "reason": "已核对适用工况，仅供诊断参考，不作为停机指令",
  "evidenceIds": [],
  "expectedPreviousId": null
}
```

## 实施范围与后续阶段

本次已实现：保留门槛、判断卡、人类录入/审核/修订、原生归属、版本级直接共享/撤回、受约束 Gateway、Compact 失效、验证证据下钻及沿用使用/结果反馈。

后续按依赖顺序推进，不冒充当前功能：

1. 明确 Human consumption policy、分页候选工作队列；完整 Transfer 候选→验证→目标版本，以及 Agent→Human 的模式提炼审核。
2. 独立 Agent run detail（模型、工具、Prompt/Policy 版本等）、人工采集辅助提炼；AI 输出始终是待确认建议。
3. L0–L3 多层 packet、问题覆盖型摘要生成与代表经验选择；当前仍是已有确定性 Compact 和整条规则预算选择。
4. DecisionProvider SPI / Laya sidecar 先 shadow，校准后再 assist。当前没有调用 Laya、没有伪造预测分或回放测试。
5. 经过验证后才考虑主动保留门槛、自动失效信号、成本闭环和自动程序化迁移。
