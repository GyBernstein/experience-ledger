# V1.1 实际验证记录

> 此文件保留 Spring Boot 3 版本的验收历史；升级至 4.1.1 后的实际结果见 `spring-boot-4-verification.md`。

验证日期：2026-09-11。工作区恢复后已重新生成并验证本次交付代码，以下结果对应此压缩包。

| 项目 | 实际结果 | 依据 |
|---|---|---|
| 后端全部主源码 Java 21 编译 | 通过 | javac --release 21，复用原 V1 JAR 内依赖；无新增后端依赖 |
| Spring Boot 启动与 HTTP API | 通过 | 实际启动应用，使用非超级用户数据库角色 |
| V1/V2/V3 迁移 | 通过（补充环境） | PostgreSQL WASM / PGlite + vector |
| Gateway 9 组接口场景 | 通过 | validation/agent-context-api.json |
| 强制 RLS、跨空间读写、追加写入、治理守卫与审计 | 通过（补充环境） | scripts/context_db_checks.sql，validation/agent-context-database.txt |
| 前端 TypeScript + Vite 构建 | 通过 | validation/agent-context-frontend-build.txt |
| 前端 14 项客户端测试 | 通过 | validation/agent-context-frontend-tests.txt |
| 新增 4 项 JUnit 预算单测 | 已编写，等待 Maven 执行 | ContextBudgetTest.java；同类预算边界已由实际 HTTP 场景覆盖 |
| Maven verify / 原生 PostgreSQL + Docker / Nginx | 本次未执行完成 | 之前 Maven 依赖解析受网络限制；当前环境没有可用 Maven/Docker 执行链，已接入 CI |
| 浏览器交互、真实几十 Agent 并发负载 | 未执行 | 需目标环境验收 |

9 组 HTTP 场景包含：身份绑定与 CAS、旧入口防绕过；Compact 来源及反例和预算；缓存及摘要自身来源策略；范围/置信度/深检索约束与预算不足；证据所有权；幂等反馈、一 Usage 多 Outcome、费用、人工否决；验证变化使热缓存摘要失效；Evidence 更正使必要反例退出；策略禁用及过期修订拒绝。

**结果边界**：PGlite 是补充 SQL/接口验证环境，不代替 PostgreSQL 16 的并发、锁、性能、Flyway 权限和 Docker/Nginx 部署验收。未将 V1 历史报告重新标记为本次已执行。目标环境执行根 README 的 `mvn verify` 与新增 HTTP/SQL 脚本。

## 当前产品边界

- Compact 由人工归纳和审核，不自动聚类或调用模型总结。
- 预算单位为 contextText 的完整 UTF-8 字节数，用作常见 byte-BPE 的保守上界；不是实际模型 Token 数。额外系统提示、工具定义和 Evidence 下钻由 Agent 单独预算。
- 成本为调用方自报的事件增量，含被人工否决的报告；没有每日总费用限额或供应商账单对账。
- 人工置信度与 V1 结果平滑统计分开；否决追加记录，不自动回滚历史或使来源失效，必要时手动追加 DISPUTED。
- 检索候选池和 Compact 清单有界；无自动全库深检索或跨域知识图谱。
- 同时更新策略期间，已开始的事务按其一致性快照完成；之后的新请求读取新策略。
- 原终端用户录入、证据、审核、结果和审计功能保留。Agent 旧版检索/反馈直连需升级 V2。
