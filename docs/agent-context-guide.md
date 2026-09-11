# V1.1 启动与操作

前端采用 React + TypeScript + Vite，后端沿用 Java 21 / Spring Boot / PostgreSQL + pgvector。完整包包含原 V1 功能、V1.1 源码、迁移、测试、前端构建产物和 Compose。

## 首次启动

在项目根目录执行：

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.frontend.yml up -d --build
docker compose exec -T db psql -U postgres -d ledger -v ON_ERROR_STOP=1 -v space_id=11111111-1111-1111-1111-111111111111 -v space_name=Demo < scripts/create-space.sql
```

Windows PowerShell 的复制命令为 `Copy-Item .env.example .env`；初始化空间的 PowerShell 管道见根 README。应用完成 Flyway V1～V3 后，访问 http://localhost:3000，使用 `.env` 中人员 token 登录。示例 token 仅用于本机演示；实际运行替换为独立凭证。

已有 V1 数据库：保留数据卷，先备份；替换代码后重建应用，Flyway 自动追加 V3。V1/V2 未修改。AGENT/SYSTEM 旧版通用检索及 Usage 调用须按 API 文档迁移到 V2。

## 业务人员录入仍在

「采集经验」录入事实与过程 →「证据记录」保存文字/JSON 快照 →「候选审核」维护适用条件、决策、行动、Claim 和支持证据 → 发布经验。后续结果可在「使用与反馈」继续追加。原有双时态检索、历史和审计页面保留。

工程师在审核时填写 applicability，例如 `{ "assetType":"pump", "loadMin":50, "loadMax":90 }`，constraints 例如 `{ "maintenanceWindow":true }`。经验类型 FAILURE/WARNING 用作反例。

## 新增治理与 Agent 供给

1. 「检索策略」设置 domain/task、来源与预算，保存后绑定 Agent ID。Agent ID 必须与服务端凭证配置一致。
2. 「知识压缩」按版本 ID 追加验证，明确置信度；选择相同范围、约束和正反方向的版本，创建 Compact 草稿，审核后批准。反例独立维护。
3. 「上下文供给」用策略、当前条件和预算预览。人员若需证据下钻，要将自己的 HUMAN Actor ID 也绑定到允许该领域的策略。Agent 不会传 policyId，而是由服务端读取其绑定。
4. Agent 通过 V2 API 获得 contextText，使用后回报 runId/版本/eventKey/结果。预算不足或条件未知会得到人工处理提示。
5. 「Agent 运营」查看请求、上下文量、耗时、升级人工、采用、自报费用及反馈。人工否决错误解读，必要时追加 DISPUTED 验证使相关经验退出供给。

## 开发与验收

```bash
# 前端
cd frontend
npm ci
npm test
npm run build
# 回到项目根目录后，JDK 21 + Maven + Docker
mvn verify
```

在**独立测试 Space**执行以下脚本（会写数据并更改/停用身份绑定的测试策略）：

```bash
LEDGER_URL=http://localhost:3000 LEDGER_TOKEN=local-human-token-change-me LEDGER_AGENT_TOKEN=local-agent-token-change-me python3 scripts/agent_context_smoke.py
```

如凭证中的身份不是 demo-reviewer/demo-agent，通过 `LEDGER_HUMAN_ID`、`LEDGER_AGENT_ID` 指定。测试不自动撤销已写审计和反馈；不要对业务身份运行。

当前构建和实际验证状态见 agent-context-verification.md。无需 GPU、Redis 或外部模型即可运行本次 Gateway。真实业务负载下的性能、准确率、tokenizer 和费用仍需按实际 Agent 集成验收。
