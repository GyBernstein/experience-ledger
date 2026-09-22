# 判断规则增量包：本地覆盖说明

## 适用基线

基于上次提供的 `experience-ledger-v1.1-springboot-4.1.1.zip`。沿用其 Spring Boot 4.1.1、JDK 21 和 Maven/npm 1.0.0；产品名称沿用 V1.1，本包不抬高版本号。若你本地另有 0.5/1.1 命名，不需要因为本补丁改版本号。

ZIP 的 `experience-ledger/` 目录只包含新增或修改的文件；将**该目录里的内容**覆盖到你现有的项目根目录，不要再嵌套一层 experience-ledger。不是全量项目，不要单独运行解压目录。

`CHANGES.json` 列出每个覆盖文件的基线与目标 SHA256。`check-overlay.py` 为只读检查器，在覆盖前执行：

```bash
python check-overlay.py /你的本地/experience-ledger
```

若文件与基线不同，它会列出差异并返回非零退出码。这可能是你的本地修改或不同基线，需要逐文件合并；检查器不会写入或删除本地文件。已与目标一致的文件标记为 already-applied。

## 覆盖与启动

1. 停止应用，备份数据库，并保留当前代码副本/本地 Git 提交。覆盖清单中的同路径文件，保留 `.env`、本地连接配置和数据卷。
2. 在项目根目录执行 `mvn clean package -DskipTests`；开发机有 Docker 时可执行 `mvn verify` 运行原生 PostgreSQL 集成测试。
3. 沿用原启动方式启动后端。Flyway 自动执行新增 `V4__judgment_rules.sql`；**不要修改、删除或重跑旧迁移，不要禁用 Flyway**。
4. 前端源码方式：进入 `frontend`，执行 `npm ci`、`npm run build` 或 `npm run dev`。包内也含更新后的 `frontend/dist`，静态部署时应整体替换所部署的 dist；后端 API 仍需独立运行。
5. Docker Compose 方式，在项目根目录执行：

```bash
docker compose -f docker-compose.yml -f docker-compose.frontend.yml up -d --build
```

不需要 `down -v`，不需要重新建库。Windows 本地数据库和 IDEA 继续使用 `scripts/start-local.ps1 -DatabaseOnly` 与 `local` profile；已有空间无需再次初始化。

本补丁不含依赖目录、数据库文件、JAR、凭证或本地配置，不改变已有密码或端口。

## 数据迁移的实际影响

- V1/V2/V3 数据和 Flyway 校验值保持不变。V4 增加四张 sidecar 表、约束和审计。
- 原生来源优先依据第一版本对应 VERIFIED Candidate 的认证采集身份。找不到时使用 Family 创建身份保守推断，标记 LEGACY_FALLBACK；这不是重新证明来源。
- 旧的人类记录**不会自动获得跨侧共享授权**。因此 Agent 检索结果可能减少。到「经验检索 → 经验详情 → 审核跨侧共享」逐条审核，或调用新增 reuse API。人员治理检索仍可查看旧记录。
- 所有旧 Compact 的源指纹格式增加了归属与共享状态，因此会失效，需要在共享审核后新建摘要并审核；旧记录留作审计，不覆盖它们。
- 分享针对某个版本。新版本默认没有旧版本的跨侧授权，需重新审核。
- Flyway 迁移用户必须是已有表的 owner（标准部署为 ledger_owner）。迁移在一个 PostgreSQL 事务内短暂关闭 exp_space 的 FORCE，仅让 owner 枚举空间；运行角色的 RLS 策略保持启用，事务持有表锁，提交前恢复 FORCE。其他业务表始终 FORCE RLS。不要把 V4 拆成逐句自动提交脚本。
- 如迁移报 owner 权限问题，应使用既有数据库迁移 owner 凭证；不要给运行用户授予 BYPASSRLS/superuser。

## 第一次使用

进入「人的判断库 → 沉淀一条判断」。若无法说明它将改变什么未来决策，可以直接“不记，离开”。通过保留检查后填写规则、关键问题、约束、trade-off、检验方法和边界；保存后人工确认发布。

在规则详情选择“供参考”或“已验证复用”，说明审核依据。后者还需有效测试/回放 Evidence。到「上下文供给」使用 Agent 的策略预览；策略要求高验证分或支持证据时，要先完成人工验证/证据补充，作者自评不能替代它们。

规则详情可记录使用与结果、发起后继版本、撤回共享。自然语言边界会随上下文供给；需要硬性禁止跨型号/负载/版本使用时，填写结构化适用条件。

## 复验

```bash
mvn verify
cd frontend
npm test
npm run build
# 对隔离测试空间运行；会创建记录及测试策略
node --import tsx tests/judgment-api-smoke.ts
```

HTTP 检查需要环境变量 `LEDGER_URL`、HUMAN 身份的 `LEDGER_TOKEN`。同时提供 `LEDGER_AGENT_TOKEN` / `LEDGER_AGENT_ID` 可检查真实 Agent 身份；默认测试 Agent ID 为 demo-agent。脚本会更改该测试身份的策略绑定，勿指向生产身份。CI 已接入这个检查。

## 本次未实现

完整 Transfer 候选/回放编排、Laya/LLM 决策 Provider、自动生成跨轨经验、自动触发重审、精确模型 tokenizer、多层 L0-L3 packet。详见 `docs/judgment-design.md`，这些不是当前补丁已完成的功能。
