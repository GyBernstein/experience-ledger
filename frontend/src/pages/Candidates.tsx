import { useEffect, useRef, useState } from "react";
import {
  Plus,
  ArrowRight,
  RefreshCw,
  Trash2,
  Save,
  Check,
  Download,
} from "lucide-react";
import { LedgerApi, query } from "../api";
import type { Candidate, Claim, Draft, Row } from "../types";
import {
  candidateStatuses,
  claimTypes,
  originTypes,
  experienceTypes,
} from "../types";
import {
  allowedDispositions,
  arrayJson,
  draftFromCandidate,
  instant,
  localNow,
  objectJson,
  pretty,
  requireId,
  toLocal,
  validateDraft,
} from "../domain";
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
  navigationGuard,
} from "../components/ui";

export function CapturePage({ api }: { api: LedgerApi }) {
  const [content, setContent] = useState(""),
    [sourceSystem, setSystem] = useState(""),
    [sourceRef, setRef] = useState(""),
    [eventType, setEvent] = useState(""),
    [dedupKey, setKey] = useState(() => crypto.randomUUID() as string);
  const [hasEpisode, setHasEpisode] = useState(false),
    [title, setTitle] = useState(""),
    [occurredAt, setOccurred] = useState(localNow),
    [episodeSummary, setEpisodeSummary] = useState(""),
    [extracted, setExtracted] = useState("{}"),
    [result, setResult] = useState<Candidate>();
  const task = useAction();
  return (
    <>
      <PageTitle eyebrow="CAPTURE / 01" title="采集一条经验">
        先保留发生过的事，再整理成可复用的结论。
      </PageTitle>
      <div className="two-columns">
        <form
          className="panel"
          onSubmit={(e) => {
            e.preventDefault();
            void task.run(async () => {
              const row = await api.request<Candidate>("/candidates/capture", {
                content,
                sourceSystem: sourceSystem || undefined,
                sourceRef: sourceRef || undefined,
                eventType: eventType || undefined,
                dedupKey,
                extracted: objectJson(extracted, "结构化信息"),
                ...(hasEpisode
                  ? {
                      episode: {
                        title,
                        summary: episodeSummary,
                        occurredAt: instant(occurredAt),
                      },
                    }
                  : {}),
              });
              setResult(row);
              task.setMessage(
                "采集成功。候选已交由后台处理，可以进入人工审核。",
              );
            });
          }}
        >
          <h2>原始记录</h2>
          <Field label="经验内容 *">
            <textarea
              rows={9}
              required
              maxLength={100000}
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="发生了什么？尝试了哪些方法？观察到什么结果？"
            />
          </Field>
          <details>
            <summary>来源与重复采集控制（可选）</summary>
            <div className="form-grid">
              <Field label="来源系统">
                <input
                  value={sourceSystem}
                  onChange={(e) => setSystem(e.target.value)}
                  placeholder="例如 coding-session"
                />
              </Field>
              <Field label="来源编号">
                <input
                  value={sourceRef}
                  onChange={(e) => setRef(e.target.value)}
                  placeholder="例如 session-001"
                />
              </Field>
              <Field label="事件类型">
                <input
                  value={eventType}
                  onChange={(e) => setEvent(e.target.value)}
                  placeholder="例如 TASK_COMPLETED"
                />
              </Field>
              <Field
                label="采集幂等键 *"
                hint="同一次采集重试请保留此键；开始新记录时更换。"
              >
                <input
                  required
                  maxLength={256}
                  value={dedupKey}
                  onChange={(e) => setKey(e.target.value)}
                />
              </Field>
            </div>
          </details>
          <label className="check">
            <input
              type="checkbox"
              checked={hasEpisode}
              onChange={(e) => setHasEpisode(e.target.checked)}
            />
            同时记录一次过程（Episode）
          </label>
          {hasEpisode && (
            <div className="nested">
              <div className="form-grid">
                <Field label="过程标题 *">
                  <input
                    required
                    value={title}
                    onChange={(e) => setTitle(e.target.value)}
                  />
                </Field>
                <Field label="发生时间 *">
                  <input
                    type="datetime-local"
                    step="1"
                    required
                    value={occurredAt}
                    onChange={(e) => setOccurred(e.target.value)}
                  />
                </Field>
              </div>
              <Field label="过程摘要">
                <textarea
                  value={episodeSummary}
                  onChange={(e) => setEpisodeSummary(e.target.value)}
                />
              </Field>
            </div>
          )}
          <details>
            <summary>已有结构化信息</summary>
            <Field
              label="extracted JSON"
              hint="保留已有 Claim 的 originType 与 derivationMethod。"
            >
              <textarea
                className="code-input"
                rows={7}
                value={extracted}
                onChange={(e) => setExtracted(e.target.value)}
              />
            </Field>
          </details>
          <ErrorBox error={task.error} />
          <Success>{task.message}</Success>
          <div className="actions">
            <button className="primary" disabled={task.busy || !!result}>
              <Plus size={17} />
              {task.busy ? "提交中…" : "保存候选"}
            </button>
            {result && (
              <>
                <button
                  type="button"
                  onClick={() => go(`/review/${result.id}`)}
                >
                  进入审核
                  <ArrowRight size={16} />
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setResult(undefined);
                    setContent("");
                    setExtracted("{}");
                    setTitle("");
                    setEpisodeSummary("");
                    setRef("");
                    setKey(crypto.randomUUID());
                    task.setMessage("");
                  }}
                >
                  开始新记录
                </button>
              </>
            )}
          </div>
        </form>
        <aside className="stack">
          <div className="panel guidance">
            <div className="eyebrow">好的记录，始于上下文</div>
            <h2>记录事实与判断的边界</h2>
            <ol>
              <li>
                <b>写清情境</b>
                <p>目标、约束与采取的行动。</p>
              </li>
              <li>
                <b>保留依据</b>
                <p>测试结果、业务记录或人工确认，可在证据页添加。</p>
              </li>
              <li>
                <b>标明推导</b>
                <p>Agent 推断在审核后仍保留来源；发布由有权限的人员确认。</p>
              </li>
            </ol>
          </div>
          {result && (
            <div className="panel">
              <h3>采集结果</h3>
              <Badge value={result.status} />
              <p>
                <Id value={result.id} />
              </p>
              <p className="muted">修订号 {result.revision}</p>
              <JsonView value={result} />
            </div>
          )}
        </aside>
      </div>
    </>
  );
}

export function CandidatesPage({ api }: { api: LedgerApi }) {
  const [status, setStatus] = useState(""),
    [limit, setLimit] = useState(50),
    [lookup, setLookup] = useState("");
  const task = useAction();
  const list = useLoad(
    (s) =>
      api.request<Candidate[]>(
        `/candidates${query({ status, limit })}`,
        undefined,
        s,
      ),
    [api, status, limit],
  );
  return (
    <>
      <PageTitle
        eyebrow="REVIEW / 02"
        title="候选审核"
        actions={
          <a className="button primary" href="#/capture">
            <Plus size={17} />
            采集经验
          </a>
        }
      >
        将原始记录整理为有依据、可验证的经验。
      </PageTitle>
      <div className="toolbar panel">
        <Field label="候选状态">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <SelectOptions values={candidateStatuses} blank="全部状态" />
          </select>
        </Field>
        <Field label="显示条数">
          <select
            value={limit}
            onChange={(e) => setLimit(Number(e.target.value))}
          >
            <option>25</option>
            <option>50</option>
            <option>100</option>
          </select>
        </Field>
        <button onClick={list.reload} disabled={list.loading}>
          <RefreshCw size={17} />
          刷新
        </button>
        <form
          className="inline-form push-right"
          onSubmit={(e) => {
            e.preventDefault();
            void task.run(async () =>
              go(`/review/${requireId(lookup, "候选 ID")}`),
            );
          }}
        >
          <input
            aria-label="按候选 ID 打开"
            placeholder="输入候选 UUID"
            value={lookup}
            onChange={(e) => setLookup(e.target.value)}
            required
          />
          <button>打开</button>
        </form>
      </div>
      <ErrorBox error={list.error || task.error} />
      {list.loading ? (
        <Loading />
      ) : list.data?.length ? (
        <div className="panel table-wrap">
          <table>
            <thead>
              <tr>
                <th>候选内容</th>
                <th>来源</th>
                <th>状态 / 处理</th>
                <th>采集时间</th>
                <th>修订</th>
                <th>
                  <span className="sr-only">操作</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((c) => (
                <tr key={c.id}>
                  <td>
                    <a className="row-title" href={`#/review/${c.id}`}>
                      {c.extracted_json?.draft?.title ||
                        c.raw_content.slice(0, 88)}
                    </a>
                    <div className="micro">{c.id}</div>
                  </td>
                  <td>
                    {c.source_system || c.source_type}
                    <div className="micro">{c.source_ref || "—"}</div>
                  </td>
                  <td>
                    <Badge value={c.status} />
                    <div className="micro">{c.processing_status}</div>
                  </td>
                  <td className="nowrap">{date(c.created_at)}</td>
                  <td>r{c.revision}</td>
                  <td>
                    <a
                      className="icon-link"
                      aria-label="打开候选"
                      href={`#/review/${c.id}`}
                    >
                      <ArrowRight size={18} />
                    </a>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="table-note">
            仅显示最近 {limit} 条；更早记录可按候选 ID 打开。
          </p>
        </div>
      ) : (
        !list.error && (
          <Empty title="当前筛选下暂无候选">
            采集一条经验后，它会出现在这里。
          </Empty>
        )
      )}
    </>
  );
}

function ClaimEditor({
  claims,
  onChange,
}: {
  claims: Claim[];
  onChange: (claims: Claim[]) => void;
}) {
  function change(i: number, patch: Partial<Claim>) {
    onChange(claims.map((c, j) => (i === j ? { ...c, ...patch } : c)));
  }
  return (
    <section className="claim-editor">
      <div className="section-heading">
        <h2>
          结论与依据 <span className="count">{claims.length}</span>
        </h2>
        <button
          type="button"
          onClick={() =>
            onChange([
              ...claims,
              {
                claimType: "RECOMMENDATION",
                originType: "HUMAN_ASSERTED",
                content: "",
                evidence: [],
              },
            ])
          }
        >
          <Plus size={16} />
          添加 Claim
        </button>
      </div>
      {!claims.length && (
        <p className="muted">至少添加一条结论，并选择真实来源。</p>
      )}
      {claims.map((c, i) => (
        <article className="claim-card" key={i}>
          <div className="section-heading">
            <b>CLAIM {String(i + 1).padStart(2, "0")}</b>
            <button
              type="button"
              className="icon-button"
              aria-label={`删除第 ${i + 1} 条 Claim`}
              onClick={() => onChange(claims.filter((_, j) => i !== j))}
            >
              <Trash2 size={16} />
            </button>
          </div>
          <Field label="内容 *">
            <textarea
              required
              value={c.content}
              onChange={(e) => change(i, { content: e.target.value })}
            />
          </Field>
          <div className="form-grid">
            <Field label="结论类型">
              <select
                value={c.claimType}
                onChange={(e) => change(i, { claimType: e.target.value })}
              >
                <SelectOptions values={claimTypes} />
              </select>
            </Field>
            <Field label="来源类型">
              <select
                value={c.originType}
                onChange={(e) => change(i, { originType: e.target.value })}
              >
                <SelectOptions values={originTypes} />
              </select>
            </Field>
          </div>
          {c.originType.endsWith("_DERIVED") && (
            <Field label="推导方法 *">
              <textarea
                required
                value={c.derivationMethod || ""}
                onChange={(e) =>
                  change(i, { derivationMethod: e.target.value })
                }
              />
            </Field>
          )}
          <div className="evidence-links">
            {c.evidence?.map((link, j) => (
              <div className="inline-form" key={j}>
                <input
                  aria-label={`Claim ${i + 1} 证据 ${j + 1} UUID`}
                  required
                  placeholder="证据 UUID"
                  value={link.evidenceId}
                  onChange={(e) =>
                    change(i, {
                      evidence: c.evidence!.map((v, k) =>
                        k === j ? { ...v, evidenceId: e.target.value } : v,
                      ),
                    })
                  }
                />
                <select
                  aria-label="证据关系"
                  value={link.supportType}
                  onChange={(e) =>
                    change(i, {
                      evidence: c.evidence!.map((v, k) =>
                        k === j ? { ...v, supportType: e.target.value } : v,
                      ),
                    })
                  }
                >
                  <SelectOptions
                    values={["SUPPORTS", "CONTRADICTS", "CONTEXT"]}
                  />
                </select>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="移除证据引用"
                  onClick={() =>
                    change(i, {
                      evidence: c.evidence!.filter((_, k) => k !== j),
                    })
                  }
                >
                  <Trash2 size={15} />
                </button>
              </div>
            ))}
            <button
              type="button"
              className="text-button"
              onClick={() =>
                change(i, {
                  evidence: [
                    ...(c.evidence || []),
                    { evidenceId: "", supportType: "SUPPORTS" },
                  ],
                })
              }
            >
              + 关联证据
            </button>
          </div>
        </article>
      ))}
    </section>
  );
}

export function ReviewPage({
  api,
  id,
  familyId,
  previousId,
}: {
  api: LedgerApi;
  id: string;
  familyId: string;
  previousId: string;
}) {
  const loaded = useLoad(
    (s) => api.request<Candidate>(`/candidates/${requireId(id)}`, undefined, s),
    [api, id],
  );
  return (
    <>
      <PageTitle
        eyebrow="REVIEW / CANDIDATE"
        title="审核候选"
        actions={
          <a className="button" href="#/review">
            返回列表
          </a>
        }
      />
      <ErrorBox error={loaded.error} />
      {loaded.loading ? (
        <Loading />
      ) : (
        loaded.data && (
          <ReviewEditor
            key={loaded.data.id}
            api={api}
            initial={loaded.data}
            familyId={familyId}
            previousId={previousId}
          />
        )
      )}
    </>
  );
}
function ReviewEditor({
  api,
  initial,
  familyId,
  previousId,
}: {
  api: LedgerApi;
  initial: Candidate;
  familyId: string;
  previousId: string;
}) {
  const [candidate, setCandidate] = useState(initial),
    [draft, setDraft] = useState(() => draftFromCandidate(initial)),
    [dirty, setDirty] = useState(false);
  const [app, setApp] = useState(pretty(draft.applicability)),
    [constraints, setConstraints] = useState(pretty(draft.constraints)),
    [contexts, setContexts] = useState(pretty(draft.contextRefs || [])),
    [episodes, setEpisodes] = useState(pretty(draft.episodes || []));
  const [reason, setReason] = useState(""),
    [mode, setMode] = useState(
      familyId ? "CREATE_NEW_VERSION" : "CREATE_NEW_FAMILY",
    ),
    [family, setFamily] = useState(familyId),
    [previous, setPrevious] = useState(previousId),
    [key, setKey] = useState(""),
    [domain, setDomain] = useState(""),
    [type, setType] = useState("BEST_PRACTICE"),
    [published, setPublished] = useState<Row>();
  const [disposition, setDisposition] = useState("REJECTED"),
    [target, setTarget] = useState(""),
    [targetKind, setTargetKind] = useState("targetVersionId");
  useEffect(() => {
    navigationGuard.dirty = dirty;
    return () => {
      navigationGuard.dirty = false;
    };
  }, [dirty]);
  const original = useRef(draftFromCandidate(initial)),
    task = useAction();
  const terminal = !["NEW", "ENRICHED", "PENDING_REVIEW"].includes(
    candidate.status,
  );
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  function edit(patch: Partial<Draft>) {
    setDraft((d) => ({ ...d, ...patch }));
    setDirty(true);
  }
  function replace(c: Candidate) {
    const d = draftFromCandidate(c);
    setCandidate(c);
    setDraft(d);
    original.current = d;
    setApp(pretty(d.applicability));
    setConstraints(pretty(d.constraints));
    setContexts(pretty(d.contextRefs || []));
    setEpisodes(pretty(d.episodes || []));
    setDirty(false);
  }
  function buildDraft() {
    const next = {
      ...draft,
      applicability: objectJson(app, "适用范围"),
      constraints: objectJson(constraints, "约束"),
      contextRefs: arrayJson<NonNullable<Draft["contextRefs"]>[number]>(
        contexts,
        "上下文",
      ),
      episodes: arrayJson<NonNullable<Draft["episodes"]>[number]>(
        episodes,
        "过程关联",
      ),
    };
    validateDraft(next, original.current);
    return next;
  }
  return (
    <>
      <div className="record-strip">
        <Badge value={candidate.status} />
        <span>r{candidate.revision}</span>
        <Id value={candidate.id} />
        {candidate.extracted_json?.judgmentInput && <a className="button" href={`#/judgments/review/${candidate.id}`}>打开判断规则编辑器</a>}
        <button
          disabled={task.busy}
          className="text-button push-right"
          onClick={() =>
            void task.run(async () => {
              if (
                dirty &&
                !window.confirm(
                  "重新加载会丢弃未保存草稿。可以先导出草稿。继续？",
                )
              )
                return;
              replace(
                await api.request<Candidate>(`/candidates/${candidate.id}`),
              );
              task.setMessage("已加载最新候选。");
            })
          }
        >
          <RefreshCw size={16} />
          重新加载
        </button>
      </div>
      <ErrorBox error={task.error} />
      <Success>{task.message}</Success>
      {published && (
        <div className="notice success">
          发布完成：
          <a href={`#/experiences/${published.family_id}`}>
            查看版本 V{published.version_no}
          </a>
        </div>
      )}
      <div className="review-layout">
        <div className="stack">
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              void task.run(async () => {
                const next = buildDraft();
                replace(
                  await api.request<Candidate>(
                    `/candidates/${candidate.id}/review`,
                    {
                      expectedRevision: candidate.revision,
                      draft: next,
                      reason,
                    },
                  ),
                );
                task.setMessage(
                  "审核草稿已保存。核对右侧发布信息后，可确认发布。",
                );
              });
            }}
          >
            <fieldset disabled={terminal || task.busy}>
              <div className="section-heading">
                <h2>经验草稿</h2>
                <span className="micro">
                  {dirty ? "有未保存修改" : "与已加载记录一致"}
                </span>
              </div>
              <Field label="标题 *">
                <input
                  required
                  value={draft.title}
                  onChange={(e) => edit({ title: e.target.value })}
                />
              </Field>
              <Field label="摘要 *">
                <textarea
                  required
                  value={draft.summary}
                  onChange={(e) => edit({ summary: e.target.value })}
                />
              </Field>
              <div className="form-grid">
                {(
                  ["problem", "decision", "action", "outcomeSummary"] as const
                ).map((name, i) => (
                  <Field
                    key={name}
                    label={["问题", "决策", "行动", "结果摘要"][i]}
                  >
                    <textarea
                      value={draft[name] || ""}
                      onChange={(e) => edit({ [name]: e.target.value })}
                    />
                  </Field>
                ))}
              </div>
              <Field label="经验结论 *">
                <textarea
                  required
                  value={draft.lesson}
                  onChange={(e) => edit({ lesson: e.target.value })}
                />
              </Field>
              <div className="form-grid">
                <Field label="业务生效时间 *">
                  <input
                    type="datetime-local"
                    step="1"
                    required
                    value={toLocal(draft.validFrom)}
                    onChange={(e) => {
                      if (e.target.value)
                        edit({ validFrom: instant(e.target.value) });
                    }}
                  />
                </Field>
                <Field label="业务失效时间（可选）">
                  <input
                    type="datetime-local"
                    step="1"
                    value={toLocal(draft.validTo)}
                    onChange={(e) =>
                      edit({
                        validTo: e.target.value
                          ? instant(e.target.value)
                          : null,
                      })
                    }
                  />
                </Field>
              </div>
              <ClaimEditor
                claims={draft.claims}
                onChange={(claims) => edit({ claims })}
              />
              <details className="advanced">
                <summary>适用范围、约束与关联</summary>
                <div className="form-grid">
                  <Field label="适用范围 JSON">
                    <textarea
                      className="code-input"
                      rows={5}
                      value={app}
                      onChange={(e) => {
                        setApp(e.target.value);
                        setDirty(true);
                      }}
                    />
                  </Field>
                  <Field label="约束 JSON">
                    <textarea
                      className="code-input"
                      rows={5}
                      value={constraints}
                      onChange={(e) => {
                        setConstraints(e.target.value);
                        setDirty(true);
                      }}
                    />
                  </Field>
                  <Field
                    label="上下文引用数组"
                    hint='例如 [{"refType":"PROJECT","refValue":"demo"}]'
                  >
                    <textarea
                      className="code-input"
                      rows={5}
                      value={contexts}
                      onChange={(e) => {
                        setContexts(e.target.value);
                        setDirty(true);
                      }}
                    />
                  </Field>
                  <Field
                    label="过程关联数组"
                    hint='例如 [{"episodeId":"UUID","relationType":"VALIDATION"}]；采集自带的过程会自动作为 SOURCE 关联。'
                  >
                    <textarea
                      className="code-input"
                      rows={5}
                      value={episodes}
                      onChange={(e) => {
                        setEpisodes(e.target.value);
                        setDirty(true);
                      }}
                    />
                  </Field>
                </div>
              </details>
              <Field label="审核说明">
                <textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </Field>
              <button className="primary">
                <Save size={17} />
                保存审核草稿
              </button>
            </fieldset>
          </form>
          <button
            className="text-button"
            onClick={() => {
              const blob = new Blob(
                [
                  pretty({
                    ...draft,
                    applicability: app,
                    constraints,
                    contextRefs: contexts,
                    episodes,
                  }),
                ],
                { type: "application/json" },
              );
              const url = URL.createObjectURL(blob);
              const a = document.createElement("a");
              a.href = url;
              a.download = `candidate-${candidate.id}-local-draft.json`;
              a.click();
              setTimeout(() => URL.revokeObjectURL(url), 1000);
            }}
          >
            <Download size={16} />
            导出本地草稿备份
          </button>
        </div>
        <aside className="stack">
          <div className="panel raw-record">
            <h3>原始记录</h3>
            <p className="preserve">{candidate.raw_content}</p>
            <div className="micro">
              {candidate.source_system || candidate.source_type} ·{" "}
              {date(candidate.created_at)}
            </div>
            <p>
              增强状态 <Badge value={candidate.processing_status} />
            </p>
            {candidate.processing_error && (
              <p className="warning-text">{candidate.processing_error}</p>
            )}
            {!terminal && (
              <button
                disabled={task.busy}
                onClick={() =>
                  void task.run(async () => {
                    await api.request(
                      `/candidates/${candidate.id}/retry-processing`,
                      {},
                    );
                    task.setMessage(
                      "已请求重试处理。完成后请手动重新加载候选。",
                    );
                  })
                }
              >
                <RefreshCw size={15} />
                重试增强
              </button>
            )}
            <JsonView
              title="采集的证据与增强信息"
              value={candidate.extracted_json}
            />
            {candidate.episode_id && (
              <p>
                <small>采集过程 ID</small>
                <Id value={candidate.episode_id} />
              </p>
            )}
          </div>
          {!terminal && (
            <form
              className="panel publish-panel"
              onSubmit={(e) => {
                e.preventDefault();
                void task.run(async () => {
                  if (dirty) throw new Error("请先保存草稿，再确认发布");
                  if (
                    !window.confirm(
                      "发布后该版本正文、Claim 与关联将冻结。确认发布？",
                    )
                  )
                    return;
                  const body =
                    mode === "CREATE_NEW_FAMILY"
                      ? {
                          mode,
                          experienceKey: key,
                          domain,
                          experienceType: type,
                        }
                      : {
                          mode,
                          familyId: requireId(family, "Family ID"),
                          expectedSupersedesId: requireId(
                            previous,
                            "当前版本 ID",
                          ),
                        };
                  const version = await api.request<Row>(
                    `/candidates/${candidate.id}/verify`,
                    { ...body, expectedRevision: candidate.revision, reason },
                  );
                  setPublished(version);
                  setCandidate((c) => ({ ...c, status: "VERIFIED" }));
                  task.setMessage("经验已发布。");
                });
              }}
            >
              <h2>确认发布</h2>
              <p className="muted">发布使用已保存的审核草稿。</p>
              <fieldset disabled={task.busy}>
                <Field label="发布方式">
                  <select
                    value={mode}
                    onChange={(e) => setMode(e.target.value)}
                  >
                    <option value="CREATE_NEW_FAMILY">创建新经验</option>
                    <option value="CREATE_NEW_VERSION">创建后继版本</option>
                  </select>
                </Field>
                {mode === "CREATE_NEW_FAMILY" ? (
                  <>
                    <Field label="经验编号 *">
                      <input
                        required
                        value={key}
                        onChange={(e) => setKey(e.target.value)}
                        placeholder="EXP-001"
                      />
                    </Field>
                    <Field label="领域 *">
                      <input
                        required
                        value={domain}
                        onChange={(e) => setDomain(e.target.value)}
                        placeholder="例如 engineering"
                      />
                    </Field>
                    <Field label="经验类型">
                      <select
                        value={type}
                        onChange={(e) => setType(e.target.value)}
                      >
                        <SelectOptions values={experienceTypes} />
                      </select>
                    </Field>
                  </>
                ) : (
                  <>
                    <Field label="经验 Family ID *">
                      <input
                        required
                        value={family}
                        onChange={(e) => setFamily(e.target.value)}
                      />
                    </Field>
                    <Field label="期望替代的当前版本 ID *">
                      <input
                        required
                        value={previous}
                        onChange={(e) => setPrevious(e.target.value)}
                      />
                    </Field>
                    <p className="micro">
                      当前链尾变化时，后端会拒绝发布。不会自动覆盖。
                    </p>
                  </>
                )}
                <button
                  className="primary wide"
                  disabled={candidate.status !== "PENDING_REVIEW" || dirty}
                >
                  <Check size={17} />
                  确认并发布
                </button>
              </fieldset>
            </form>
          )}
          {!terminal && (
            <form
              className="panel"
              onSubmit={(e) => {
                e.preventDefault();
                void task.run(async () => {
                  if (
                    !allowedDispositions(candidate.status).includes(disposition)
                  )
                    throw new Error("当前状态不允许此操作");
                  if (!reason.trim())
                    throw new Error("请填写审核说明，作为处置原因");
                  if (!window.confirm("确认结束该候选的审核？")) return;
                  const link =
                    (disposition === "MERGED" || disposition === "DUPLICATE") &&
                    target
                      ? { [targetKind]: requireId(target, "目标 ID") }
                      : {};
                  if (["MERGED", "DUPLICATE"].includes(disposition) && !target)
                    throw new Error("合并需要填写目标");
                  replace(
                    await api.request<Candidate>(
                      `/candidates/${candidate.id}/disposition`,
                      {
                        status: disposition,
                        expectedRevision: candidate.revision,
                        reason,
                        ...link,
                      },
                    ),
                  );
                  task.setMessage("候选处置完成。");
                });
              }}
            >
              <h3>其他处置</h3>
              <Field label="处置方式">
                <select
                  value={disposition}
                  onChange={(e) => setDisposition(e.target.value)}
                >
                  <SelectOptions
                    values={allowedDispositions(candidate.status)}
                  />
                </select>
              </Field>
              {["MERGED", "DUPLICATE"].includes(disposition) && (
                <>
                  <Field label="目标类型">
                    <select
                      value={targetKind}
                      onChange={(e) => setTargetKind(e.target.value)}
                    >
                      <option value="targetVersionId">经验版本</option>
                      <option value="targetEpisodeId">过程 Episode</option>
                    </select>
                  </Field>
                  <Field label="目标 UUID">
                    <input
                      value={target}
                      onChange={(e) => setTarget(e.target.value)}
                      required
                    />
                  </Field>
                  <p className="micro">
                    合并仅建立归档引用，不向已发布版本追加证据。
                  </p>
                </>
              )}
              <button className="danger" disabled={task.busy}>
                确认处置
              </button>
            </form>
          )}
          <a className="button" href={`#/audit?target=${candidate.id}`}>
            查看审计记录
          </a>
        </aside>
      </div>
    </>
  );
}
