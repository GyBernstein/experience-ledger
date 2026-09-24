# 草稿审核时报相似经验 SQL 错误

AI 草稿生成成功后，审核页面会读取相似经验。该查询依赖 Flyway `V7__problem_groups.sql` 创建的 `exp_problem_group` 与 `exp_problem_group_event`。如果只覆盖了应用文件、未将 V7 迁移放进运行包，或应用和迁移连接不同的数据库，审核页面此前会把该查询的异常误记为“草稿生成失败”。

在应用使用的数据库中检查：

```sql
select current_database(), current_schema(), current_setting('search_path');
select installed_rank, version, description, success
from flyway_schema_history order by installed_rank desc limit 8;
select to_regclass('exp_problem_group') as problem_group,
       to_regclass('exp_problem_group_event') as problem_group_event;
```

如果 V7 未成功执行或表不存在，确认本次构建包含 `src/main/resources/db/migration/V7__problem_groups.sql`，并用配置的迁移账户 `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` 启动当前后端，使 Flyway 执行迁移。`DB_URL` 同时供业务连接和 Flyway 使用；若有额外环境覆盖，请核对实际指向的数据库和 schema。不要手工修改 `flyway_schema_history`，也不要重新执行已成功的迁移。迁移结束后，重新打开原草稿即可看到关联推荐。

本修复在两张表缺失时仍允许生成、修改和独立发布草稿，并在审核页提示迁移未完成。归组及关联发布仍依赖 V7；其他 SQL 故障会保留原始异常并写入服务端日志，避免被误报为模型故障。
