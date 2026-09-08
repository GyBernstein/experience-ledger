# 双时态：具体例子

假设 5 月 15 日总结出经验 V1，认为它从 3 月 1 日适用；6 月 20 日重新验证后发布 V2，将适用范围收窄，并认为新范围同样从 3 月 1 日适用。

| 版本 | valid_from | valid_to | recorded_at | invalidated_at | 当前 status |
|---|---|---|---|---|---|
| V1 | 3 月 1 日 | NULL | 5 月 15 日 | 6 月 20 日 | SUPERSEDED |
| V2 | 3 月 1 日 | NULL | 6 月 20 日 | NULL | VERIFIED |

`validAt` 问业务时间适用什么；`knownAt` 问在当时已经接受哪种认知。

| 查询 | 结果 | 解释 |
|---|---|---|
| validAt=4 月 1 日，knownAt=4 月 1 日 | 无结果 | 当时尚未总结入库 |
| validAt=4 月 1 日，knownAt=5 月 20 日 | V1 | 5 月时接受的回溯性认知 |
| validAt=4 月 1 日，knownAt=7 月 1 日 | V2 | 今天对 4 月业务的修正认识 |
| knownAt 恰等于 6 月 20 日替代时间 | V2 | recorded time 使用半开区间 |

两个维度的统一条件：

```sql
valid_from <= :validAt
AND (valid_to IS NULL OR :validAt < valid_to)
AND recorded_at <= :knownAt
AND (invalidated_at IS NULL OR :knownAt < invalidated_at)
```

**历史查询不能追加 `status='VERIFIED'`**，否则已经 SUPERSEDED/INVALIDATED 的历史认知会丢失。当前查询两个时间默认现在，结合状态与 invalidated_at 的数据库等价约束可得当前有效版本。

请求示例：

```text
GET /api/v1/experiences/{familyId}?validAt=2026-04-01T00:00:00Z&knownAt=2026-05-20T00:00:00Z
```

参数可独立省略，省略的一个默认当前时刻。`history` 返回链上的所有版本，不作有效性过滤。

## 时间由谁决定

validFrom/validTo 是审核后的业务语义；recordedAt/invalidatedAt 由数据库时间赋值，不接受客户端回填。Supersession 在锁 Family 后取时间，防止长事务早于已提交后继的 recordedAt。

统一使用 timestamptz 与 ISO-8601 带时区入参。数据库微秒精度；测试用一微秒验证边界。Episode.occurred_at 表示实际发生的时间，独立于经验总结和适用时间。

## V1 边界

不做时间区间自动拆分、不做未来定时激活、不做旧版本复活。V2 替代的是当前认知的整体版本；若它的 valid interval 未覆盖某个历史业务日期，按当前认知查询该日期可以无结果，并不会自动回退到已被替代的 V1。

历史查询返回的版本内容是当时认知，但 `status`、证据更正状态、Usage/Outcome 统计和轻关系是当前信息，结果显式带 `lifecycleStatusAsOf/statisticsAsOf/evidenceLifecycleAsOf=CURRENT`。V1 不声称对统计与关系也实现双时态。
