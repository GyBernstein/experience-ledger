# 架构

一个 Spring Boot 进程、一个 PostgreSQL 数据库。对象存储和模型服务均为可选外部能力。

## 包职责

| 包 | 职责 |
|---|---|
| api | REST DTO、验证、Bearer 身份、统一错误、traceId |
| application | 采集/审核/发布等用例，处理任务 |
| domain | Actor、Candidate 状态机、Claim 来源、Confidence 算法 |
| retrieval | 混合召回、双时态过滤、适用性与结果组装 |
| provider | Enrichment、Embedding 抽象及默认实现 |
| infrastructure | JDBC、JSON 转换、事务级 Space/Actor 注入 |

选择显式 JDBC SQL，便于核对 PostgreSQL 锁、JSONB、FTS、pgvector 和时态条件。领域规则不依赖具体模型厂商。

## 事务

Db.with 在同一 TransactionTemplate 中设置事务级 `ledger.space_id/actor_type/actor_id/reason/trace_id`。连接归还池后设置随事务结束失效。所有 Gateway 用例显式带 ActorContext 和 Space 参数，数据库同时强制 RLS。

- Capture：Candidate 幂等插入、可选 Episode/Evidence、处理 job 一起提交。外部 Provider 此时不执行。
- Promotion：锁 Candidate，核对 revision；锁 Family，核对链尾；创建 Version、Claims、证据链接、Context、Episode 链接；更新 Candidate；追加 Audit 和 embedding job；任意失败全部回滚。
- Supersession：V1 trigger 在 Family 锁内分配/校验顺序、关闭旧版本。deferred trigger 在 commit 时验证后继存在且 recorded_at 与 invalidated_at 完全相等。
- Evidence correction：锁旧证据，新增证据，再关闭旧证据；旧快照和旧 Claim 链接保留。
- Usage+immediateOutcome：同一事务；以后可追加多个 Outcome。
- Merge：验证同 Space 的目标，更新 Candidate 归属并写审计；不改已发布 Version 内容。

## 数据库账户

Compose 的 postgres 仅作初始化管理员。init-db.sh 创建 pgvector 扩展、ledger_owner 和 ledger_app。Flyway 使用 ledger_owner；业务连接使用 ledger_app，无超级用户、BYPASSRLS、表所有权或 CREATE 权限。

ledger_app 获得表的 SELECT/INSERT/UPDATE，DELETE/TRUNCATE 未授予；数据库 trigger 仍明确禁止核心对象 UPDATE/DELETE/TRUNCATE 中对应不可变操作。管理员具备修改 schema 的能力，不属于 append-only 对抗恶意 DBA 的威胁模型。

多 Space：用 scripts/create-space.sql 初始化 Space，再为凭证绑定该 ID，同时把 ID 加入 `LEDGER_WORKER_SPACES`（逗号分隔）。V1 不提供任意跨 Space 查询或管理 API。

## 运行与审计

数据库 trigger 写核心操作审计，确保直接 SQL 生命周期变更也必须满足 ActorContext 并产生记录。审计避免存完整请求，使用目标 ID、reason、traceId、事务 ID；已发布内容和终态 Candidate 可沿引用回溯。

Spring Boot 使用 JSON structured logging。每个请求服务端生成 traceId，在响应 `X-Trace-Id`、错误体和数据库 Audit 中一致使用。日志不记录 Bearer token 或完整采集内容。

指标：`ledger.search.latency`、`ledger.embedding.failures`（query/background）、`ledger.candidate.processing.failures`、`ledger.promotion.failures`。通过需治理身份的 `/actuator/metrics` 查看；`/actuator/health` 公开且不暴露细节。

## 恢复

数据库卷持久化，使用现有 PostgreSQL 备份方案备份整个库。Migration 和应用发布先在独立测试库执行。运行中的 job 超过 lease 会重新领取；Provider 失败不会回滚已提交 Candidate/Version。无消息中间件。
