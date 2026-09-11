# 验证记录与交付状态

状态：**代码实现与补充环境闭环验证完成，待真实 PostgreSQL/pgvector 环境最终验收。** 不将补充环境等同于 Frozen §73 的原生数据库验收。

| 检查 | 实际结果 |
|---|---|
| Java 21 编译与 Spring Boot JAR 打包 | 通过 |
| DomainTest | 6 项通过，0 失败 |
| LedgerIT 补充环境 | 16 项中 15 项通过，1 项明确跳过，0 失败 |
| 原生 PostgreSQL 并发 Supersession | 未执行；本环境无 Docker / 原生 PostgreSQL 服务 |
| 独立 JAR + seed_demo.py | 通过，checks=PASS，默认 FTS_METADATA |
| Docker Compose 全栈启动 | 本环境未执行；已提供 CI 验证步骤 |
| GitHub Actions | 已提供 workflow；尚未在远端运行 |

## 补充环境范围

使用 PGlite 0.5.8 的 PostgreSQL 18.3 WASM 内核与 pgvector，通过 PostgreSQL wire protocol 和真实 JDBC 执行测试，没有使用 H2。SQL 约束、RLS、FTS、向量距离、事务回滚、审核发布和历史查询均实际执行。

PGlite 连接复用与原生 PostgreSQL 不同，因此仅补充环境启用 `preferQueryMode=simple`、禁用 JDBC server prepare，并关闭 Flyway transaction advisory lock。测试中的 `LEDGER_IT_WASM=true` 仅用于这次补充验证，正常 Testcontainers/Compose 测试不要设置它。并发 Supersession 测试在补充环境明确跳过，不能据此声称原生锁竞争已经通过。

独立 JAR 验证通过只读/写业务权限的非超级用户执行。测试脚本创建 Evidence、Episode、Observed/Derived Claims、成功和失败 Experience、Usage、两次 Outcome、V1→V2、历史查询和 CONTRADICTS。操作摘要见 validation/demo-smoke.json。

已修复 HTTP 请求显式 null 可选 JSON 字段导致采集失败的问题，并重新通过测试。

## 最终验收入口

```bash
mvn verify
# 或完全用容器：
docker compose --profile test run --rm tests
```

正常测试使用 PostgreSQL 16 + pgvector 0.8.2。应看到 6 项 DomainTest、16 项 LedgerIT 全部通过，且 0 skipped。随后启动 Compose 并运行 seed_demo.py，核对 checks=PASS。

`.github/workflows/verify.yml` 已串联上述原生测试、Compose 部署、示例 Space 初始化与示例脚本。推送 main 后可据 CI 结果补齐验收记录；首次失败时必须修复后重跑，不能忽略门槛。

## 交付不包含

复杂 RBAC、具体业务系统适配器、真实 LLM 服务实现、自动审核、Ontology/知识图谱。通用 HTTP Embedding Provider 需要接入符合约定的模型服务；测试向量仅用于验证检索管线，不代表语义质量评估。

## 后续前端交付

按项目所有者追加要求，已增加 React 前端（包括终端用户录入）。本文件上述测试记录对应原后端交付；本次前端构建与客户端 API 验证记录见 `frontend-verification.md`。
