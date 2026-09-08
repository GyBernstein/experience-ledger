# Provider 与检索

## 无模型模式

默认 Enrichment 是 Noop，Embedding 是 disabled。已有人工结构不变，模型不可用不影响 Capture/Review/Verify。默认搜索 FTS+Metadata。

Enrichment 扩展实现 `ExperienceEnrichmentProvider` Bean；替代默认 Noop。输出仅作候选建议，不自动发布，不改写 provenance。Core 没有具体 LLM 厂商逻辑。

## 通用 HTTP Embedding 契约

配置：LEDGER_EMBEDDING_PROVIDER=http、LEDGER_EMBEDDING_URL、LEDGER_EMBEDDING_MODEL，可选 LEDGER_EMBEDDING_TOKEN。服务端固定 endpoint，不接受请求者提供任意 URL。

请求：

```json
{"text":"需要编码的全文","model":"my-embedding-v1"}
```

响应：

```json
{"model":"my-embedding-v1","vector":[0.1,0.2]}
```

上例数组仅示意，实际必须 **384 个有限浮点数、非零向量**。服务端校验 model 精确一致；不跟随重定向，连接/请求超时默认 20 秒。可在现有模型服务前加简单适配器；不需要 Core 依赖某供应商 SDK。

只处理 Candidate 和 Experience Version。Evidence/Episode/Outcome/Audit 均不 embedding。更换模型后调用版本 retry-embedding 重建；检索只比较同 model 的向量，未重建数据仍通过 FTS 可查。维数变化必须显式 migration。

## 检索顺序与解释

各召回分支先带 Space、domain/type、validAt/knownAt、applicabilityFilter(JSONB containment)，避免过期/其他空间数据挤占结果。FTS、向量、Context 引用三路取候选并合并，再按可配置权重排序。

- FTS：PostgreSQL simple dictionary，中文附加 Han 单字/双字片段，GIN 索引；查询词用 OR 召回。适合中英混合技术关键词，无专业中文分词或同义词理解。
- Vector：pgvector cosine，HNSW 索引；相似度截到 [0,1]，同模型比较。
- Metadata：domain/experienceType/applicabilityFilter 精确过滤；context 的 `PROJECT` 等 key 可与 ContextRef 匹配。
- Applicability：同名标量相等、候选值数组成员匹配、数值 `xxxMin/xxxMax` 与 query.context.xxx 比较。字段缺失记 unknown，不自动断言适用。不解析版本比较字符串或运行任意表达式。
- Constraints 只解释形成经验时的限制，不当作可复用范围规则。

归一化分数：lexical=ts_rank_cd(...,32)，semantic=max(0,1-cosine_distance)，context=匹配引用比例，applicability=(匹配数-不匹配数)/规则数。ConfidenceCalculator 提供可重建的平滑 Outcome 摘要。所有组合权重在 application.yml 配置。

每路默认最多 200 个候选，合并后排序再取 limit（1–100）。这是轻量 V1 的有限候选排序；大规模时可增加候选池，并用真实查询评估召回。HNSW 是近似检索，不声称精确全局最优排名。

返回 whyMatched 包含各项分数、规则匹配/不匹配/未知和权重；同时返回 Claims、Evidence links、当前 Usage/Outcome 统计、派生 Confidence、CONTRADICTS 关系。Agent 应结合这些信息判断是否复用。

Confidence `(1+success+0.5*partial)/(2+success+partial+failure)` 仅是可解释排序摘要，**不是企业事实或独立试验成功概率**，多个 Outcome 可来自同一次 Usage。

## 来源

依赖基线核对参考 [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html) 和 [pgvector 官方仓库](https://github.com/pgvector/pgvector)。运行版本已在 pom.xml / Compose 中固定。
