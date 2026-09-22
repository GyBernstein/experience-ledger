# 判断规则增量验证

日期：2026-09-22。基线：已交付 Spring Boot 4.1.1 源码包；没有修改 Maven/npm/产品版本号。

| 检查 | 结果 |
| --- | --- |
| JDK 21 / Spring Boot 4.1.1 `mvn verify` | 构建成功；16 个单元测试通过 |
| LedgerIT | 22 项：21 通过，1 跳过 |
| React / TypeScript / Vite 生产构建 | 通过 |
| 前端 Node 测试 | 16 通过 |
| 实际可执行 JAR 的原有 Agent Context HTTP 脚本 | 9 组场景通过；人类测试记录显式共享后使用 |
| 原有 V1 seed_demo 流程 | 通过 |
| 前端 LedgerApi 原有真实 HTTP 契约 | 11 步通过 |
| 新判断卡真实 HTTP 契约 | 保留门槛、幂等/异载荷冲突、编辑、发布、浏览、共享、真实 Agent 身份获取、禁止 Agent 人类录入、撤回均通过 |
| V3 已有数据迁移到 V4 | 以 NOBYPASSRLS owner 执行通过；AGENT 采集/HUMAN 审核被正确识别，伪造 source_type 无效，跨空间不可见，迁移后 exp_space FORCE RLS 恢复 |

新增集成测试覆盖：不保留时没有 Candidate；作者自评 0.99 不提升 0.5 的独立验证分；规则问题、约束、边界完整进包；过小预算不返回残缺规则；默认不跨侧共享；共享的 CAS；撤回后旧 Compact 和历史 run 的证据下钻失效；共享验证 Evidence 计入证据预算；Evidence 更正后停供；四张新表不可变；后继版本不继承授权；原生轨道不可在版本链内切换；Agent 采集经人审核仍是 AGENT；候选编辑和发布的修订冲突；规则与通用 Draft 不一致被拒绝。

当前环境数据库为 PostgreSQL WASM / PGlite（含 pgvector），运行账号非 superuser、无 RLS bypass。跳过的 1 项是原有多连接并发 Supersession 测试；没有声称已在这里运行 Docker/PostgreSQL 16、Windows IDEA 或真实浏览器视觉验收。GitHub Actions 保留原生 PostgreSQL Testcontainers 和 Compose/Nginx 契约检查，新增了判断卡 HTTP 契约。

留存报告见 `docs/validation/judgment/`。CI 的测试账号和示例记录只用于隔离测试空间，实际部署仍使用你自己的凭证。
