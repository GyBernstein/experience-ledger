import { useEffect, useState } from "react";
import { ArrowUpRight, Plus, RefreshCw, Fingerprint } from "lucide-react";
import { LedgerApi, query } from "../api";
import type { Row } from "../types";
import { evidenceTypes, outcomeTypes } from "../types";
import {
  instant,
  localNow,
  objectJson,
  pretty,
  requireId,
  toLocal,
} from "../domain";
import {
  Badge,
  date,
  Empty,
  ErrorBox,
  Field,
  Id,
  JsonView,
  Loading,
  PageTitle,
  SelectOptions,
  Success,
  useAction,
  useLoad,
} from "../components/ui";

export function EvidencePage({
  api,
  initialId,
}: {
  api: LedgerApi;
  initialId: string;
}) {
  const [lookup, setLookup] = useState(initialId),
    [selectedId, setSelectedId] = useState(initialId),
    [type, setType] = useState("USER_NOTE"),
    [system, setSystem] = useState(""),
    [ref, setRef] = useState(""),
    [snapshot, setSnapshot] = useState("{}"),
    [entryMode, setEntryMode] = useState("text"),
    [note, setNote] = useState(""),
    [metadata, setMetadata] = useState("{}"),
    [observed, setObserved] = useState(localNow),
    [reliability, setReliability] = useState("0.8"),
    [correction, setCorrection] = useState(""),
    [reason, setReason] = useState(""),
    [saved, setSaved] = useState<Row>();
  const task = useAction();
  const detail = useLoad(
    (s) =>
      selectedId
        ? api.request<Row>(
            `/evidence/${requireId(selectedId, "证据 ID")}`,
            undefined,
            s,
          )
        : Promise.resolve(null),
    [api, selectedId],
  );
  return (
    <>
      <PageTitle eyebrow="EVIDENCE / PROVENANCE" title="证据记录">
        保存证据快照。需要更正时创建替代记录，原始依据仍可追溯。
      </PageTitle>
      <div className="two-columns">
        <section className="stack">
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              void task.run(async () => {
                setSelectedId(requireId(lookup, "证据 ID"));
                detail.reload();
              });
            }}
          >
            <h2>查找证据</h2>
            <div className="inline-form">
              <input
                aria-label="证据 UUID"
                required
                value={lookup}
                onChange={(e) => setLookup(e.target.value)}
                placeholder="输入证据 UUID"
              />
              <button disabled={detail.loading}>查询</button>
            </div>
            <p className="micro">证据也可以从经验详情中的关联列表打开。</p>
          </form>
          {detail.loading ? (
            <Loading />
          ) : detail.error ? (
            <ErrorBox error={detail.error} />
          ) : detail.data ? (
            <div className="panel">
              <div className="section-heading">
                <h2>{detail.data.evidence_type}</h2>
                <Badge value={detail.data.status} />
              </div>
              <Id value={detail.data.id} />
              <dl className="metadata">
                <dt>来源</dt>
                <dd>
                  {detail.data.source_system || "—"} /{" "}
                  {detail.data.source_ref || "—"}
                </dd>
                <dt>观察时间</dt>
                <dd>{date(detail.data.observed_at)}</dd>
                <dt>采集时间</dt>
                <dd>{date(detail.data.captured_at)}</dd>
                <dt>可靠度</dt>
                <dd>{detail.data.reliability}</dd>
                <dt>内容指纹</dt>
                <dd>
                  <code className="id">{detail.data.content_hash}</code>
                </dd>
              </dl>
              <div className="nested">
                <h3>证据内容</h3>
                {typeof detail.data.snapshot_json?.text === "string" ? (
                  <p className="preserve">{detail.data.snapshot_json.text}</p>
                ) : (
                  <pre className="snapshot-preview">
                    {pretty(detail.data.snapshot_json)}
                  </pre>
                )}
              </div>
              <JsonView value={detail.data.metadata_json} title="证据元数据" />
              {detail.data.snapshot_uri && (
                <p className="micro">
                  外部快照地址：{detail.data.snapshot_uri}
                </p>
              )}
              {detail.data.corrected_by_evidence_id && (
                <p>
                  <a
                    href={`#/evidence?id=${detail.data.corrected_by_evidence_id}`}
                  >
                    查看替代证据
                    <ArrowUpRight size={15} />
                  </a>
                </p>
              )}
              <div className="actions">
                <button
                  disabled={task.busy || detail.data.status !== "ACTIVE"}
                  onClick={() => {
                    const r = detail.data!;
                    setCorrection(r.id);
                    setSaved(undefined);
                    setType(r.evidence_type);
                    setSystem(r.source_system || "");
                    setRef(r.source_ref || "");
                    setObserved(toLocal(r.observed_at));
                    setSnapshot(pretty(r.snapshot_json));
                    setEntryMode(
                      typeof r.snapshot_json?.text === "string" &&
                        Object.keys(r.snapshot_json).length === 1
                        ? "text"
                        : "json",
                    );
                    setNote(r.snapshot_json?.text || "");
                    setMetadata(pretty(r.metadata_json));
                    setReliability(String(r.reliability));
                    setReason("");
                  }}
                >
                  准备更正
                </button>
                <a className="button" href={`#/audit?target=${detail.data.id}`}>
                  查看审计
                </a>
              </div>
              <JsonView value={detail.data} />
            </div>
          ) : (
            <div className="panel">
              <Empty title="选择一份证据">
                输入证据 ID，查看快照、来源及更正历史。
              </Empty>
            </div>
          )}
        </section>
        <form
          className="panel"
          onSubmit={(e) => {
            e.preventDefault();
            void task.run(async () => {
              const body = {
                evidenceType: type,
                sourceSystem: system || undefined,
                sourceRef: ref || undefined,
                snapshot:
                  entryMode === "text" ? { text: note } : JSON.parse(snapshot),
                metadata: objectJson(metadata, "元数据"),
                observedAt: instant(observed),
                reliability: Number(reliability),
              };
              if (correction && !reason.trim())
                throw new Error("请填写更正原因");
              if (
                correction &&
                !window.confirm(
                  "确认创建替代证据？原快照会保留并标记为已替代。",
                )
              )
                return;
              const row = await api.request<Row>(
                correction
                  ? `/evidence/${correction}/correct${query({ reason })}`
                  : "/evidence",
                body,
              );
              setSaved(row);
              setSelectedId(row.id);
              setLookup(row.id);
              setCorrection("");
              task.setMessage(
                "证据已保存。请复制证据 ID，在审核 Claim 时关联。",
              );
            });
          }}
        >
          <div className="section-heading">
            <h2>{correction ? "更正证据" : "新建证据"}</h2>
            <Fingerprint size={22} className="accent" />
          </div>
          {correction && (
            <p className="micro">
              替代原记录：
              <Id value={correction} />
            </p>
          )}
          <fieldset disabled={task.busy || !!saved}>
            <div className="form-grid">
              <Field label="证据类型">
                <select value={type} onChange={(e) => setType(e.target.value)}>
                  <SelectOptions values={evidenceTypes} />
                </select>
              </Field>
              <Field label="可靠度（0–1）">
                <input
                  type="number"
                  min="0"
                  max="1"
                  step="0.01"
                  required
                  value={reliability}
                  onChange={(e) => setReliability(e.target.value)}
                />
              </Field>
              <Field label="来源系统">
                <input
                  value={system}
                  onChange={(e) => setSystem(e.target.value)}
                />
              </Field>
              <Field label="来源编号">
                <input value={ref} onChange={(e) => setRef(e.target.value)} />
              </Field>
            </div>
            <Field label="观察时间 *">
              <input
                type="datetime-local"
                step="1"
                required
                value={observed}
                onChange={(e) => setObserved(e.target.value)}
              />
            </Field>
            <Field label="录入方式">
              <select
                value={entryMode}
                onChange={(e) => setEntryMode(e.target.value)}
              >
                <option value="text">文字记录</option>
                <option value="json">结构化 JSON</option>
              </select>
            </Field>
            {entryMode === "text" ? (
              <Field
                label="证据内容 *"
                hint="可以填写现场观察、人工确认、测试结果或文档摘录。"
              >
                <textarea
                  required
                  rows={8}
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder="例如：本次测试在相同条件下重复三次，均得到相同结果。"
                />
              </Field>
            ) : (
              <Field label="证据快照 JSON *" hint="内容指纹由后端生成。">
                <textarea
                  className="code-input"
                  required
                  rows={9}
                  value={snapshot}
                  onChange={(e) => setSnapshot(e.target.value)}
                />
              </Field>
            )}
            <details>
              <summary>附加元数据</summary>
              <Field label="元数据 JSON">
                <textarea
                  className="code-input"
                  value={metadata}
                  onChange={(e) => setMetadata(e.target.value)}
                />
              </Field>
            </details>
            {correction && (
              <Field label="更正原因 *">
                <textarea
                  required
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </Field>
            )}
            <button className="primary">
              <Plus size={17} />
              {correction ? "保存替代证据" : "保存证据"}
            </button>
          </fieldset>
          <ErrorBox error={task.error} />
          <Success>{task.message}</Success>
          {saved && (
            <div className="nested">
              <Id value={saved.id} />
              <button
                type="button"
                onClick={() => {
                  setSaved(undefined);
                  setSnapshot("{}");
                  setNote("");
                  setEntryMode("text");
                  setMetadata("{}");
                  setRef("");
                  setObserved(localNow());
                  task.setMessage("");
                }}
              >
                继续新建
              </button>
            </div>
          )}
          {correction && (
            <button
              type="button"
              className="text-button"
              onClick={() => setCorrection("")}
            >
              取消更正，改为新建
            </button>
          )}
        </form>
      </div>
    </>
  );
}
export function UsagePage({
  api,
  initialVersion,
}: {
  api: LedgerApi;
  initialVersion: string;
}) {
  const [version, setVersion] = useState(initialVersion),
    [context, setContext] = useState("{}"),
    [recommended, setRecommended] = useState(true),
    [used, setUsed] = useState(true),
    [usage, setUsage] = useState<Row>(),
    [lookup, setLookup] = useState(""),
    [usageId, setUsageId] = useState(""),
    [outcomes, setOutcomes] = useState<Row[]>(),
    [type, setType] = useState("SUCCESS"),
    [notes, setNotes] = useState(""),
    [metrics, setMetrics] = useState("{}"),
    [observed, setObserved] = useState(localNow),
    [evidence, setEvidence] = useState("");
  const create = useAction(),
    outcome = useAction(),
    read = useAction();
  async function load(id: string) {
    setOutcomes(await api.request<Row[]>(`/usages/${id}/outcomes`));
  }
  return (
    <>
      <PageTitle eyebrow="REUSE / FEEDBACK" title="使用与结果反馈">
        先记录采用了哪个版本，再持续追加观察到的结果。
      </PageTitle>
      <div className="two-columns">
        <div className="stack">
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              void create.run(async () => {
                const row = await api.request<Row>("/usages", {
                  versionId: requireId(version, "版本 ID"),
                  queryContext: objectJson(context, "查询上下文"),
                  recommended,
                  actuallyUsed: used,
                });
                setUsage(row);
                setUsageId(row.id);
                setLookup(row.id);
                setOutcomes([]);
                create.setMessage("使用记录已保存。可以在右侧追加结果。");
              });
            }}
          >
            <h2>记录一次使用</h2>
            <fieldset disabled={create.busy || !!usage}>
              <Field label="经验版本 ID *">
                <input
                  required
                  value={version}
                  onChange={(e) => setVersion(e.target.value)}
                  placeholder="使用 versionId，不是 Family ID"
                />
              </Field>
              <details>
                <summary>结构化使用上下文（可选）</summary>
                <Field label="使用上下文 JSON">
                  <textarea
                    className="code-input"
                    rows={6}
                    value={context}
                    onChange={(e) => setContext(e.target.value)}
                  />
                </Field>
              </details>
              <div className="actions">
                <label className="check">
                  <input
                    type="checkbox"
                    checked={recommended}
                    onChange={(e) => setRecommended(e.target.checked)}
                  />
                  曾被推荐
                </label>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={used}
                    onChange={(e) => setUsed(e.target.checked)}
                  />
                  实际采用
                </label>
              </div>
              <button className="primary">
                <Plus size={17} />
                保存使用记录
              </button>
            </fieldset>
            <ErrorBox error={create.error} />
            <Success>{create.message}</Success>
            {usage && (
              <div className="nested">
                <h3>使用记录 ID</h3>
                <Id value={usage.id} />
                <p className="micro">请保留此 ID，用于后续追加和查询反馈。</p>
                <JsonView value={usage} />
                <button
                  type="button"
                  onClick={() => {
                    setUsage(undefined);
                    create.setMessage("");
                  }}
                >
                  记录另一次使用
                </button>
              </div>
            )}
          </form>
          <div className="panel guidance">
            <h3>反馈是持续发生的</h3>
            <p>
              同一次使用可以先报告阶段性成功，之后再补充失败或新的结果。所有反馈作为独立事件保留。
            </p>
            <p className="micro">
              页面不会自动重试写入。若提交超时，请先查询结果或审计后再决定是否重试。
            </p>
          </div>
        </div>
        <div className="stack">
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              void read.run(async () => {
                const id = requireId(lookup, "使用记录 ID");
                const rows = await api.request<Row[]>(`/usages/${id}/outcomes`);
                setUsageId(id);
                setOutcomes(rows);
                read.setMessage(
                  rows.length
                    ? "已读取反馈。"
                    : "当前返回 0 条反馈；这不能证明该使用记录存在，追加时后端会校验。",
                );
              });
            }}
          >
            <h2>选择使用记录</h2>
            <div className="inline-form">
              <input
                aria-label="使用记录 UUID"
                required
                value={lookup}
                onChange={(e) => setLookup(e.target.value)}
                placeholder="输入使用记录 UUID"
              />
              <button disabled={read.busy}>查询</button>
            </div>
            <ErrorBox error={read.error} />
            <Success>{read.message}</Success>
          </form>
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              void outcome.run(async () => {
                const id = requireId(usageId, "使用记录 ID");
                await api.request(`/usages/${id}/outcomes`, {
                  outcomeType: type,
                  notes,
                  metrics: objectJson(metrics, "结果指标"),
                  observedAt: instant(observed),
                  evidenceId: evidence
                    ? requireId(evidence, "证据 ID")
                    : undefined,
                });
                setNotes("");
                setMetrics("{}");
                setEvidence("");
                setObserved(localNow());
                outcome.setMessage("结果已追加。");
                try {
                  await load(id);
                } catch {
                  outcome.setMessage(
                    "结果已追加，但列表刷新失败。请查询记录，勿重复提交同一次结果。",
                  );
                }
              });
            }}
          >
            <h2>追加结果反馈</h2>
            {usageId && (
              <p className="micro">
                当前记录 <Id value={usageId} />
              </p>
            )}
            <fieldset disabled={!usageId || outcome.busy}>
              <div className="form-grid">
                <Field label="结果类型">
                  <select
                    value={type}
                    onChange={(e) => setType(e.target.value)}
                  >
                    <SelectOptions values={outcomeTypes} />
                  </select>
                </Field>
                <Field label="观察时间 *">
                  <input
                    type="datetime-local"
                    step="1"
                    required
                    value={observed}
                    onChange={(e) => setObserved(e.target.value)}
                  />
                </Field>
              </div>
              <Field label="结果说明">
                <textarea
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                />
              </Field>
              <details>
                <summary>结果指标与证据关联（可选）</summary>
                <Field label="结果指标 JSON">
                  <textarea
                    className="code-input"
                    value={metrics}
                    onChange={(e) => setMetrics(e.target.value)}
                  />
                </Field>
                <Field label="关联证据 ID（可选）">
                  <input
                    value={evidence}
                    onChange={(e) => setEvidence(e.target.value)}
                  />
                </Field>
              </details>
              <button className="primary">追加反馈</button>
            </fieldset>
            {!usageId && <p className="micro">先创建或查询一条使用记录。</p>}
            <ErrorBox error={outcome.error} />
            <Success>{outcome.message}</Success>
          </form>
        </div>
      </div>
      {outcomes && (
        <div className="panel outcome-list">
          <div className="section-heading">
            <h2>
              结果事件 <span className="count">{outcomes.length}</span>
            </h2>
            {usageId && <a href={`#/audit?target=${usageId}`}>查看使用审计</a>}
          </div>
          {outcomes.length ? (
            outcomes.map((r, i) => (
              <article key={r.id} className="outcome-row">
                <div className="event-number">
                  {String(i + 1).padStart(2, "0")}
                </div>
                <div>
                  <Badge value={r.outcome_type} />
                  <p className="preserve">{r.notes || "未填写说明"}</p>
                  <div className="micro">
                    观察于 {date(r.observed_at)} · 记录于 {date(r.created_at)}
                  </div>
                  <JsonView value={r} />
                </div>
              </article>
            ))
          ) : (
            <Empty title="暂无结果反馈" />
          )}
        </div>
      )}
    </>
  );
}
export function AuditPage({
  api,
  initialTarget,
}: {
  api: LedgerApi;
  initialTarget: string;
}) {
  const [target, setTarget] = useState(initialTarget),
    [limit, setLimit] = useState(100),
    [applied, setApplied] = useState({ targetId: initialTarget, limit: 100 });
  const task = useAction();
  const list = useLoad(
    (s) =>
      api.request<Row[]>(
        `/audit${query({ targetId: applied.targetId ? requireId(applied.targetId, "目标 ID") : undefined, limit: applied.limit })}`,
        undefined,
        s,
      ),
    [api, applied],
  );
  return (
    <>
      <PageTitle eyebrow="GOVERNANCE / AUDIT" title="审计日志">
        追踪当前空间内的记录变化、操作身份与原因。
      </PageTitle>
      <form
        className="panel toolbar"
        onSubmit={(e) => {
          e.preventDefault();
          void task.run(async () =>
            setApplied({
              targetId: target ? requireId(target, "目标 ID") : "",
              limit,
            }),
          );
        }}
      >
        <Field label="目标记录 UUID">
          <input
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            placeholder="留空查看当前空间"
          />
        </Field>
        <Field label="返回条数">
          <select
            value={limit}
            onChange={(e) => setLimit(Number(e.target.value))}
          >
            <option>100</option>
            <option>250</option>
            <option>500</option>
            <option>1000</option>
          </select>
        </Field>
        <button className="primary" disabled={list.loading}>
          查询日志
        </button>
        <button type="button" onClick={list.reload} disabled={list.loading}>
          <RefreshCw size={16} />
          刷新
        </button>
      </form>
      <ErrorBox error={list.error || task.error} />
      {list.loading ? (
        <Loading />
      ) : list.data?.length ? (
        <div className="panel table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>动作 / 对象</th>
                <th>操作身份</th>
                <th>原因与变化</th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((r) => (
                <tr key={r.id}>
                  <td className="nowrap">{date(r.created_at)}</td>
                  <td>
                    <b>{r.action}</b>
                    <p className="micro">{r.target_type}</p>
                    <Id value={r.target_id} />
                  </td>
                  <td>
                    <Badge value={r.actor_type} />
                    <p>{r.actor_id}</p>
                  </td>
                  <td>
                    {r.reason || "—"}
                    <JsonView
                      value={{
                        before: r.before_ref,
                        after: r.after_ref,
                        metadata: r.metadata_json,
                      }}
                      title="查看变更"
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="table-note">
            按记录时间倒序，最多返回 {applied.limit} 条。日志仅提供只读查看。
          </p>
        </div>
      ) : (
        !list.error && <Empty title="没有匹配的审计事件" />
      )}
    </>
  );
}
