# 采集和审核

## 最小输入

```json
{"content":"这次问题发现是候选生成阶段剪枝过早。"}
```

Space/Actor 从服务端凭证绑定获得，时间由服务端补充。对需要网络重试的人工或 Agent 输入，附带稳定 dedupKey。系统事件可传 sourceSystem/sourceRef/eventType，系统会对包含 Space 的 JSON 元组做 SHA-256。

同一 Space 的同一 dedupKey 返回最早保存的 Candidate，不覆盖原事件内容，不重复创建 Episode/Evidence。需要修正已收到的事件应提交新事件键或在审核草案中修正。不能用所有空 source 字段计算同一个幂等键。

## 可审计摘要

可选 episode 接收 title、summary、occurredAt、completedAt、goal、importantObservations、toolsUsed、importantToolResults、changes、validation、result。DTO 拒绝未定义字段；extracted 中也拒绝明确命名的 private chain-of-thought 字段。内容提交者仍需仅发送操作摘要，不能把私有推理藏在任意自由文本中。

证据可随 capture.evidence 一起写入，也可先 POST /evidence。响应 extracted_json.capturedEvidenceIds 提供新证据 ID，便于审核时关联到 Claim。

## 异步阶段

Capture 事务提交 Candidate+job 后立即返回。Scheduled Worker 按配置的 Space 逐个领取 job：

1. `FOR UPDATE SKIP LOCKED` 领取并增加 attempts，设置 lease。
2. 事务外调用 Enrichment 和 Embedding，避免外部网络占用核心业务事务。
3. 独立事务写回派生结果，核对 job attempt 和 Candidate revision。
4. 无模型仍可经 Noop 进入 ENRICHED→PENDING_REVIEW。模型失败记录 FAILED 状态，Candidate 保留可审核。
5. 失败按 10/20/30 秒级退避，默认最多 3 次；进程中断导致的 RUNNING job 在 lease 过期后可重新领取。

相似经验建议只保存 family IDs，不自动去重、不自动 VERIFIED。Provider 结果存到 enrichmentSuggestion，人工 draft 单独保存，不把自动建议直接当事实。审核与 Worker 并发时，以 Candidate revision 防止建议覆盖人工修改。

## 审核与发布

review 接收 expectedRevision + draft，可从 NEW/ENRICHED 推进到 PENDING_REVIEW，无须等模型。用户可在未来页面只看“事件/经验建议/证据/相似项”，前端负责把确认后的结果转换成 draft。

verify 接收 review 后返回的新 revision。两种模式：CREATE_NEW_FAMILY 与 CREATE_NEW_VERSION。后者必须提供 familyId、expectedSupersedesId。版本完整内容只来源于审核保存的 draft，发布请求无需重复填写。

AGENT/SYSTEM 凭证无法审核、发布、替代、撤销或人工合并。HUMAN/TRUSTED_WORKFLOW 凭证应由上游可信认证/审批入口保管。

## 反馈

recordUsage 记录“是否推荐、是否实际使用”；recordOutcome 单独追加真实反馈，可在同一次 Usage 下记录不同时间结果。provideFeedback 只产生 EVOLUTION Candidate，不改正式经验。

失败任务可通过 retry-processing/retry-embedding 重新入队。终态 Candidate 不重处理；正式版本的 Embedding 可以重建。
