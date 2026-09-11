# V1.1 Memory Gateway API

所有接口需要 `Authorization: Bearer <token>`；Space/Actor 由服务端配置绑定。下文路径均以 `/api/v2` 开头。`ContextRequests.java` 是完整请求约束，响应字段采用数据库 snake_case，context 输出采用 camelCase。

| 方法/路径 | 权限与用途 |
|---|---|
| GET /me | 读取当前身份及启用策略；无绑定返回 403 |
| POST /context | 获取预算约束上下文，记录不可变 run |
| GET /evidence/{id}?runId=... | 只能下钻自己运行供给过且当前仍符合策略的证据 |
| POST /feedback | 关联运行和供给版本的幂等使用/结果反馈 |
| GET/POST /policies | 人员读取/创建策略 |
| POST /policies/{id} | 人员更新策略，必须 expectedRevision |
| GET/POST /bindings | 人员读取/创建/修改身份绑定；新绑定 revision 0，修改须当前 revision |
| GET/POST /versions/{id}/validations | 人员读取/追加独立验证 |
| GET/POST /compacts | 人员读取最近 100 条/创建摘要草稿 |
| POST /compacts/{id}/approve 或 /retire | 人员批准或退役，body 为 `{ "reason": "原因" }` |
| POST /feedback/{id}/review | 人员追加复核，verdict 为 ACCEPTED/REJECTED，加 reason |
| GET /operations | 人员读取 30 天聚合与最近 100 条运行/反馈 |

## 创建策略与绑定

```json
{
  "name": "设备诊断",
  "enabled": true,
  "reason": "设备域初始策略",
  "rules": {
    "domains": ["equipment"],
    "taskTypes": ["equipment_diagnosis"],
    "allowedOrigins": ["OBSERVED", "HUMAN_ASSERTED"],
    "maxContextTokens": 3000,
    "maxEvidence": 5,
    "maxItems": 4,
    "minConfidence": 0.8,
    "minEvidenceReliability": 0.7,
    "maxAgeDays": 3650,
    "requireEvidence": true,
    "requireNegativeCases": true,
    "allowUnknownScope": false,
    "allowDeepSearch": false,
    "candidateLimit": 50,
    "deepCandidateLimit": 200
  }
}
```

更新策略需要在相同 body 增加 `expectedRevision`。绑定示例：

```json
{"actorType":"AGENT","actorId":"equipment-agent-01","policyId":"<返回的策略UUID>","enabled":true,"expectedRevision":0,"reason":"设备Agent接入"}
```

凭证配置中必须已有该 Actor ID。绑定不会创建凭证。治理人员预览用 policyId；Agent 提供 policyId 返回 403。

## 上下文请求

```json
{
  "taskType": "equipment_diagnosis",
  "domain": "equipment",
  "assetType": "pump",
  "query": "high vibration",
  "context": {"model":"P100","load":80,"maintenanceWindow":true},
  "maxContextTokens": 2500,
  "maxEvidence": 5,
  "minConfidence": 0.8,
  "needNegativeCases": true,
  "needEvidence": true,
  "deepSearch": false
}
```

响应含 runId、READY/ESCALATE_HUMAN、contextText、selected、gaps、budget、policy、policyRules、diagnostics、asOf。请求中的同名 taskType/assetType 与 context 内值冲突返回 400。策略域/任务越权返回 403。selected 包含 compactId（原始经验为 null）、representativeVersionId、versionIds、evidence、negative、assessedConfidence 和 units。

Agent 处理顺序：检查 status；READY 时仅注入 contextText；不足时按 gaps 请求人员帮助，不能自行降低必须证据/反例。原始 Evidence 下钻是额外的上下文，应由 Agent 在其剩余模型预算内决定是否注入。

## Compact / 验证

```json
{"title":"泵振动诊断检查","summary":"先核查传感器，再在稳定负载下重复测量。","domain":"equipment","taskType":"equipment_diagnosis","representativeId":"<版本UUID>","versionIds":["<版本UUID>","<另一个同范围版本UUID>"],"reason":"工程师整理"}
```

先创建，再 approve。摘要是 HUMAN_ASSERTED，不重写原 Claim。版本验证：

```json
{"status":"VERIFIED","assessedConfidence":0.9,"reason":"已核对独立测量记录"}
```

DISPUTED/DEPRECATED 不向 Agent 供给；Candidate 未发布前不进入 Gateway。已有来源发生替代、撤销、证据更正、验证变化或矛盾关系变化时，Compact 的 fresh=false，需要新建并审核。

## 反馈与成本

```json
{
  "runId":"<本Agent运行UUID>",
  "versionId":"<本次供给中的版本UUID>",
  "eventKey":"diagnosis-20260911-001-result-1",
  "adopted":true,
  "outcomeType":"SUCCESS",
  "evaluation":"排除传感器误差后定位到基础松动",
  "actualInputTokens":1200,
  "actualOutputTokens":160,
  "reportedCost":0.012,
  "currency":"CNY"
}
```

结果可为 SUCCESS/FAILURE/PARTIAL_SUCCESS/INCONCLUSIVE；null 表示仅报告采用。计数和费用可为 null。费用非空时必须币种。金额/Token 是**本事件增量**。

同身份相同 eventKey + 相同内容返回原记录；内容变化返回 409。一次 run/version 对应一个 Usage，可追加多个不同 eventKey 的 Outcome；已记录 adopted 不可通过后续事件改写。人工否决追加 review，不删除反馈、不自动修改验证状态。运营聚合保留所有原始报告，包括被否决报告，费用按币种分组。
