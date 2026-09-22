# V1.2 AI-assisted Authoring

## 目标与边界

V1.2 的默认写入路径是“轻录入、AI 先写、人审核”。`exp_candidate` 保存不可替换的原始输入；每次 AI 生成、AI 修订或人工编辑都在 `exp_experience_draft` 新增版本。只有 HUMAN / TRUSTED_WORKFLOW 调用 Accept 后才进入 Frozen V1 的 Experience、Claim、Evidence 与审计模型。

本版不会自动发布 VERIFIED，也不会让 Agent 绕过治理。模型输出始终是 `AGENT_DERIVED`；只有已有 Evidence 真正以 `SUPPORTS` 映射到观察 Claim 时，发布转换才允许生成 `OBSERVED/OBSERVATION`。不存在的 Evidence Ref 会被丢弃并保留为待确认问题。

## LLM 配置

默认配置不会访问外部网络：

```yaml
ledger.ai.enabled: false
ledger.ai.provider: local
```

此时 `local-safe` Provider 只创建保守、可审核的草稿，并明确提示需要人工提炼。接入 OpenAI-compatible 服务或 Ollama：

```bash
LEDGER_AI_ENABLED=true
LEDGER_AI_PROVIDER=openai-compatible # 也可写 ollama，仅作为审计标签
LEDGER_AI_URL=http://localhost:11434/v1/chat/completions
LEDGER_AI_API_KEY=
LEDGER_AI_MODEL=qwen3:14b
LEDGER_AI_TEMPERATURE=0.2
```

Docker 中调用宿主 Ollama 时通常将 URL 主机改为 `host.docker.internal`。URL 与密钥只由服务端配置，不进入数据库、前端或 Prompt。接口期望 OpenAI Chat Completions 兼容响应。

Prompt 存在 `exp_prompt_template`，新 Space 自动写入 `EXPERIENCE_DRAFT_V1` 与 `EXPERIENCE_REVISION_V1`。Draft 记录实际 `prompt_code/prompt_version/provider/model`。

## Human API

### 采集并生成草稿

```http
POST /api/v2/capture/human
Authorization: Bearer <human-token>
Content-Type: application/json
```

```json
{
  "rawContent": "粘贴原始对话、Issue 或执行记录",
  "sourceType": "MANUAL_TEXT",
  "sourceRef": "issue://123",
  "taskContext": {"domain":"engineering","taskType":"maven_diagnosis"},
  "evidenceRefs": ["<existing evidence UUID or sourceRef>"],
  "language": "zh-CN",
  "generateDraft": true,
  "dedupKey": "issue-123-authoring-v1"
}
```

### Review Inbox 与草稿

```text
GET  /api/v2/review/count
GET  /api/v2/review/inbox?state=PENDING&channel=AGENT&domain=engineering&limit=50
GET  /api/v2/drafts/{draftId}
POST /api/v2/drafts/{draftId}/revise
POST /api/v2/drafts/{draftId}/edit
POST /api/v2/drafts/{draftId}/regenerate
POST /api/v2/drafts/{draftId}/accept
POST /api/v2/drafts/{draftId}/reject
```

自然语言修订请求：

```json
{"expectedDraftVersion":1,"instruction":"不要写成确定根因，改成优先排查项，并补充适用边界。"}
```

所有写操作都要求 `expectedDraftVersion`。旧 Draft 或已被修订的 Draft 返回 `409 DRAFT_REVISION_CONFLICT`，不会覆盖新版本。

发布请求：

```json
{
  "expectedDraftVersion": 2,
  "mode": "CREATE_NEW_FAMILY",
  "domain": "engineering",
  "experienceType": "DECISION",
  "reason": "审核人确认关键判断、适用边界和 Evidence 映射"
}
```

发布是显式治理动作。成功后生成 `exp_draft_publication` 和 `exp_experience_summary`，可以从正式版本追溯 Candidate、Accepted Draft、Provider、Model 与 Prompt。

## Agent Capture

AGENT/SYSTEM 凭证允许调用：

```http
POST /api/v2/capture/agent
```

```json
{
  "agentRole": "java-coding-agent",
  "sourceType": "AGENT_RUN",
  "sourceRef": "agent-run://123",
  "task": "排查 Maven 依赖版本缺失",
  "rawContent": "任务过程中的原始事实与关键工具输出摘要",
  "result": "定位到 BOM 未覆盖子模块",
  "outcome": "补齐 dependencyManagement 后构建通过",
  "taskContext": {"domain":"engineering","repository":"service-a"},
  "evidenceRefs": ["build-log://123"],
  "dedupKey": "agent-run-123-experience"
}
```

成功返回 Candidate 和 Draft。Agent 不能 GET Draft、修订、拒绝或发布；这些请求由 `ActorFilter` 返回 `403 AGENT_GATEWAY_REQUIRED`。本地 Claude/Codex 可通过 curl、CLI 或 MCP Adapter 封装该端点。

仓库附带无第三方依赖的 CLI：

```bash
export LEDGER_URL=http://localhost:8080
export LEDGER_TOKEN=<agent-token>
printf '%s' '任务过程中的事实与关键工具输出' | python scripts/agent_authoring_capture.py \
  --task '排查 Maven 依赖' --result '定位 BOM 边界' --outcome '构建通过' \
  --task-type maven_diagnosis --dedup-key agent-run-123-experience
```

## 数据与失败语义

- `exp_authoring_candidate`：Capture 通道、Task Context、Evidence Ref 和原文 hash；
- `exp_experience_draft`：不可覆写的结构化 Draft 版本；仅允许状态迁移；
- `exp_draft_revision_log`：前后 Draft 的字段级 Diff；
- `exp_llm_invocation`：调用、Token、成本、延迟和失败摘要；
- `exp_draft_publication`：Accepted Draft 与正式版本映射；
- `exp_experience_summary`：L0 fingerprint、L1 compact、L2 summary。

LLM 调用或结构校验失败时，Candidate 已经独立提交，不会回滚或丢失。系统保存 `GENERATION_FAILED` Draft；审核人可重新生成。外部调用超时不自动重复发布，发布端点会先检查是否已有映射并返回现有结果。
