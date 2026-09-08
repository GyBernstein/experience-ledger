# 领域模型

## 14 张冻结核心表

| 表 | 含义 / 主要关系 |
|---|---|
| exp_space | 最高隔离边界 |
| exp_experience_family | 稳定主题；space+experience_key 唯一 |
| exp_experience_version | 不可变业务内容；family+version_no 唯一；strict supersedes_id |
| exp_experience_claim | Version 的关键结论；origin 与 claim_type 分开 |
| exp_episode | 一次真实处理过程，可审计摘要 |
| exp_experience_episode | Version↔Episode M:N，SOURCE/VALIDATION/FAILURE_CASE/APPLICATION |
| exp_evidence | 不可变最小快照、hash、可选 URI、更正链 |
| exp_claim_evidence | Claim↔Evidence，SUPPORTS/CONTRADICTS/CONTEXT |
| exp_candidate | 可审核草案，事件幂等、状态机、修订号 |
| exp_context_ref | 简单类型/值引用，无 Ontology |
| exp_relation | 版本间 6 类轻关系；无 SUPERSEDES |
| exp_usage | 某次推荐/实际使用，不是搜索日志 |
| exp_outcome | Usage 的一次反馈，1:N |
| exp_audit_event | append-only 变更事实 |

所有关联表也有 space_id，通过复合外键拒绝跨 Space 引用。

## 两张技术辅助表

- `processing_job`：数据库任务队列，状态、attempts、lease、重试时刻。
- `exp_version_stats`：evidence_count、usage_count、success_count、partial_success_count、failure_count、human_verified、last_verified_at。由同事务 trigger 维护，是可重建投影。`confidence_score` 不落表。

使用统计以 Version 为单位。Evidence 数按不同 evidence_id 去重；usage_count 是 Usage 记录数（包括 recommended=true 而 actually_used=false 的记录）。SUCCESS/PARTIAL_SUCCESS/FAILURE 按 Outcome 事件计数，一个 Usage 可贡献多个事件，不能解读为独立试验成功率。

## 不可变范围

Version 的语义字段、valid interval、recorded_at、created fields、family/sequence/supersedes 都不可改。派生 embedding/model/status 可更新；业务 lifecycle 只允许 VERIFIED→SUPERSEDED/INVALIDATED 并写审计。不能把旧版本重新改回 VERIFIED。

Claims、Context、Episode associations、Claim-Evidence associations 只在创建 Version 的事务中插入；提交后不能增加/改写/删除。Evidence correction 是新增对象，旧 Claim 继续指向当时引用的证据。

Candidate 从 NEW→ENRICHED→PENDING_REVIEW，后续 VERIFIED/MERGED/REJECTED。NEW/ENRICHED 可转 DUPLICATE/REJECTED/EXPIRED。终态不可修改。DUPLICATE 需指向已有版本或 Episode；PENDING_REVIEW 的吸收操作使用 MERGED。

## Evidence hash

有内联 snapshot 时，服务端递归排序 JSON object key、保留 array 顺序，UTF-8 紧凑 JSON 做 SHA-256。传入 contentHash 时会核对。只有 URI、无 snapshot 的证据必须提供外部对象 contentHash；V1 不下载 URI，也不声称验证外部对象的内容。可靠度是来源声明，非真实性保证。
