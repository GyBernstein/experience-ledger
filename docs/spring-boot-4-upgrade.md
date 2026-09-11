# Spring Boot 4.1.1 升级与本地数据库启动修复

本次针对附件中的 `Connection to localhost:5432 refused / SQLSTATE 08001`。后端在 IDEA/Windows 主机启动，默认连接 localhost:5432，但此地址没有可访问的 PostgreSQL。原 Compose 将数据库仅开放在容器网络，不能直接被主机 Java 访问。Flyway 的依赖异常是此连接问题向上传播的结果。

## Windows / IDEA：直接使用新增启动配置

需要已启动 Docker Desktop（Linux containers）。在项目根目录执行：

```powershell
.\scripts\start-local.ps1 -DatabaseOnly
```

脚本执行三件事：初始化缺失的 `.env`；启动 PostgreSQL 并等待健康；将 Compose 解析后的真实密码、凭证、Space 配置写入被 Git 忽略的 `.local/application-local.properties`。不会执行 `.env` 内容，不会删除原数据库卷。

随后在 IDEA 的 Spring Boot Run Configuration 中设置：

| 项目 | 值 |
|---|---|
| JDK | 21 |
| Main class | com.example.ledger.LedgerApplication |
| Active profiles | local |
| Working directory | 项目根目录，例如 E:\ot\github\experience-ledger |

若界面没有 Active profiles 字段，在 Program arguments 添加 `--spring.profiles.active=local`。Maven Reload 后执行 `mvn clean test`，再启动应用，清除升级前 target/classes 残留。

默认数据库映射为 `127.0.0.1:15432 → 容器5432`。如端口占用：

```powershell
.\scripts\start-local.ps1 -DatabaseOnly -DatabasePort 25432
```

脚本会同步生成连接地址。不要将 `.local` 配置提交或发给其他人。修改 `.env` 后重新执行脚本更新本地配置。

应用首次成功启动并完成 Flyway 后，在另一个终端初始化 Space：

```powershell
.\scripts\initialize-local-space.ps1
```

也可直接在 PowerShell 启动后端（需要 Maven 与 JDK21）：

```powershell
.\scripts\start-local.ps1
```

这个终端保持后端运行；另开终端初始化 Space。前端开发仍为 `cd frontend; npm ci; npm run dev`。如果已用完整 Compose 启动了 application，先停止该 application，再在 IDEA 启动，避免双方占用 8080：`docker compose stop application`。

## 已有 PostgreSQL：使用自己的地址

不启用 local profile，给 IDEA 配置环境变量：

```text
DB_URL=jdbc:postgresql://你的数据库主机:5432/ledger
DB_USER=ledger_app
DB_PASSWORD=实际运行账户密码
DB_MIGRATION_USER=ledger_owner
DB_MIGRATION_PASSWORD=实际迁移账户密码
LEDGER_PRINCIPALS=实际身份配置JSON
LEDGER_WORKER_SPACES=实际Space UUID
```

需要 pgvector 扩展以及分离的迁移/运行账户，权限设置参考 scripts/init-db.sh。不要只设置 spring.datasource.url 而留下旧 spring.flyway.url；本项目以 DB_URL 同步两者。

## 完整容器部署

保留原路径：

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.frontend.yml up -d --build
```

容器内后端连接 `db:5432`。`docker-compose.local.yml` 仅用于主机 Java 调试，默认生产 Compose 不暴露 PostgreSQL 端口。

## 错误区分

| 错误 | 优先检查 |
|---|---|
| connection refused / 08001 | 服务是否启动、地址/端口是否正确、主机端口是否映射 |
| password authentication failed / 28P01 | 运行和迁移账户密码；已有卷不会因修改 .env 自动改数据库密码 |
| database does not exist / 3D000 | DB_URL 的数据库名 |
| permission denied | pgvector/建表权限、迁移账户与运行账户隔离 |
| Flyway checksum mismatch | 是否改动已执行迁移；本次没有修改 V1/V2/V3 SQL |
| 401 或登录失败 | LEDGER_PRINCIPALS 和人员 token，及 Space 初始化 |

应用新增数据库启动失败分析器，在连接类 SQLSTATE 异常下打印实际操作提示，不把连接故障伪装成启动成功，也不关闭 Flyway。

## 4.1.1 迁移范围

- Spring Boot parent 固定为 **4.1.1**；JDK21 保持。
- Web 改用 spring-boot-starter-webmvc；Flyway 使用 spring-boot-starter-flyway，保留 PostgreSQL 扩展模块。
- Jackson 全部迁移到 tools.jackson 的 Jackson 3；使用 Boot 的 Jackson 2 defaults 兼容设置保留 V1 JSON 行为，未知字段继续拒绝。
- 适配 JsonNode properties、Mapper 创建、Testcontainers 2 模块与 PostgreSQLContainer 包名。
- TestRestTemplate 使用新的测试模块与显式 AutoConfigureTestRestTemplate。
- 增加本地 profile 和连接故障提示测试；原 V1.1 前后端功能继续保留。

参考：[官方 4.1.1 文档](https://docs.spring.io/spring-boot/index.html)、[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[官方迁移说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)。

实际验证结果见 `docs/spring-boot-4-verification.md`。
