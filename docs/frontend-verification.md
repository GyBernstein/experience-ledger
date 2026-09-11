# 前端交付与验证记录

日期：2026-09-09。范围为项目所有者追加要求的 React 工作台与终端用户录入，不修改 Frozen V1 的后端治理、时态和不可变规则。

## 本次已完成

| 项目 | 结果 | 证据或范围 |
|---|---|---|
| React + TypeScript + Vite 构建 | 通过 | `validation/frontend-build.txt`；类型检查和生产构建 |
| 客户端规则与传输测试 | 13 项通过 | `validation/frontend-tests.txt`；来源保留、Observed 支持证据、时间、状态、修订冲突、超时不重放、鉴权和错误响应 |
| 真实 Java HTTP 后端契约闭环 | 通过 | `validation/frontend-api-smoke.json`；使用 UI 同款 `LedgerApi` 与 Draft 转换函数 |
| 配套源码与构建产物 | 已提供 | `frontend/` 源码、依赖锁与压缩包中的 `frontend/dist/` |
| 开发/部署接入 | 已配置 | Vite 代理、Nginx、`docker-compose.frontend.yml`；默认同源 API 请求 |
| 终端用户录入 | 已实现 | 文字经验、可选 Episode、文字/JSON 证据、使用记录、文字结果与演进反馈 |

真实 HTTP 验证运行了 Spring Boot 已构建 JAR、真实事务与迁移结构，数据库为补充环境 PGlite（PostgreSQL WASM + pgvector）并使用非超级用户应用角色。不是用假接口响应代替后端。

闭环包括证据创建、Capture 去重、保存审核、409 修订冲突、发布、搜索与嵌套经验包、版本历史、一次 Usage 下两次 Outcome、证据更正和审计查询。

## 尚未完成的验收

- 未执行浏览器点击或视觉验收；响应式布局与页面事件实现已提供，但不能据此宣称跨浏览器交互全部通过。
- 本工作环境没有运行原生 PostgreSQL 16 Docker 并发验收，也没有运行 Nginx + Compose 整体部署。WASM 补充测试不替代原生数据库并发验证。
- `.github/workflows/verify.yml` 已加入前端构建/测试及通过 Nginx 执行真实客户端契约测试。当前任务按压缩包交付，未推送、未产生远端 CI 运行结果。
- 没有验证企业 SSO、复杂 RBAC、真实 LLM、真实 Embedding 语义质量或附件上传，这些不是本次实现范围。

## 交付边界

前端直接使用已有 `/api/v1`，保持 camelCase 请求与 snake_case 写入响应的约定。所有身份、空间和治理限制继续由服务端检查。界面不提供改写已发布正文、删除审计、把 Agent 推导直接改为事实的捷径。

基本终端录入不要求 JSON。结构化适用范围、上下文和指标作为高级字段；普通用户可直接填文字证据与文字反馈。当前凭证仍是固定 token 模型，不构成企业账户权限管理系统。

运行和用户操作步骤分别见 `../frontend/README.md`、`frontend-user-guide.md`。原后端测试和验收边界见 `verification.md`。
