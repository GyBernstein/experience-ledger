# Spring Boot 4.1.1 实际验证

日期：2026-09-11。本文件为当前交付版本验收；原 V1 / V1.1 报告作为升级前历史保留。

| 验证 | 实际结果 |
|---|---|
| Maven 主源码与测试源码编译、可执行 JAR 打包 | 通过，JAR Manifest 为 Spring-Boot-Version: 4.1.1 |
| 单元测试 | 14 项全部通过：6 项领域规则、4 项预算、4 项本地配置/连接诊断 |
| Maven verify 集成测试 | 16 项中 15 项通过、1 项并发测试因 WASM 环境跳过；无失败 |
| Flyway | 在空数据库上实际执行 V1、V2、V3 成功；SQL 文件与上一版未改动 |
| 真实应用 HTTP Agent Gateway | 9 组场景通过，含策略 CAS、范围、来源、预算、缓存失效、反馈幂等和人工复核 |
| V1 完整业务示例 | 通过，包括 V1→V2 替代链、证据、检索和结果反馈 |
| 前端真实 API 契约 | 通过：证据、幂等采集、审核、修订冲突、发布、检索、历史、Usage、两个 Outcome、证据更正、审计 |
| 前端客户端单元测试 | 14 项通过；本次前端源码未修改 |
| 数据库不可达时的启动提示 | 用实际打包 JAR 连接拒绝端口，非零退出并显示 start-local.ps1/local profile 指引 |
| 默认 local profile | runtime/Flyway 同步使用 127.0.0.1:15432；运行/迁移账户分离 |
| 本地配置导入 | 自定义端口 25432 及转义密码加载通过，测试使用独立临时文件 |
| Windows PowerShell / Docker Desktop 启动脚本 | 已实现并检查源码，本环境未实际执行 |
| 原生 PostgreSQL 16、Docker/Nginx 和并发负载 | 需要目标环境/CI 验收，未用 WASM 结果代替 |

执行环境为 JDK21 + Maven3.9.11；数据库为 PGlite（PostgreSQL WASM）+ pgvector。应用使用非超级用户、NOBYPASSRLS 角色，集成测试实际执行 Flyway。Maven verify 完成后增加了独立配置导入单测，并再次执行 Maven package，最终单测总数为 14。

记录位于 `docs/validation/boot4/`。`maven-verify-summary.txt` 保留当次集成测试执行摘要；最终单测以各测试类报告及 `maven-package.txt` 为准。

## 在目标环境完成验收

```bash
mvn clean verify
```

默认 Testcontainers 使用 PostgreSQL16 + pgvector，执行全部集成测试（包括当前补充环境跳过的并发测试）。或者：

```bash
docker compose --profile test run --rm tests
```

Windows 本地启动详见 `spring-boot-4-upgrade.md`。已有数据卷的密码不会因更改 `.env` 自动变化；脚本不会删除数据或自动改数据库角色密码。
