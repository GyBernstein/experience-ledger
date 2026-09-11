# Experience Ledger V1.1：Agent 组织的上下文供给层

目标：由两个人维护几十个 Agent 的共享经验，减少重复推理和无效上下文，并保留判断依据。V1 Frozen Specification 仍优先；本版本为增量扩展，不改变 V1 双时态、单链 Supersession、Evidence、Observed/Derived 和 Append-only Audit。

## 需求与实现

| 能力 | 本次实现 | 设计边界 |
|---|---|---|
| Applicability Scope | 复用版本 applicability/constraints，检索校验任务、资产与运行条件；数字 Min/Max、枚举和相等判断 | 条件冲突拒绝；缺失条件默认拒绝，可由治理策略显式允许 |
| Validation / Confidence | 独立追加验证事件，VERIFIED / ADOPTED / DISPUTED / DEPRECATED | Candidate 仍用 V1 状态机；无独立评估的已发布版本默认 0.5，评估分不等于成功概率 |
| Decision → Action → Outcome | 复用正文 decision/action/outcomeSummary，反馈关联运行、版本、Usage、多次 Outcome | 人工复核不覆盖原始结果，也不自动提升置信度 |
| Experience Compact | 同域、同范围/约束、同正反方向的来源簇，代表版本、人工摘要与来源指纹 | DRAFT → ACTIVE → RETIRED；正文/来源不可修改，修改需新建 |
| Cost-aware Retrieval | 服务端 Policy + 有界候选池 + 预算选择 + 反例/证据预留 | 无外部 LLM/Embedding 调用；有界贪心，不承诺全局最优 |
| Usage Feedback | 幂等事件、一次运行/版本对应一个 Usage、多次 Outcome、增量 Token/费用、人工接受/否决 | 自报费用，非供应商账单；重复 eventKey 变更内容返回冲突 |
| Namespace / Domain | Space 为 RLS 租户边界，domain 为策略可见域 | 不做跨 Space 引用；不引入复杂本体或知识图谱 |
| Hot/Warm/Cold | Hot：最多 128 条已编译摘要缓存，TTL 60 秒；Warm：审核 Compact；Cold：有限原始版本检索 | 每次命中缓存前重新校验来源和策略；不是自动归档系统 |
| Experience API | V2 Memory Gateway、证据下钻、反馈、治理和运营 API | Agent 不能直接查询数据库或绕过策略使用 V1 通用检索 |

## 数据与信任边界

新增七张表：exp_retrieval_policy、exp_agent_binding、exp_validation_event、exp_compact、exp_context_run、exp_agent_feedback、exp_feedback_review。全部启用 FORCE RLS，禁止 DELETE/TRUNCATE；运行/验证/反馈/复核事件禁止 UPDATE。策略与绑定修订使用 CAS，治理更新写入含前后快照的审计。

身份来自服务端 Bearer 配置；请求头不改变身份。策略以 Space + ActorType + ActorId 绑定，Agent 无法指定 policyId。人员可以预览指定策略，但其证据下钻仍须有相应身份绑定。策略禁用或绑定停用后，新请求被拒绝。

**Agent 接口兼容变化**：AGENT/SYSTEM 仅保留 V1 capture，并使用 V2 context/evidence/feedback/me。V1 通用查询、Evidence 直读、Usage/Outcome 接口返回 403，防止绕过策略。HUMAN/TRUSTED_WORKFLOW 的 V1 操作继续可用。不要给 Agent 人员治理凭证。

## 检索与预算

1. 合并策略和请求：上下文/证据预算取较小值，最低置信度取较大值，强制证据/反例取逻辑 OR。
2. 在当前有效、同域版本中按 FTS 相关度与时间取有限候选池，筛选适用范围、约束、来源、独立验证、证据可靠度与年龄。争议、弃用、矛盾关系、失效/被更正证据排除。
3. 匹配 domain/task 的 ACTIVE Compact 检查所有成员，验证来源指纹。政策必须允许摘要自身的 HUMAN_ASSERTED 以及源 Claim 的来源类型。
4. 存在必须反例要求时，先找到预算内可共同放入的正例和反例；再按价值/成本贪心补充。Compact 与被其覆盖的原版本不重复放入。
5. 当要求证据时，每个来源成员至少有一个供给中的 SUPPORTS 引用。输出保留版本、证据哈希、来源、适用条件、决策/行动和结果事件数。
6. contextText 的完整 UTF-8 字节数受 maxContextTokens 限制，标记为 `UTF8_BYTES_UPPER_BOUND_V1`。这是常见 byte-BPE 的保守上界，不是假装精确的 tokenizer。换 tokenizer 须验证；模型额外系统提示/工具定义/其他历史需要调用方另行预留预算。
7. 不截断规则，不静默丢弃必须的反例。无法满足时返回 ESCALATE_HUMAN 和原因。

统计 baselineUnits 是本次有限原始候选快照的字节量；不是等价语义的精确基准，更不等于节省的 Token 或金额。Agent 只应将 contextText 放入模型，诊断 JSON 留在程序和运营页面。

## 两人的分工与日常流程

- 知识/业务治理负责人：维护适用范围，判断证据与独立验证，审核 Compact 和争议，确认哪些经验可采用。
- 平台/运营负责人：维护身份和策略预算，观察请求量、升级人工比例、成本和延迟，排查重复失败与不合理范围。

Agent 负责采集摘要、按任务申请上下文、按需下钻证据、回报采用与结果。经验验证和摘要发布由人员/可信治理工作流完成，避免未经确认的结论快速扩散。

## 开发阶段与交付

1. 治理基础：迁移、身份绑定、独立验证、修订审计。已实现。
2. Context 供给：Compact、指纹、严格筛选、预算与证据下钻。已实现。
3. 闭环：反馈幂等、Usage/Outcome、多币种费用、人工复核。已实现。
4. 人员工作台：保留现有采集/审核/证据/使用录入，新增四个页面。已实现。
5. 上线验收：目标 PostgreSQL/Docker、真实数据负载、身份/策略配置、供应商 tokenizer 对账。具体实测与待执行项见 agent-context-verification.md。

下一阶段再根据真实运营数据补充：模型专用 tokenizer、每日总预算与限流、供应商账单对账、异步聚类建议/人工批准、评估基准与检索质量反馈、批量加载优化、跨域引用治理。V1.1 已提供这些能力所需的运行与反馈数据，但没有将尚未开发的调度、自动聚类、全局费用熔断描述为现成功能。
