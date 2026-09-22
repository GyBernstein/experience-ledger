# Experience Ledger 前端

React 19 + TypeScript + Vite 的独立 SPA，连接本项目 Spring Boot `/api/v1` 与 `/api/v2`。采用响应式工作台布局、Lucide 图标与原生表单；没有额外 UI 套件或前端数据库。源码、依赖锁文件和已构建的 `dist/` 随完整交付包提供。

## 最快启动：前后端 Docker Compose

需要 Docker Engine / Docker Desktop（Linux 容器）及 Compose v2。在项目根目录执行：

```bash
cp .env.example .env
# Windows PowerShell 可使用 Copy-Item .env.example .env
# 首次本机试用可使用示例配置；供他人访问前改成自己的数据库密码、凭证和身份。
docker compose -f docker-compose.yml -f docker-compose.frontend.yml up -d --build
docker compose logs -f application
```

应用启动、Flyway 完成后，按根目录 README 初始化 Space（尚未初始化时才需要执行；重复执行也不会覆盖已有 Space）：

```bash
docker compose exec -T db psql -U postgres -d ledger -v ON_ERROR_STOP=1 -v space_id=11111111-1111-1111-1111-111111111111 -v space_name=Demo < scripts/create-space.sql
```

PowerShell 对应命令：

```powershell
Get-Content -Raw scripts/create-space.sql | docker compose exec -T db psql -U postgres -d ledger -v ON_ERROR_STOP=1 -v space_id=11111111-1111-1111-1111-111111111111 -v space_name=Demo
```

打开 **http://localhost:3000**，输入 `.env` 的 `LEDGER_PRINCIPALS` 中对应人员的 `token` 值，不加 `Bearer ` 前缀。原样使用本机示例配置时，演示审核凭证是 `local-human-token-change-me`。修改过配置则必须输入实际值。

前端不会自动添加假数据。需要完整演示数据时可运行根目录的 `scripts/seed_demo.py`；也可直接从「采集经验」开始手工录入。

停止前后端（保留数据库卷）：

```bash
docker compose -f docker-compose.yml -f docker-compose.frontend.yml down
```

## 已有后端：单独开发前端

需要 Node.js 22.12+，推荐 Node.js 24。后端默认在本机 8080 端口。

```bash
cd frontend
npm ci
npm run dev
```

打开 **http://localhost:5173**。如果后端运行在其他地址，复制 `frontend/.env.example` 为 `frontend/.env`，修改 `LEDGER_API_TARGET`，然后重启 Vite。

浏览器始终请求同源 `/api/`，开发时由 Vite 代理、Docker 下由 Nginx 代理。无需为后端添加通配 CORS，也没有把 token 编进构建产物。不要将 token 放入 `VITE_*` 环境变量。Nginx `/api/` 代理不做失败重放。

## 构建产物

```bash
npm ci
npm run build
npm run preview
```

预览访问 **http://localhost:4173**，同样代理到 `LEDGER_API_TARGET`。压缩包内的 `dist/` 可用任何静态服务器托管，但必须配置同源 `/api/` 代理；不能双击 `index.html` 后期待它直接连接后端。示例 Nginx 配置见 `nginx.conf`，其中 `application:8080` 是 Compose 内的后端服务名。接入已有服务器时改成实际后端地址。

默认 Compose 仅绑定本机端口。供终端用户访问时，应在现有 HTTPS 入口上配置域名与代理。

## 页面与后端能力

| 页面 | 用户可以做什么 |
|---|---|
| AI 采集 | 只输入原始事实/对话/日志；Ledger 自动生成结构化 Draft、Claim 和信息缺口 |
| 审核工作箱 | Human/Agent 草稿筛选、原文/草稿双栏核对、自然语言修订、手工微调、Diff、拒绝或显式发布 |
| 经验检索 | 关键词、领域、类型、上下文、双时态筛选；查看匹配依据 |
| 经验详情 | 历史链、正文、Claim、证据、过程、矛盾提醒、统计；创建演进候选、版本关系和撤销 |
| 证据记录 | 按 ID 查询；文字或 JSON 录入；创建替代证据进行更正 |
| 使用与反馈 | 记录采用的版本；按 Usage ID 查询并多次追加结果 |
| 审计日志 | 按目标 ID 和条数查询只读日志 |
| 上下文供给 | 按策略/范围/预算预览、证据下钻、幂等结果反馈 |
| 知识压缩 | Compact 草稿/审核/退役、来源失效、独立验证与置信度 |
| 检索策略 | 域/任务/来源/预算策略和身份绑定，CAS 更新 |
| Agent 运营 | 运行量、上下文量、延迟、自报费用、人工接受/否决反馈 |

V1.1 工作流见 `../docs/agent-context-guide.md`；新页面面向 HUMAN/TRUSTED_WORKFLOW，Agent 通过 V2 程序接口接入。

终端用户操作说明见 `../docs/frontend-user-guide.md`。

## 权限与交互约束

- 沿用 Frozen V1 的固定凭证模型：Space、Actor 和治理权限由后端决定。前端没有注册、账号管理、企业 SSO，也不伪造“普通用户/审核员”的独立 RBAC；目前 HUMAN / TRUSTED_WORKFLOW 可治理，其他身份依后端权限限制。
- token 仅保存在页面内存中。刷新、关闭或断开后需要重新输入；不写入 LocalStorage/SessionStorage。
- Candidate revision 冲突提示重新加载并核对；不会自动覆盖。审核页面离开时会提示未保存修改，可导出草稿备份（其中保留原始 JSON 文本，供人工恢复，不提供自动导入）。其他录入页请先提交再离开。
- Observation 必须关联 SUPPORTS 证据；Derived 必须保留推导方法。前端预校验之外，后端与数据库继续承担最终校验。
- 发布后仅查看；新内容通过后继版本更新。合并只建立归档引用。
- Capture 幂等键在本次表单内保持不变。“开始新记录”才生成新键。Usage/Outcome/证据没有事件幂等键，提交中断后先核查再决定是否重试。
- 列表使用现有 API 的有限条数，不显示虚假的全库总数，也不模拟服务端分页。Evidence / Usage 没有全量列表接口，使用 ID 查询。
- 历史时点查询选择当时正文；生命周期、证据更正状态、关系和统计明确显示当前状态。
- 当前证据输入支持文字快照和 JSON；不包含附件二进制上传或外部文档下载。

## 测试

```bash
npm test
npm run build
npm run format
```

真实后端客户端契约测试会写入数据，必须使用独立测试空间的 HUMAN 凭证：

```bash
LEDGER_URL=http://localhost:8080 LEDGER_TOKEN=<test-human-token> npm run test:api
```

PowerShell：

```powershell
$env:LEDGER_URL = 'http://localhost:8080'
$env:LEDGER_TOKEN = '<test-human-token>'
npm run test:api
```

此测试使用与页面相同的 `LedgerApi` 和 Draft 转换逻辑。原生 PostgreSQL、Nginx 代理和构建验证已接入根目录 GitHub Actions，实际执行状态见 `../docs/frontend-verification.md`。

## 代码导航

- `src/App.tsx`：内存凭证、工作台布局与 Hash 路由。
- `src/api.ts`：同源请求、错误码、超时及“结果不确定”提示。
- `src/domain.ts`：Draft 转换、来源与证据预校验、时区转换。
- `src/pages/Authoring.tsx`：V1.2 AI 采集、Review Inbox、Draft 修订与发布。
- `src/pages/Candidates.tsx`：V1 兼容工作流（不再作为主导航入口）。
- `src/pages/Experiences.tsx`：检索、历史、反馈和治理。
- `src/pages/AgentContext.tsx`：策略绑定、Compact、上下文试验和运营。
- `src/pages/Records.tsx`：证据、使用、结果和审计。
- `src/components/ui.tsx`：表单、状态、通知与异步状态。
- `tests/`：客户端规则测试和真实 API 契约脚本。

框架参考：[React 文档](https://react.dev/learn)、[Vite 文档](https://vite.dev/guide/)。
