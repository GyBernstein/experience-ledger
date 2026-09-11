# Experience Ledger V1.1 · Agent Context

Java 21 / Spring Boot 4.1.1 / PostgreSQL 16 / pgvector 0.8.2 的模块化单体。以项目所有者提供的 Frozen Specification 为最高优先级；冻结决策和冲突处理见 `docs/implementation-plan.md`。两份原始设计文档不进入公开仓库，来源校验值见 `docs/specification-basis.md`。

实现 Candidate → Review → Version/Claim/Evidence → Retrieval → Usage/Outcome → Evolution Candidate → Supersession。默认无 LLM、无外部 Embedding，也能完成采集、人工审核与全文检索。配置 Embedding Provider 后启用混合检索。

**当前升级验收见 `docs/spring-boot-4-verification.md`；V1 历史验收保留在 `docs/verification.md`。**

## V1.1 新增能力

策略与身份绑定、独立验证/置信度、同范围 Compact、预算/反例/证据约束、幂等反馈及运营统计。前端保留终端用户录入，新增「上下文供给」「知识压缩」「检索策略」「Agent 运营」。

- `docs/agent-context-design.md`：需求映射、设计边界和开发阶段。
- `docs/agent-context-api.md`：V2 Gateway 和管理 API。
- `docs/agent-context-guide.md`：启动、升级和业务操作。
- `docs/agent-context-verification.md`：本次验证结果与待验收项。

## Windows / IDEA 本地运行

附件中 localhost:5432 连接被拒的问题已补充本地开发配置。启动 Docker Desktop 后执行：

```powershell
.\scripts\start-local.ps1 -DatabaseOnly
```

在 IDEA 激活 `local` profile，Working directory 设置为项目根目录，再启动 LedgerApplication；数据库默认为 `127.0.0.1:15432`。首次应用启动完成 Flyway 后执行 `.\scripts\initialize-local-space.ps1`。脚本会从 Compose 配置生成本地连接与凭证，不需要 IDEA 自动读取 `.env`。

Spring Boot 已升级至 **4.1.1**，保持 JDK21。Maven Reload 后先执行 `mvn clean test`。详细修复、已有数据库接入和密码问题见 `docs/spring-boot-4-upgrade.md`；本次验证见 `docs/spring-boot-4-verification.md`。

## 快速启动

需要 Docker Engine + Compose v2。以下命令在项目根目录执行。

```bash
cp .env.example .env
# Windows PowerShell: Copy-Item .env.example .env
# 编辑 .env：设置数据库密码、调用凭证和真实 actorId。
docker compose up -d --build
docker compose logs -f application
```

看到应用启动成功后，初始化演示 Space：

```bash
docker compose exec -T db psql -U postgres -d ledger -v ON_ERROR_STOP=1 -v space_id=11111111-1111-1111-1111-111111111111 -v space_name=Demo < scripts/create-space.sql
```

PowerShell 可使用：

```powershell
Get-Content -Raw scripts/create-space.sql | docker compose exec -T db psql -U postgres -d ledger -v ON_ERROR_STOP=1 -v space_id=11111111-1111-1111-1111-111111111111 -v space_name=Demo
```

浏览器打开 `http://localhost:8080/actuator/health`，预期 `{"status":"UP"}`。前端工作台位于 `frontend/`，可进行终端用户录入、审核、检索和反馈。前后端一起启动及操作说明见 `frontend/README.md` 和 `docs/frontend-user-guide.md`。

运行完整示例（Python 3.10+，无第三方依赖）：

```bash
export LEDGER_TOKEN=local-human-token-change-me
python scripts/seed_demo.py
```

PowerShell：

```powershell
$env:LEDGER_TOKEN = 'local-human-token-change-me'
python scripts/seed_demo.py
```

脚本每次新建一组带 `DEMO-` 前缀的数据，不删除已有数据。它包含成功/失败经验、Observed/Derived Claim、两个 Evidence、Episode、一次 Usage、两次 Outcome、V1→V2 和 CONTRADICTS，并验证闭环。`searchMode=FTS_METADATA` 是默认禁用 Embedding 时的正常结果。

## 开发与测试

需要 JDK 21、Maven 3.9+。数据库运行账户和 migration 账户应分开。

```bash
mvn test          # 领域单元测试，不需要数据库
mvn verify        # 使用 Testcontainers 自动启动真实 PostgreSQL + pgvector；需要 Docker
mvn package -DskipTests
java -jar target/experience-ledger-1.0.0.jar
```

也可以完全在 Docker 中运行完整测试：

```bash
docker compose --profile test run --rm tests
```

测试使用独立 `test-db`，不连接业务数据库。测试可重复运行，但会累积测试记录；如需全新测试库，只重建 `test-db` 容器。不要对业务数据库执行测试。

已有独立测试 PostgreSQL 可设置 `LEDGER_IT_URL`、`LEDGER_IT_ADMIN_USER`、`LEDGER_IT_ADMIN_PASSWORD` 后执行 `mvn verify`。测试管理员需要创建隔离测试角色；运行中的 Service 使用非超级用户、无 BYPASSRLS 的角色。默认不跳过数据库测试。

## 接入方式

1. 为 Agent 配置独立 `AGENT` 凭证，通过 `/candidates/capture` 提交可审计摘要。
2. 为审核人配置 `HUMAN` 凭证，完成 review / verify。一个凭证只绑定一个 Actor 和 Space。
3. 人员配置策略并绑定身份，Agent 通过 `/api/v2/context` 获取预算内上下文，通过 `/api/v2/evidence/{id}?runId=...` 下钻证据。
4. Agent 通过 `/api/v2/feedback` 幂等报告采用与结果，人员复核后决定是否形成 Evolution Candidate。

AGENT/SYSTEM 的 V1 通用检索、Evidence 直读与 Usage/Outcome 写入现在返回 403，须升级到 V2 Gateway。V1 capture 保留；人员治理接口继续可用。

`X-Actor-Type`、`X-Actor-Id`、`X-Space-Id` 等请求头不会改变身份。HTTP Bearer 凭证由服务端配置绑定真实身份。不要把 HUMAN/Trusted Workflow 的凭证交给 Agent。

## 文档

- `docs/implementation-plan.md`：阶段、冻结冲突处理与验收映射。
- `docs/architecture.md`：模块边界、事务和账户配置。
- `docs/domain-model.md`：14 张核心表与辅助投影。
- `docs/api.md`：全部接口、字段与最小发布示例。
- `docs/bitemporal.md`：双时态实例与历史查询。
- `docs/capture-flow.md`：低成本采集、审核、幂等与异步任务。
- `docs/providers-and-retrieval.md`：Provider 接入、中文检索、匹配和统计口径。
- `docs/verification.md`：本次实际验证与待验收项。

生产接入前先更换示例密码和 token，按 Space 配置 worker 列表；默认 Compose 仅将 HTTP 绑定到本机。外部访问可接入现有 TLS 反向代理。数据库端口未暴露。

## 前端工作台

React 19 + TypeScript + Vite，包含终端用户的经验、证据和使用结果录入，以及审核、历史追溯和审计。无需编写 JSON 即可完成基本采集、文字证据和文字反馈。

```bash
docker compose -f docker-compose.yml -f docker-compose.frontend.yml up -d --build
```

初始化 Space 后打开 `http://localhost:3000`。已有后端也可以在 `frontend/` 下执行 `npm ci`、`npm run dev`，访问 `http://localhost:5173`。本次前端验证见 `docs/frontend-verification.md`。
