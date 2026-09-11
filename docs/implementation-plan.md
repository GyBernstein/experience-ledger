# 实施阶段与冻结语义

## 阶段规划

| 阶段 | 目标 | 代码交付 | 验收 |
|---|---|---|---|
| 1 规格收敛 | 逐项读完冻结版，再对照原设计 | 冻结决策清单与原文校验值 | 14 核心表、明确非目标 |
| 2 数据底座 | 先通过数据库执行关键不变量 | V1/V2 Flyway migrations、领域规则 | FK、RLS、不可变 Trigger、链约束、双时态 CHECK |
| 3 业务闭环 | 采集、审核、发布、证据、反馈 | GatewayController / LedgerService | 原子发布、并发冲突、事件幂等 |
| 4 检索 | 两类召回、时态过滤、解释 | RetrievalService / Applicability | FTS、pgvector、回退、Space 隔离 |
| 5 Provider 与运行 | 无模型可用、异步失败隔离 | Provider 接口、Noop、HTTP Embedding、DB job worker、Actuator | 故障注入、重试、审计、指标 |
| 6 交付验收 | 可重复演示和测试 | Docker、测试、Python client、示例脚本、文档 | 见 verification.md；真实 PG 并发测试是发布门槛 |

本次已实现各阶段代码；执行证据和未完成环境验收以 verification.md 为准。后续先在目标环境完成第 6 阶段，再接入一类真实 Agent coding session，不提前增加图谱。按项目所有者后续要求，现已补充前端工作台；前端范围与验证见 `frontend/README.md` 和 `docs/frontend-verification.md`。

## 原设计与 Frozen 的冲突

| 问题 | 采用的 Frozen 语义 |
|---|---|
| Family.current_version_id | 不建该字段，使用 Version 双时态查询 |
| Episode.experience_version_id | 不建，使用 exp_experience_episode 多对多 |
| Experience-level Evidence 表 | 不建 exp_experience_evidence，通过 Claim 聚合 |
| Relation.SUPERSEDES | 不允许；只用 Version.supersedes_id |
| Usage.outcome_id | 不建；Outcome.usage_id 支持 1:N |
| 旧版 review_state / visibility 原位修改 | 不实现，只允许冻结版 lifecycle 字段及可重建 embedding 派生字段 |
| 自动发布 / 自动推断事实 | 不实现，审核接口要求 HUMAN 或 TRUSTED_WORKFLOW |

## 实现选择与边界

- `valid_to` 与其他业务语义字段一样冻结，修订需新版本；它不是通过后台更新延长/截断的字段。
- `recorded_at` / `invalidated_at` 由数据库决定。替代时先锁 Family，再用同一数据库时间关闭旧认知并创建新认知。
- 子表在 Version 创建事务内完成，`creation_tx` 是辅助封存标记；提交后新增 Claim、证据链接、Context、Episode 关联均拒绝。
- 因而 MERGED 表示候选归档到已有 Version/Episode 的引用，不改写已发布对象。候选内容、来源 Episode、目标 ID 与审计保留，版本查询返回 mergedCandidates。要把新增证据或 Episode 正式吸收进经验，走新版本。
- Observed Claim 要求至少一条 SUPPORTS Evidence；无证据的结论可以用 HUMAN_ASSERTED 或明确的 DERIVED。系统不把“存在证据”当作证据真实或因果成立的证明。
- 新版本只允许从尚未关闭的 VERIFIED 链尾替代。INVALIDATED 不重新开启，避免重写已经关闭的 recorded time；重新立项用新 Family，并可建立 RELATED_TO。
- 不做未来版本定时激活；发布时 validFrom 不得晚于数据库当前时刻。可以发布已结束业务区间的历史总结。
- Candidate 默认幂等键只在 sourceSystem/sourceRef/eventType 齐全时计算；单句输入缺少事件身份时生成独立键，避免所有人工记录误合并。需要重试的单句请求应传稳定 dedupKey。
- 固定 vector(384) 是本工程物理建模选择，不绑定供应商。更换维数要增加 migration 和回填；同维不同模型通过 embedding_model 隔开，禁止混比。
- 关系端点指向版本，历史时态查询只过滤版本认知；统计、证据 lifecycle、版本 status 返回当前状态并显式标识。

## 规范验收映射

| Frozen 章节 | 主要实现 | 测试/验证 |
|---|---|---|
| 6–12、56–59 | V1 migration：复合 FK、RLS、版本锁/链、半开区间、不可变 | spaceIsolation、temporal、immutableVersion、concurrentSupersession |
| 13–19、36–37、60 | Capture/Review/Verify、M:N Episode、candidate state、job | candidateIdempotency、promotionRollback、merge、seed_demo |
| 20–27 | ClaimRules、证据快照 hash、correction、sealed_parent、V2 deferred checks | observedAndDerived、originCannotBeRelabelled、evidenceCorrection |
| 28–43 | Context、Applicability、FTS/pgvector、统计投影、ConfidenceCalculator | applicabilityRanges、hybridAndApplicability、FTS fallback |
| 44–50、64、71 | Gateway、Bearer ActorContext、Provider 接口 | httpEndToEnd、agentCannotVerifyOrSpoofActor |
| 51–55、61–62 | Episode 摘要、Audit Trigger、异步 job、metrics | auditAppendOnly、providerFailure、seed_demo |
| 72–76、79 | DomainTest / LedgerIT、Docker、文档、演示脚本 | verification.md |

无自动演化规则引擎：feedback 已可创建 EVOLUTION Candidate，正式版本只经审核创建。
