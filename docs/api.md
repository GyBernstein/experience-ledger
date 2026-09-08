# Memory Gateway API

Base path：`/api/v1`。请求 JSON 使用 camelCase；采集/写入对象返回数据库列名 snake_case，检索包使用 Frozen 要求的 camelCase。UUID 用字符串，Instant 用带时区的 ISO-8601 字符串。未知请求字段会被拒绝。

所有 API 需要 `Authorization: Bearer <token>`。Actor 和 Space 来自服务端凭证配置，不能由请求体指定。治理操作只允许 HUMAN / TRUSTED_WORKFLOW；其他类型可采集、查询、使用、反馈。

## 接口清单

| 方法 | 路径 | 含义 |
|---|---|---|
| POST | /candidates/capture | 幂等采集；最小仅 content |
| GET | /candidates?status=...&limit=50 | 按状态取候选，最多 100 |
| GET | /candidates/{candidateId} | 候选、revision、增强状态 |
| POST | /candidates/{candidateId}/review | 保存审核草案，推进 PENDING_REVIEW |
| POST | /candidates/{candidateId}/verify | 创建 Family 或创建后继版本 |
| POST | /candidates/{candidateId}/reject | 拒绝 |
| POST | /candidates/{candidateId}/merge | 归档到已有 Version/Episode |
| POST | /candidates/{candidateId}/disposition | DUPLICATE / EXPIRED / MERGED / REJECTED |
| POST | /candidates/{candidateId}/retry-processing | 非终态候选重试 |
| POST | /evidence | 创建不可变证据 |
| GET | /evidence/{evidenceId} | 证据及当前更正状态 |
| POST | /evidence/{evidenceId}/correct?reason=... | 新增替代 Evidence，保留旧快照 |
| POST | /experiences/search | Metadata + FTS + Vector + Applicability |
| GET | /experiences/{familyId} | 当前或 validAt/knownAt 指定的认知 |
| GET | /experiences/{familyId}/history | 整条历史版本链 |
| POST | /experiences/{familyId}/similar | 基于当前版本标题检索，排除本 Family |
| POST | /experiences/{familyId}/supersede | 从已审核 Candidate 创建后继版本 |
| POST | /versions/{versionId}/invalidate | 撤销当前版本认知，历史保留 |
| POST | /versions/{versionId}/retry-embedding | 重建指定版本的派生向量 |
| POST | /experiences/{versionId}/feedback | 创建 EVOLUTION Candidate，注意这里是 versionId |
| POST | /relations | 建立两个版本之间的轻关系 |
| POST | /usages | 使用记录，可带 immediateOutcome |
| POST | /usages/{usageId}/outcomes | 追加一次结果反馈 |
| GET | /usages/{usageId}/outcomes | 查看多次反馈 |
| GET | /audit?targetId=...&limit=100 | 当前 Space 审计，最多 1000 |

## 一次最小发布

### 1. Capture

```json
{
  "content": "候选生成阶段过早剪枝可能降低全局利用率。",
  "sourceSystem": "coding-session",
  "sourceRef": "session-001",
  "eventType": "TASK_COMPLETED",
  "dedupKey": "session-001-completed"
}
```

返回 Candidate `id`、`revision`、`status`。随后 GET 最新 Candidate 再审核，可避免 worker 刚更新 revision 造成冲突。

Capture 可附加 `candidateType`、`sourceType`、`extracted` object、`episode` 摘要和 `evidence` 数组。见 scripts/seed_demo.py 的完整示例。

### 2. Review

```json
{
  "expectedRevision": 1,
  "reason": "工程师确认需要沉淀",
  "draft": {
    "title": "候选生成与全局优化分离",
    "summary": "避免在候选阶段过早排除有价值的组合",
    "problem": "组合利用率偏低",
    "decision": "保留候选",
    "action": "延后局部剪枝",
    "outcomeSummary": "待持续跟踪",
    "lesson": "候选阶段保留对全局组合有价值的选择",
    "validFrom": "2026-01-01T00:00:00Z",
    "applicability": {"lengthMin": 700, "material": ["A", "B"]},
    "constraints": {"gpuAvailable": false},
    "claims": [
      {
        "claimType": "RECOMMENDATION",
        "content": "候选阶段避免过早局部剪枝",
        "originType": "HUMAN_ASSERTED"
      }
    ],
    "contextRefs": [{"refType": "PROJECT", "refValue": "rod_matching"}]
  }
}
```

这是无证据的人工建议，明确使用 HUMAN_ASSERTED。Observed 必须用 OBSERVATION 且附 SUPPORTS Evidence。`expectedRevision` 必须替换为 GET 的实际值。

### 3. Verify

```json
{
  "mode": "CREATE_NEW_FAMILY",
  "experienceKey": "EXP-ROD-001",
  "domain": "rod_matching",
  "experienceType": "OPTIMIZATION",
  "expectedRevision": 4,
  "reason": "人工接受"
}
```

revision 使用上一步 review 响应的新值。事务成功返回版本 `id`、`family_id`、`version_no`、`recorded_at` 等。

创建 V2 的 verify 请求改为：

```json
{
  "mode": "CREATE_NEW_VERSION",
  "familyId": "<family UUID>",
  "expectedSupersedesId": "<current version UUID>",
  "expectedRevision": 4,
  "reason": "新证据使适用范围改变"
}
```

替代端点 `/experiences/{familyId}/supersede` 则接收 `candidateId/expectedSupersedesId/expectedRevision/reason`，使用同一发布事务。

## Evidence 与 Claim

创建 Evidence：

```json
{
  "evidenceType": "TEST_RESULT",
  "sourceSystem": "test-runner",
  "sourceRef": "run-001",
  "snapshot": {"selected": 79, "effective": 79},
  "observedAt": "2026-08-18T08:00:00Z",
  "reliability": 0.8
}
```

Claim 引用：

```json
{
  "claimType": "OBSERVATION",
  "content": "有效棒材 79 根均被选择",
  "originType": "OBSERVED",
  "evidence": [{"evidenceId": "<evidence UUID>", "supportType": "SUPPORTS"}]
}
```

DERIVED 结论必须附 `derivationMethod`；Agent 因果推断用 CAUSAL_HYPOTHESIS / AGENT_DERIVED，不能在审核中仅改标签为观察或规则。

## Search

```json
{
  "query": "候选 剪枝 利用率",
  "domain": "rod_matching",
  "experienceType": "OPTIMIZATION",
  "context": {"length": 800, "material": "A", "PROJECT": "rod_matching"},
  "applicabilityFilter": {},
  "limit": 10
}
```

可选 `validAt/knownAt` 独立指定时间。query 为空时进行 Metadata/时态浏览。applicabilityFilter 是 PostgreSQL JSONB 包含过滤，context 用于匹配和解释，规则详见 providers-and-retrieval.md。

响应包括 mode、results、候选池信息和实际查询时间。每个结果提供 experienceId/versionId、正文、applicability/constraints、keyClaims/keyEvidence、contextRefs/episodes、usageStats/outcomeStats、confidenceSummary、whyMatched、contradictionWarnings。

mode：FTS_METADATA / HYBRID / FTS_METADATA_FALLBACK。HYBRID 表示查询向量可用；某些版本尚未完成 embedding 时仍可由 FTS 命中。

## Usage / Outcome / Feedback

```json
{
  "versionId": "<version UUID>",
  "queryContext": {"length": 800},
  "retrievalScore": 0.8,
  "applicabilityScore": 1.0,
  "recommended": true,
  "actuallyUsed": true,
  "immediateOutcome": {
    "outcomeType": "SUCCESS",
    "metrics": {"selected": 79},
    "notes": "本次处理通过",
    "observedAt": "2026-08-18T09:00:00Z"
  }
}
```

POST /usages/{id}/outcomes 使用 immediateOutcome 的相同结构，支持可选 evidenceId。Outcome type：SUCCESS/PARTIAL_SUCCESS/FAILURE/INCONCLUSIVE。

反馈：`{"content":"在材料 B 上发现新问题","dedupKey":"feedback-event-001"}`。只创建待审核 EVOLUTION Candidate。

Usage/Outcome 目前没有事件幂等键，不应在超时后无条件重放；先按 usageId 查结果或由调用方确保一次写入。Candidate Capture 是 V1 强制幂等的入口。

## 合并、拒绝、撤销

merge：`expectedRevision`、`targetVersionId` 或 `targetEpisodeId` 二选一、`reason`。

reject：`expectedRevision`、`reason`。

disposition：同上，增加 `status`；必须遵守 Candidate 状态机。

invalidate：`{"reason":"新证据已否定"}`。

relation：`fromVersionId/toVersionId/relationType/reason`。关系类型 SIMILAR_TO、SUPPORTS、CONTRADICTS、DERIVED_FROM、CAUSED_BY、RELATED_TO。都是版本 UUID。

## 错误与客户端处理

```json
{"code":"VERSION_CONFLICT","message":"VERSION_CONFLICT","traceId":"...","details":{}}
```

400：输入或时间区间不合法；401：凭证无效；403：非治理身份；404：该 Space 内不可见；409：版本、修订号、状态、不可变或数据库约束冲突。跨 Space ID 通常返回 404，避免泄露另一 Space 是否存在该对象。

发生 CANDIDATE_REVISION_CONFLICT，GET 最新 Candidate 后重新审核；VERSION_CONFLICT 必须重新查看当前链尾并决定如何整合，不能盲目覆盖。其他数据库错误以 CONSTRAINT_VIOLATION 隐藏具体 SQL。

完整可调用示例见 `scripts/ledger_client.py` 和 `scripts/seed_demo.py`。
