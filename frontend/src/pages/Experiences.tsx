import { useEffect, useState } from "react";
import {
  Search,
  ArrowUpRight,
  ArrowLeft,
  SlidersHorizontal,
  RefreshCw,
  History,
  Plus,
  Link2,
  ShieldCheck,
} from "lucide-react";
import { LedgerApi, query } from "../api";
import type { Experience, Row, SearchResult } from "../types";
import { experienceTypes, relationTypes } from "../types";
import { instant, objectJson, requireId } from "../domain";
import {
  Badge,
  date,
  Empty,
  ErrorBox,
  Field,
  go,
  Id,
  JsonView,
  Loading,
  PageTitle,
  SelectOptions,
  Success,
  useAction,
  useLoad,
} from "../components/ui";
function ExperienceCard({ item }: { item: Experience }) {
  return (
    <article className="experience-card">
      <div className="card-top">
        <Badge value={item.status} />
        <span>V{item.versionNo}</span>
        <ArrowUpRight className="push-right" size={19} />
      </div>
      <h2>
        <a
          href={`#/experiences/${item.experienceId}?version=${item.versionId}`}
        >
          {item.title}
        </a>
      </h2>
      <p className="card-summary">{item.summary}</p>
      <div className="lesson-preview">{item.lesson}</div>
      <div className="card-meta">
        <span>{item.keyClaims.length} 条结论</span>
        <span>{item.keyEvidence.length} 条证据</span>
        <span>{item.usageStats.usage_count} 次使用</span>
      </div>
      {item.contradictionWarnings.length > 0 && (
        <div className="warning-text">
          存在 {item.contradictionWarnings.length} 条矛盾关系，请核对后复用
        </div>
      )}
      <div className="card-bottom">
        <span>记录于 {date(item.recordedAt)}</span>
        {item.score !== undefined && <b>匹配分 {item.score.toFixed(3)}</b>}
      </div>
      {item.whyMatched && (
        <JsonView value={item.whyMatched} title="为什么匹配" />
      )}
    </article>
  );
}
export function SearchPage({ api }: { api: LedgerApi }) {
  const [text, setText] = useState(""),
    [domain, setDomain] = useState(""),
    [type, setType] = useState(""),
    [validAt, setValid] = useState(""),
    [knownAt, setKnown] = useState(""),
    [context, setContext] = useState("{}"),
    [filter, setFilter] = useState("{}"),
    [limit, setLimit] = useState(20),
    [lookup, setLookup] = useState("");
  const [request, setRequest] = useState<Row>({ query: "", limit: 20 }),
    task = useAction();
  const found = useLoad(
    (s) => api.request<SearchResult>("/experiences/search", request, s),
    [api, request],
  );
  return (
    <>
      <PageTitle
        eyebrow="LIBRARY / 03"
        title="找到可以复用的经验"
        actions={
          <a className="button primary" href="#/capture">
            <Plus size={17} />
            采集经验
          </a>
        }
      >
        按问题检索，并结合适用范围、依据与历史判断是否采用。
      </PageTitle>
      <form
        className="panel search-panel"
        onSubmit={(e) => {
          e.preventDefault();
          void task.run(async () =>
            setRequest({
              query: text,
              domain: domain || undefined,
              experienceType: type || undefined,
              validAt: validAt ? instant(validAt) : undefined,
              knownAt: knownAt ? instant(knownAt) : undefined,
              context: objectJson(context, "当前上下文"),
              applicabilityFilter: objectJson(filter, "适用范围筛选"),
              limit,
            }),
          );
        }}
      >
        <div className="search-box">
          <Search size={22} />
          <input
            aria-label="检索经验"
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={4000}
            placeholder="描述你的问题，例如：候选剪枝导致利用率下降"
          />
          <button className="primary" disabled={found.loading}>
            检索经验
          </button>
        </div>
        <div className="search-filters">
          <Field label="领域">
            <input
              value={domain}
              onChange={(e) => setDomain(e.target.value)}
              placeholder="全部领域"
            />
          </Field>
          <Field label="经验类型">
            <select value={type} onChange={(e) => setType(e.target.value)}>
              <SelectOptions values={experienceTypes} blank="全部类型" />
            </select>
          </Field>
          <Field label="返回条数">
            <select
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
            >
              <option>10</option>
              <option>20</option>
              <option>50</option>
              <option>100</option>
            </select>
          </Field>
        </div>
        <details className="advanced">
          <summary>
            <SlidersHorizontal size={16} />
            上下文与历史时点
          </summary>
          <div className="form-grid">
            <Field label="业务有效时点（validAt）" hint="留空使用当前时间。">
              <input
                type="datetime-local"
                step="1"
                value={validAt}
                onChange={(e) => setValid(e.target.value)}
              />
            </Field>
            <Field
              label="系统认知时点（knownAt）"
              hint="留空使用当前时间，可与业务时点独立指定。"
            >
              <input
                type="datetime-local"
                step="1"
                value={knownAt}
                onChange={(e) => setKnown(e.target.value)}
              />
            </Field>
            <Field
              label="当前上下文 JSON"
              hint='例如 {"length":800,"material":"A","PROJECT":"demo"}'
            >
              <textarea
                className="code-input"
                value={context}
                onChange={(e) => setContext(e.target.value)}
              />
            </Field>
            <Field label="适用范围筛选 JSON" hint="使用 JSON 包含匹配。">
              <textarea
                className="code-input"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
            </Field>
          </div>
        </details>
      </form>
      <ErrorBox error={task.error || found.error} />
      <div className="results-heading">
        <div>
          <h2>
            检索结果{" "}
            {found.data && (
              <span className="count">{found.data.results.length}</span>
            )}
          </h2>
          <span className="micro">
            {found.data
              ? `${found.data.mode} · 候选池 ${found.data.candidateCount} 条`
              : "数据来自当前凭证所属空间"}
          </span>
        </div>
        <button
          className="text-button"
          disabled={found.loading}
          onClick={found.reload}
        >
          <RefreshCw size={16} />
          刷新结果
        </button>
      </div>
      {found.loading ? (
        <Loading />
      ) : found.data?.results.length ? (
        <>
          <div className="experience-grid">
            {found.data.results.map((x) => (
              <ExperienceCard item={x} key={x.versionId} />
            ))}
          </div>
          <p className="micro">
            有效时点 {date(found.data.validAt)} · 认知时点{" "}
            {date(found.data.knownAt)}。结果最多 {request.limit}{" "}
            条；请缩小筛选以查找更多记录。
          </p>
        </>
      ) : (
        !found.error && (
          <Empty title="还没有匹配的经验">
            尝试减少关键词、调整领域，或清空关键词浏览已发布记录。
          </Empty>
        )
      )}
      <form
        className="panel direct-lookup"
        onSubmit={(e) => {
          e.preventDefault();
          void task.run(async () =>
            go(`/experiences/${requireId(lookup, "Family ID")}`),
          );
        }}
      >
        <div>
          <h3>按经验 ID 查看历史</h3>
          <p className="micro">已撤销或已替代的记录仍可从版本链追溯。</p>
        </div>
        <input
          aria-label="经验 Family UUID"
          placeholder="经验 Family UUID"
          required
          value={lookup}
          onChange={(e) => setLookup(e.target.value)}
        />
        <button>打开版本链</button>
      </form>
    </>
  );
}
export function ExperiencePage({
  api,
  id,
  initialVersion,
}: {
  api: LedgerApi;
  id: string;
  initialVersion?: string;
}) {
  const history = useLoad(
    (s) =>
      api.request<Experience[]>(
        `/experiences/${requireId(id, "Family ID")}/history`,
        undefined,
        s,
      ),
    [api, id],
  );
  const [selected, setSelected] = useState<Experience>(),
    [valid, setValid] = useState(""),
    [known, setKnown] = useState(""),
    [timeQuery, setTimeQuery] = useState(false),
    [similar, setSimilar] = useState<SearchResult>();
  const task = useAction();
  useEffect(() => {
    if (history.data) {
      setSelected(
        history.data.find((x) => x.versionId === initialVersion) ||
          history.data.at(-1),
      );
      setTimeQuery(false);
    }
  }, [history.data, initialVersion]);
  return (
    <>
      <PageTitle
        eyebrow="EXPERIENCE / VERSION HISTORY"
        title="经验与演进"
        actions={
          <a className="button" href="#/search">
            <ArrowLeft size={16} />
            返回检索
          </a>
        }
      />
      <ErrorBox error={history.error || task.error} />
      {history.loading ? (
        <Loading />
      ) : (
        selected && (
          <>
            <div className="record-strip">
              <History size={18} />
              <span>{history.data?.length} 个版本</span>
              <Id value={id} />
              <button
                className="text-button push-right"
                onClick={history.reload}
              >
                刷新版本链
              </button>
            </div>
            <div className="detail-layout">
              <aside className="stack">
                <div className="panel timeline">
                  <h3>版本历史</h3>
                  {history.data
                    ?.slice()
                    .reverse()
                    .map((x) => (
                      <button
                        key={x.versionId}
                        className={`version-item ${selected.versionId === x.versionId ? "selected" : ""}`}
                        onClick={() => {
                          setSelected(x);
                          setTimeQuery(false);
                        }}
                      >
                        <div>
                          <b>V{x.versionNo}</b>
                          <Badge value={x.status} />
                        </div>
                        <span>{x.title}</span>
                        <small>{date(x.recordedAt)}</small>
                      </button>
                    ))}
                </div>
                <form
                  className="panel"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void task.run(async () => {
                      setSelected(
                        await api.request<Experience>(
                          `/experiences/${id}${query({ validAt: valid ? instant(valid) : undefined, knownAt: known ? instant(known) : undefined })}`,
                        ),
                      );
                      setTimeQuery(true);
                    });
                  }}
                >
                  <h3>回到一个时点</h3>
                  <Field label="业务有效时间">
                    <input
                      type="datetime-local"
                      step="1"
                      value={valid}
                      onChange={(e) => setValid(e.target.value)}
                    />
                  </Field>
                  <Field label="系统认知时间">
                    <input
                      type="datetime-local"
                      step="1"
                      value={known}
                      onChange={(e) => setKnown(e.target.value)}
                    />
                  </Field>
                  <button disabled={task.busy}>查询当时的经验</button>
                </form>
              </aside>
              <div className="stack">
                <ExperienceContent item={selected} timeQuery={timeQuery} />
                <ProblemGroupPanel api={api} familyId={id} />
                <VersionActions
                  key={selected.versionId}
                  api={api}
                  item={selected}
                  refresh={history.reload}
                />
                <div className="panel">
                  <div className="section-heading">
                    <h2>相似经验</h2>
                    <button
                      disabled={task.busy}
                      onClick={() =>
                        void task.run(async () =>
                          setSimilar(
                            await api.request<SearchResult>(
                              `/experiences/${id}/similar`,
                              {},
                            ),
                          ),
                        )
                      }
                    >
                      查询相似
                    </button>
                  </div>
                  <p className="micro">根据本经验的当前有效版本查询。</p>
                  {similar &&
                    (similar.results.length ? (
                      <div className="stack">
                        {similar.results.map((x) => (
                          <a
                            className="related-row"
                            key={x.versionId}
                            href={`#/experiences/${x.experienceId}?version=${x.versionId}`}
                          >
                            {x.title}
                            <ArrowUpRight size={16} />
                          </a>
                        ))}
                      </div>
                    ) : (
                      <p>没有找到其他相似经验。</p>
                    ))}
                </div>
              </div>
            </div>
          </>
        )
      )}
    </>
  );
}

function ProblemGroupPanel({ api, familyId }: { api: LedgerApi; familyId: string }) {
  const group = useLoad<Row>((s) => api.v2(`/problem-groups/by-family/${familyId}`, undefined, s), [api, familyId]);
  const suggested = useLoad<Row[]>((s) => api.v2(`/problem-groups/suggestions/${familyId}`, undefined, s), [api, familyId]);
  const [reference, setReference] = useState("");
  const [reason, setReason] = useState("同一问题下存在独立的排查案例");
  const task = useAction();
  const members = (group.data?.members || []) as Row[];
  return <div className="panel problem-group-panel">
    <h2>同一问题的不同原因</h2>
    <ErrorBox error={group.error || suggested.error || task.error} /><Success>{task.message}</Success>
    {group.loading ? <Loading /> : members.length ? <>
      <p>{group.data?.problem}</p>
      {members.map((member) => <div key={member.family_id} className="problem-group-member">
        <a href={`#/experiences/${member.family_id}`}>{member.title}</a>
        <p>根因：{member.root_cause || "尚未确认"} · {member.relation === "ALTERNATIVE_CAUSE" ? "另一种原因" : member.relation === "SAME_CAUSE_CASE" ? "相同原因的独立案例" : "首个案例"} · 证据 {member.evidence_count ?? 0} 条</p>
        <p className="micro">适用范围：{JSON.stringify(member.applicability_json || {})}</p>
      </div>)}
      <p className="micro">归组是排查导航；每条经验的结论和证据仍可独立核对。</p>
    </> : <p className="micro">尚未归组；如果发现其他原因导致相同问题，可以把新经验归入这个问题。</p>}
    <details><summary>补充归组或修正错误关联</summary>
      <Field label="可能相关的经验"><select value={reference} onChange={(e) => setReference(e.target.value)}><option value="">选择系统推荐的案例</option>{suggested.data?.map((item) => <option key={item.family_id} value={item.family_id}>{item.title} · 根因：{item.root_cause || "待确认"}</option>)}</select></Field>
      <Field label="归组理由"><input value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
      <button type="button" disabled={task.busy || !reference || !reason.trim() || Boolean(group.data?.id)} onClick={() => void task.run(async () => { await api.v2("/problem-groups/attach", { familyId, referenceFamilyId: reference, relation: "ALTERNATIVE_CAUSE", reason }); group.reload(); setReference(""); task.setMessage("已归入问题组。"); })}>关联为同一问题的不同原因</button>
      {group.data?.id && <button type="button" disabled={task.busy || !reason.trim()} onClick={() => void task.run(async () => { await api.v2(`/problem-groups/${group.data?.id}/members/${familyId}/unlink`, { reason }); group.reload(); task.setMessage("关联已撤销，审计事件已保留。"); })}>撤销当前归组</button>}
    </details>
  </div>;
}
function ExperienceContent({
  item,
  timeQuery,
}: {
  item: Experience;
  timeQuery: boolean;
}) {
  return (
    <article className="panel experience-detail">
      <div className="section-heading">
        <div className="actions">
          <Badge value={item.status} />
          <span className="version-label">
            VERSION {String(item.versionNo).padStart(2, "0")}
          </span>
        </div>
        <ShieldCheck size={23} className="accent" />
      </div>
      <h1>{item.title}</h1>
      <p className="lead preserve">{item.summary}</p>
      <div className="lesson">
        <span className="eyebrow">经验结论</span>
        <p className="preserve">{item.lesson}</p>
      </div>
      <div className="narrative-grid">
        {(["problem", "decision", "action", "outcomeSummary"] as const).map(
          (name, i) =>
            item[name] && (
              <section key={name}>
                <h3>{["问题", "决策", "行动", "结果"][i]}</h3>
                <p className="preserve">{item[name]}</p>
              </section>
            ),
        )}
      </div>
      {item.contradictionWarnings.length > 0 && (
        <div className="notice warning">
          <div>
            <b>存在矛盾关系，采用前请核对</b>
            {item.contradictionWarnings.map((r, i) => (
              <p key={r.id || i}>
                关联版本{" "}
                <Id
                  value={
                    r.from_version_id === item.versionId
                      ? r.to_version_id
                      : r.from_version_id
                  }
                />
              </p>
            ))}
            <JsonView value={item.contradictionWarnings} title="查看关系详情" />
          </div>
        </div>
      )}
      <section>
        <h2>
          关键结论 <span className="count">{item.keyClaims.length}</span>
        </h2>
        {item.keyClaims.map((c, i) => (
          <div className="read-claim" key={c.id || i}>
            <div className="actions">
              <span className="micro">{c.claim_type}</span>
              <Badge value={c.origin_type} />
            </div>
            <p className="preserve">{c.content}</p>
            {c.derivation_method && (
              <p className="micro">推导方法：{c.derivation_method}</p>
            )}
            {c.evidenceLinks?.map((e: Row, j: number) => (
              <a
                key={j}
                className="evidence-chip"
                href={`#/evidence?id=${e.evidence_id}`}
              >
                {e.support_type} · {e.evidence_id}
              </a>
            ))}
          </div>
        ))}
      </section>
      <section>
        <h2>
          关联证据 <span className="count">{item.keyEvidence.length}</span>
        </h2>
        {item.keyEvidence.length ? (
          item.keyEvidence.map((e) => (
            <div className="evidence-row" key={e.id}>
              <div>
                <a href={`#/evidence?id=${e.id}`}>
                  {e.evidence_type} · {e.source_ref || e.id}
                </a>
                <p className="micro">
                  观察于 {date(e.observed_at)} · 可靠度 {e.reliability} ·{" "}
                  {e.status}
                </p>
              </div>
              <ArrowUpRight size={16} />
            </div>
          ))
        ) : (
          <p className="muted">此版本没有关联证据，请结合 Claim 来源判断。</p>
        )}
      </section>
      <div className="stat-grid">
        <div>
          <span>使用记录</span>
          <b>{item.usageStats.usage_count}</b>
        </div>
        <div>
          <span>成功结果</span>
          <b>{item.outcomeStats.success}</b>
        </div>
        <div>
          <span>失败结果</span>
          <b>{item.outcomeStats.failure}</b>
        </div>
        <div>
          <span>派生置信度</span>
          <b>{Number(item.confidenceSummary.score).toFixed(2)}</b>
        </div>
      </div>
      <p className="micro">
        结果按反馈事件计数，同一次使用可有多次反馈。置信度为统计派生值。
        {timeQuery ? "已按指定时点选择正文；" : ""}
        生命周期状态、证据更正状态、关系与统计均显示当前状态。
      </p>
      <div className="form-grid">
        <JsonView title="适用范围" value={item.applicability} />
        <JsonView title="约束" value={item.constraints} />
        <JsonView title="上下文引用" value={item.contextRefs} />
        <JsonView title="过程 Episode" value={item.episodes} />
      </div>
      {item.whyMatched && (
        <JsonView title="匹配原因与权重" value={item.whyMatched} />
      )}
      <JsonView title="完整经验包" value={item} />
      <dl className="metadata">
        <dt>版本 ID</dt>
        <dd>
          <Id value={item.versionId} />
        </dd>
        <dt>业务有效期</dt>
        <dd>
          {date(item.validFrom)} 至{" "}
          {item.validTo ? date(item.validTo) : "未设结束时间"}（左闭右开）
        </dd>
        <dt>记录时间</dt>
        <dd>{date(item.recordedAt)}</dd>
        <dt>替代自</dt>
        <dd>
          <Id value={item.supersedesId} />
        </dd>
      </dl>
    </article>
  );
}
function VersionActions({
  api,
  item,
  refresh,
}: {
  api: LedgerApi;
  item: Experience;
  refresh: () => void;
}) {
  const [feedback, setFeedback] = useState(""),
    [feedbackKey] = useState(() => crypto.randomUUID()),
    [reason, setReason] = useState(""),
    [target, setTarget] = useState(""),
    [relation, setRelation] = useState("RELATED_TO"),
    [candidate, setCandidate] = useState<Row>();
  const task = useAction();
  return (
    <div className="panel">
      <div className="section-heading">
        <h2>复用与演进</h2>
        <a
          className="button primary"
          href={`#/usage?version=${item.versionId}`}
        >
          记录使用
          <ArrowUpRight size={16} />
        </a>
      </div>
      <ErrorBox error={task.error} />
      <Success>{task.message}</Success>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void task.run(async () => {
            const c = await api.request<Row>(
              `/experiences/${item.versionId}/feedback`,
              { content: feedback, dedupKey: feedbackKey },
            );
            setCandidate(c);
            task.setMessage("已创建演进候选，需要审核后才会成为新版本。");
          });
        }}
      >
        <Field label="新发现或改进建议">
          <textarea
            required
            value={feedback}
            onChange={(e) => setFeedback(e.target.value)}
            placeholder="哪些结论需要补充？在哪些情境下有新的表现？"
          />
        </Field>
        <button disabled={task.busy || !!candidate}>提交演进候选</button>
        {candidate && (
          <a
            className="button"
            href={`#/review/${candidate.id}?family=${item.experienceId}&previous=${item.versionId}`}
          >
            审核新候选
          </a>
        )}
      </form>
      <details className="advanced">
        <summary>版本治理</summary>
        <p className="muted">
          新内容通过候选审核发布。撤销只改变当前认知，历史内容始终保留。
        </p>
        <Field label="治理原因 *">
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>
        <div className="actions">
          <button
            disabled={task.busy || item.status !== "VERIFIED"}
            className="danger"
            onClick={() =>
              void task.run(async () => {
                if (!reason.trim()) throw new Error("请填写撤销原因");
                if (
                  !window.confirm(
                    "确认撤销此版本？此操作保留历史，不恢复前一版本。",
                  )
                )
                  return;
                await api.request(`/versions/${item.versionId}/invalidate`, {
                  reason,
                });
                task.setMessage("已撤销版本。");
                refresh();
              })
            }
          >
            撤销当前版本
          </button>
          <button
            disabled={task.busy}
            onClick={() =>
              void task.run(async () => {
                await api.request(
                  `/versions/${item.versionId}/retry-embedding`,
                  {},
                );
                task.setMessage("已提交向量重建任务。");
              })
            }
          >
            <RefreshCw size={15} />
            重建向量
          </button>
          <a className="button" href={`#/judgments/version/${item.versionId}`}>审核跨侧共享</a>
          <a className="button" href={`#/audit?target=${item.versionId}`}>
            版本审计
          </a>
        </div>
        <form
          className="nested"
          onSubmit={(e) => {
            e.preventDefault();
            void task.run(async () => {
              if (!reason.trim()) throw new Error("请填写治理原因");
              const toVersionId = requireId(target, "目标版本 ID");
              if (toVersionId === item.versionId)
                throw new Error("不能关联同一个版本");
              await api.request("/relations", {
                fromVersionId: item.versionId,
                toVersionId,
                relationType: relation,
                reason,
              });
              setTarget("");
              task.setMessage("版本关系已建立。");
              refresh();
            });
          }}
        >
          <h3>建立版本关系</h3>
          <div className="form-grid">
            <Field label="关系类型">
              <select
                value={relation}
                onChange={(e) => setRelation(e.target.value)}
              >
                <SelectOptions values={relationTypes} />
              </select>
            </Field>
            <Field label="目标版本 UUID">
              <input
                required
                value={target}
                onChange={(e) => setTarget(e.target.value)}
              />
            </Field>
          </div>
          <button disabled={task.busy}>
            <Link2 size={16} />
            建立关系
          </button>
        </form>
      </details>
    </div>
  );
}
