import { useEffect, useState } from "react";
import type { LedgerApi } from "../api";
import {
  Field,
  PageTitle,
  ErrorBox,
  Success,
  JsonView,
  useAction,
} from "../components/ui";
import { objectJson } from "../domain";
type Row = Record<string, any>;
type Props = { api: LedgerApi };
const initialRules = {
  domains: ["equipment"],
  taskTypes: ["equipment_diagnosis"],
  allowedOrigins: [
    "OBSERVED",
    "HUMAN_ASSERTED",
    "AGENT_DERIVED",
    "SYSTEM_DERIVED",
  ],
  maxContextTokens: 3000,
  maxEvidence: 5,
  maxItems: 4,
  minConfidence: 0.8,
  minEvidenceReliability: 0.5,
  maxAgeDays: 3650,
  requireEvidence: true,
  requireNegativeCases: true,
  allowUnknownScope: false,
  allowDeepSearch: false,
  candidateLimit: 50,
  deepCandidateLimit: 200,
};
const split = (s: string) =>
  s
    .split(/[，,\n]+/)
    .map((x) => x.trim())
    .filter(Boolean);
function Messages({ a }: { a: ReturnType<typeof useAction> }) {
  return (
    <>
      <ErrorBox error={a.error} />
      <Success>{a.message}</Success>
    </>
  );
}
function Check({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="context-check">
      <input
        type="checkbox"
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  );
}
function Id({ id }: { id: string }) {
  return <code className="context-id">{id}</code>;
}
export function PolicyPage({ api }: Props) {
  const a = useAction(),
    [rows, setRows] = useState<Row[]>([]),
    [bindings, setBindings] = useState<Row[]>([]),
    [editing, setEditing] = useState<Row | null>(null),
    [name, setName] = useState("设备诊断策略"),
    [enabled, setEnabled] = useState(true),
    [rules, setRules] = useState<Row>(structuredClone(initialRules)),
    [domains, setDomains] = useState("equipment"),
    [tasks, setTasks] = useState("equipment_diagnosis"),
    [reason, setReason] = useState(""),
    [binding, setBinding] = useState({
      actorType: "AGENT",
      actorId: "",
      policyId: "",
      enabled: true,
      expectedRevision: 0,
    });
  const load = async () => {
    setRows(await api.v2<Row[]>("/policies"));
    setBindings(await api.v2<Row[]>("/bindings"));
  };
  useEffect(() => {
    void a.run(load);
  }, [api]);
  const edit = (p: Row | null) => {
    setEditing(p);
    setName(p?.name || "新策略");
    setEnabled(p?.enabled ?? true);
    setRules(structuredClone(p?.rules_json || initialRules));
    setDomains((p?.rules_json?.domains || initialRules.domains).join(","));
    setTasks((p?.rules_json?.taskTypes || initialRules.taskTypes).join(","));
    setReason("");
  };
  return (
    <>
      <PageTitle eyebrow="GOVERNANCE" title="检索策略">
        策略由身份绑定决定。请求可以收紧预算，无法扩大权限。
      </PageTitle>
      <Messages a={a} />
      <div className="context-columns">
        <section className="panel">
          <h2>策略列表</h2>
          <button onClick={() => edit(null)}>新建策略</button>
          {rows.length === 0 && <p>先创建策略，再绑定 Agent。</p>}
          {rows.map((p) => (
            <article className="context-card" key={p.id}>
              <strong>{p.name}</strong> · R{p.revision} ·{" "}
              {p.enabled ? "启用" : "停用"}
              <Id id={p.id} />
              <button onClick={() => edit(p)}>编辑</button>
            </article>
          ))}
        </section>
        <form
          className="panel"
          onSubmit={(e) => {
            e.preventDefault();
            void a.run(async () => {
              const p = await api.v2<Row>(
                "/policies" + (editing ? "/" + editing.id : ""),
                {
                  name,
                  enabled,
                  rules: {
                    ...rules,
                    domains: split(domains),
                    taskTypes: split(tasks),
                  },
                  expectedRevision: editing?.revision,
                  reason,
                },
              );
              edit(p);
              await load();
              a.setMessage("策略已保存");
            });
          }}
        >
          <h2>{editing ? "修改策略" : "新建策略"}</h2>
          <Field label="名称">
            <input
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <Check label="启用策略" value={enabled} onChange={setEnabled} />
          <Field label="允许领域（逗号分隔）">
            <input
              required
              value={domains}
              onChange={(e) => setDomains(e.target.value)}
            />
          </Field>
          <Field label="允许任务类型（逗号分隔）">
            <input
              required
              value={tasks}
              onChange={(e) => setTasks(e.target.value)}
            />
          </Field>
          <fieldset>
            <legend>允许的来源</legend>
            {initialRules.allowedOrigins.map((origin) => (
              <Check
                key={origin}
                label={origin}
                value={rules.allowedOrigins.includes(origin)}
                onChange={(v) =>
                  setRules({
                    ...rules,
                    allowedOrigins: v
                      ? [...rules.allowedOrigins, origin]
                      : rules.allowedOrigins.filter(
                          (s: string) => s !== origin,
                        ),
                  })
                }
              />
            ))}
          </fieldset>
          <div className="context-grid">
            {[
              ["maxContextTokens", "上下文预算", 256, 20000, 1],
              ["maxEvidence", "最多证据", 0, 50, 1],
              ["maxItems", "最多条目", 1, 20, 1],
              ["minConfidence", "最低评估置信度", 0, 1, 0.01],
              ["minEvidenceReliability", "最低证据可靠度", 0, 1, 0.01],
              ["maxAgeDays", "最大年龄（天）", 1, 36500, 1],
              ["candidateLimit", "常规候选池", 10, 200, 1],
              ["deepCandidateLimit", "深检索候选池", 10, 500, 1],
            ].map(([key, label, min, max, step]) => (
              <Field key={key} label={String(label)}>
                <input
                  required
                  type="number"
                  min={min}
                  max={max}
                  step={step}
                  value={rules[key]}
                  onChange={(e) =>
                    setRules({ ...rules, [key]: Number(e.target.value) })
                  }
                />
              </Field>
            ))}
          </div>
          {(
            [
              ["requireEvidence", "必须有支持证据"],
              ["requireNegativeCases", "必须包含反例"],
              ["allowUnknownScope", "允许未提供的范围条件"],
              ["allowDeepSearch", "允许深检索"],
            ] as const
          ).map(([key, label]) => (
            <Check
              key={key}
              label={label}
              value={rules[key]}
              onChange={(v) => setRules({ ...rules, [key]: v })}
            />
          ))}
          <Field label="修改原因">
            <input
              required
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
          <button disabled={a.busy}>保存策略</button>
        </form>
      </div>
      <section className="panel">
        <h2>身份绑定</h2>
        <p>Actor ID 必须与后端凭证配置一致。此处不会创建登录账号。</p>
        <form
          className="context-grid"
          onSubmit={(e) => {
            e.preventDefault();
            void a.run(async () => {
              await api.v2("/bindings", {
                ...binding,
                reason: "人工维护身份绑定",
              });
              await load();
              setBinding({ ...binding, actorId: "", expectedRevision: 0 });
              a.setMessage("绑定已保存");
            });
          }}
        >
          <Field label="身份类型">
            <select
              value={binding.actorType}
              onChange={(e) =>
                setBinding({
                  ...binding,
                  actorType: e.target.value,
                  expectedRevision: 0,
                })
              }
            >
              {["AGENT", "SYSTEM", "HUMAN", "TRUSTED_WORKFLOW"].map((x) => (
                <option key={x}>{x}</option>
              ))}
            </select>
          </Field>
          <Field label="Actor ID">
            <input
              required
              value={binding.actorId}
              onChange={(e) =>
                setBinding({
                  ...binding,
                  actorId: e.target.value,
                  expectedRevision: 0,
                })
              }
            />
          </Field>
          <Field label="策略">
            <select
              required
              value={binding.policyId}
              onChange={(e) =>
                setBinding({ ...binding, policyId: e.target.value })
              }
            >
              <option value="">选择策略</option>
              {rows.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </Field>
          <Check
            label="绑定启用"
            value={binding.enabled}
            onChange={(v) => setBinding({ ...binding, enabled: v })}
          />
          <button disabled={a.busy}>保存绑定</button>
        </form>
        {bindings.map((b) => (
          <article className="context-card" key={b.id}>
            {b.actor_type} / {b.actor_id} · R{b.revision} ·{" "}
            {b.enabled ? "启用" : "停用"}
            <Id id={b.policy_id} />
            <button
              onClick={() =>
                setBinding({
                  actorType: b.actor_type,
                  actorId: b.actor_id,
                  policyId: b.policy_id,
                  enabled: b.enabled,
                  expectedRevision: b.revision,
                })
              }
            >
              载入修改
            </button>
          </article>
        ))}
      </section>
    </>
  );
}
export function CompactPage({ api }: Props) {
  const a = useAction(),
    [rows, setRows] = useState<Row[]>([]),
    [form, setForm] = useState({
      title: "",
      summary: "",
      domain: "equipment",
      taskType: "equipment_diagnosis",
      representativeId: "",
      versionIds: "",
      reason: "",
    }),
    [version, setVersion] = useState(""),
    [validation, setValidation] = useState({
      status: "VERIFIED",
      assessedConfidence: 0.8,
      reason: "",
    }),
    [history, setHistory] = useState<Row[]>([]);
  const load = async () => setRows(await api.v2<Row[]>("/compacts"));
  useEffect(() => {
    void a.run(load);
  }, [api]);
  return (
    <>
      <PageTitle eyebrow="KNOWLEDGE COMPACT" title="知识压缩">
        将同范围、同方向的经验整理成审核后的摘要。反例独立成簇，来源变化后重新审核。
      </PageTitle>
      <Messages a={a} />
      <div className="context-columns">
        <form
          className="panel"
          onSubmit={(e) => {
            e.preventDefault();
            void a.run(async () => {
              await api.v2("/compacts", {
                ...form,
                versionIds: split(form.versionIds),
              });
              setForm({ ...form, title: "", summary: "" });
              await load();
              a.setMessage("已创建草稿，请核对来源后批准");
            });
          }}
        >
          <h2>新建 Compact</h2>
          {(
            [
              ["title", "摘要标题"],
              ["domain", "领域"],
              ["taskType", "任务类型"],
              ["representativeId", "代表版本 ID"],
              ["versionIds", "来源版本 ID（逗号或换行分隔，包含代表版本）"],
              ["reason", "创建原因"],
            ] as const
          ).map(([key, label]) => (
            <Field key={key} label={label}>
              <input
                required
                value={form[key]}
                onChange={(e) => setForm({ ...form, [key]: e.target.value })}
              />
            </Field>
          ))}
          <Field label="人工总结">
            <textarea
              required
              rows={6}
              maxLength={6000}
              value={form.summary}
              onChange={(e) => setForm({ ...form, summary: e.target.value })}
            />
          </Field>
          <button disabled={a.busy}>创建草稿</button>
        </form>
        <section className="panel">
          <h2>独立验证与置信度</h2>
          <p>评估分不是成功概率，也不覆盖历史结果。</p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void a.run(async () => {
                await api.v2(
                  "/versions/" + version + "/validations",
                  validation,
                );
                setHistory(
                  await api.v2<Row[]>("/versions/" + version + "/validations"),
                );
                await load();
                a.setMessage("验证事件已追加");
              });
            }}
          >
            <Field label="经验版本 ID">
              <input
                required
                value={version}
                onChange={(e) => {
                  setVersion(e.target.value);
                  setHistory([]);
                }}
              />
            </Field>
            <button
              type="button"
              disabled={a.busy || !version}
              onClick={() =>
                void a.run(async () =>
                  setHistory(
                    await api.v2<Row[]>(
                      "/versions/" + version + "/validations",
                    ),
                  ),
                )
              }
            >
              读取验证历史
            </button>
            <Field label="状态">
              <select
                value={validation.status}
                onChange={(e) =>
                  setValidation({ ...validation, status: e.target.value })
                }
              >
                {["VERIFIED", "ADOPTED", "DISPUTED", "DEPRECATED"].map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </Field>
            <Field label="人工评估置信度">
              <input
                type="number"
                required
                min={0}
                max={1}
                step={0.01}
                value={validation.assessedConfidence}
                onChange={(e) =>
                  setValidation({
                    ...validation,
                    assessedConfidence: Number(e.target.value),
                  })
                }
              />
            </Field>
            <Field label="评估理由">
              <textarea
                required
                value={validation.reason}
                onChange={(e) =>
                  setValidation({ ...validation, reason: e.target.value })
                }
              />
            </Field>
            <button disabled={a.busy}>追加验证</button>
          </form>
          <JsonView title="验证历史" value={history} />
        </section>
      </div>
      <section className="panel">
        <h2>摘要清单 · 最近 100 条</h2>
        {rows.map((c) => (
          <article className="context-card" key={c.id}>
            <h3>
              {c.title}{" "}
              <span className="badge">
                {c.fresh ? c.status : "STALE · 来源已变化"}
              </span>
            </h3>
            <p>{c.summary}</p>
            <Id id={c.id} />
            <p>
              {c.domain} / {c.task_type}
            </p>
            <p>在经验检索中查看源版本正文及其历史链。</p>
            <JsonView
              title="代表版本及来源"
              value={{
                representativeId: c.representative_id,
                sources: c.snapshot_json,
              }}
            />
            {c.status === "DRAFT" && c.fresh && (
              <button
                disabled={a.busy}
                onClick={() =>
                  void a.run(async () => {
                    await api.v2("/compacts/" + c.id + "/approve", {
                      reason: "人工核对摘要和来源后批准",
                    });
                    await load();
                  })
                }
              >
                批准使用
              </button>
            )}
            {c.status !== "RETIRED" && (
              <button
                disabled={a.busy}
                onClick={() =>
                  void a.run(async () => {
                    await api.v2("/compacts/" + c.id + "/retire", {
                      reason: "人工退役摘要",
                    });
                    await load();
                  })
                }
              >
                退役
              </button>
            )}
          </article>
        ))}
        {rows.length === 0 && <p>暂无摘要，可先录入并发布经验。</p>}
      </section>
    </>
  );
}
export function ContextPage({ api }: Props) {
  const a = useAction(),
    [policies, setPolicies] = useState<Row[]>([]),
    [request, setRequest] = useState({
      domain: "equipment",
      taskType: "equipment_diagnosis",
      assetType: "pump",
      query: "",
      policyId: "",
      maxContextTokens: 3000,
      maxEvidence: 5,
      minConfidence: 0.8,
      needNegativeCases: true,
      needEvidence: true,
      deepSearch: false,
    }),
    [scope, setScope] = useState('{"load":80,"maintenanceWindow":true}'),
    [result, setResult] = useState<Row | null>(null),
    [evidence, setEvidence] = useState<unknown>(null),
    [feedback, setFeedback] = useState({
      versionId: "",
      eventKey: crypto.randomUUID(),
      adopted: true,
      outcomeType: "SUCCESS",
      evaluation: "",
      actualInputTokens: "",
      actualOutputTokens: "",
      reportedCost: "",
      currency: "CNY",
    });
  useEffect(() => {
    void a.run(async () => setPolicies(await api.v2<Row[]>("/policies")));
  }, [api]);
  return (
    <>
      <PageTitle eyebrow="MEMORY GATEWAY" title="上下文供给">
        此页预览 Agent
        受众。人的经验需先审核跨侧共享，再校验范围与策略并按预算选择。只将
        contextText 注入 Agent 上下文。
      </PageTitle>
      <Messages a={a} />
      <form
        className="panel"
        onSubmit={(e) => {
          e.preventDefault();
          void a.run(async () => {
            const next = await api.v2<Row>("/context", {
              ...request,
              policyId: request.policyId || undefined,
              context: objectJson(scope),
            });
            setResult(next);
            setEvidence(null);
            setFeedback({
              ...feedback,
              versionId: next.selected[0]?.representativeVersionId || "",
              eventKey: crypto.randomUUID(),
              evaluation: "",
            });
          });
        }}
      >
        <div className="context-grid">
          <Field label="预览策略">
            <select
              required
              value={request.policyId}
              onChange={(e) =>
                setRequest({ ...request, policyId: e.target.value })
              }
            >
              <option value="">选择策略</option>
              {policies
                .filter((p) => p.enabled)
                .map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
            </select>
          </Field>
          {(
            [
              ["domain", "领域"],
              ["taskType", "任务类型"],
              ["assetType", "资产类型"],
              ["query", "检索关键词"],
            ] as const
          ).map(([key, label]) => (
            <Field key={key} label={label}>
              <input
                required={key === "domain" || key === "taskType"}
                value={request[key]}
                onChange={(e) =>
                  setRequest({ ...request, [key]: e.target.value })
                }
              />
            </Field>
          ))}
          {(
            [
              ["maxContextTokens", "上下文预算", 256, 20000, 1],
              ["maxEvidence", "最多证据", 0, 50, 1],
              ["minConfidence", "最低置信度", 0, 1, 0.01],
            ] as const
          ).map(([key, label, min, max, step]) => (
            <Field key={key} label={label}>
              <input
                required
                type="number"
                min={min}
                max={max}
                step={step}
                value={request[key]}
                onChange={(e) =>
                  setRequest({ ...request, [key]: Number(e.target.value) })
                }
              />
            </Field>
          ))}
        </div>
        <Field label="当前运行条件（JSON 对象）">
          <textarea
            required
            rows={3}
            value={scope}
            onChange={(e) => setScope(e.target.value)}
          />
        </Field>
        {(
          [
            ["needNegativeCases", "需要反例"],
            ["needEvidence", "需要证据"],
            ["deepSearch", "深检索"],
          ] as const
        ).map(([key, label]) => (
          <Check
            key={key}
            label={label}
            value={request[key]}
            onChange={(v) => setRequest({ ...request, [key]: v })}
          />
        ))}
        <button disabled={a.busy}>生成上下文</button>
      </form>
      {result && (
        <>
          <section className="panel">
            <h2>
              {result.status === "READY" ? "上下文已就绪" : "需要人工处理"}
            </h2>
            <p>
              {result.budget.contextUnits} / {result.budget.maxContextTokens}{" "}
              预算单位 · {result.diagnostics.tier} · 保守 UTF-8 字节计数
            </p>
            {result.gaps.map((g: string) => (
              <p key={g} className="context-gap">
                {g}
              </p>
            ))}
            <textarea
              aria-label="供给上下文"
              readOnly
              rows={14}
              value={result.contextText}
            />
            <JsonView
              title="策略、预算与诊断"
              value={{
                budget: result.budget,
                policy: result.policy,
                diagnostics: result.diagnostics,
              }}
            />
            {result.selected.map((s: Row) => (
              <article className="context-card" key={s.key}>
                <strong>
                  {s.negative ? "反例 · " : ""}
                  {s.title}
                </strong>
                <p>
                  置信度 {s.assessedConfidence} · {s.units} 单位
                </p>
                <JsonView title="源版本引用" value={s.versionIds} />
                <JsonView title="原生归属与跨侧共享授权" value={s.reuse} />
                {s.evidence.map((e: Row) => (
                  <button
                    key={e.evidenceId}
                    disabled={a.busy}
                    onClick={() =>
                      void a.run(async () =>
                        setEvidence(
                          await api.v2(
                            "/evidence/" +
                              e.evidenceId +
                              "?runId=" +
                              result.runId,
                          ),
                        ),
                      )
                    }
                  >
                    证据 {e.evidenceId.slice(0, 8)}
                  </button>
                ))}
              </article>
            ))}
            <p>证据下钻需要将当前人员身份绑定到允许该范围的策略。</p>
            {evidence !== null && (
              <JsonView title="证据详情" value={evidence} />
            )}
          </section>
          <form
            className="panel"
            onSubmit={(e) => {
              e.preventDefault();
              void a.run(async () => {
                await api.v2("/feedback", {
                  ...feedback,
                  runId: result.runId,
                  outcomeType: feedback.outcomeType || null,
                  actualInputTokens:
                    feedback.actualInputTokens === ""
                      ? null
                      : Number(feedback.actualInputTokens),
                  actualOutputTokens:
                    feedback.actualOutputTokens === ""
                      ? null
                      : Number(feedback.actualOutputTokens),
                  reportedCost:
                    feedback.reportedCost === ""
                      ? null
                      : Number(feedback.reportedCost),
                });
                a.setMessage("反馈已记录，同一事件键重试不会重复计数");
              });
            }}
          >
            <h2>报告采用与结果</h2>
            <Field label="供给版本">
              <select
                required
                value={feedback.versionId}
                onChange={(e) =>
                  setFeedback({ ...feedback, versionId: e.target.value })
                }
              >
                <option value="">选择版本</option>
                {Array.from(
                  new Set<string>(
                    result.selected.flatMap((s: Row) => s.versionIds),
                  ),
                ).map((id) => (
                  <option key={id}>{id}</option>
                ))}
              </select>
            </Field>
            <Check
              label="实际采用"
              value={feedback.adopted}
              onChange={(v) => setFeedback({ ...feedback, adopted: v })}
            />
            <Field label="结果">
              <select
                value={feedback.outcomeType}
                onChange={(e) =>
                  setFeedback({ ...feedback, outcomeType: e.target.value })
                }
              >
                <option value="">仅记录采用</option>
                {["SUCCESS", "FAILURE", "PARTIAL_SUCCESS", "INCONCLUSIVE"].map(
                  (x) => (
                    <option key={x}>{x}</option>
                  ),
                )}
              </select>
            </Field>
            <Field label="评价">
              <textarea
                required
                value={feedback.evaluation}
                onChange={(e) =>
                  setFeedback({ ...feedback, evaluation: e.target.value })
                }
              />
            </Field>
            <div className="context-grid">
              {(
                [
                  ["actualInputTokens", "本事件实际输入 Token"],
                  ["actualOutputTokens", "本事件实际输出 Token"],
                  ["reportedCost", "本事件自报费用"],
                ] as const
              ).map(([key, label]) => (
                <Field key={key} label={label}>
                  <input
                    type="number"
                    min={0}
                    step={key === "reportedCost" ? ".000001" : "1"}
                    value={feedback[key]}
                    onChange={(e) =>
                      setFeedback({ ...feedback, [key]: e.target.value })
                    }
                  />
                </Field>
              ))}
              <Field label="币种">
                <input
                  required={feedback.reportedCost !== ""}
                  value={feedback.currency}
                  onChange={(e) =>
                    setFeedback({ ...feedback, currency: e.target.value })
                  }
                />
              </Field>
            </div>
            <p>
              事件键：{feedback.eventKey}
              。多次结果填写增量费用，不重复填累计值。
            </p>
            <button disabled={a.busy || !feedback.versionId}>提交反馈</button>
            <button
              type="button"
              disabled={a.busy}
              onClick={() =>
                setFeedback({
                  ...feedback,
                  eventKey: crypto.randomUUID(),
                  evaluation: "",
                  actualInputTokens: "",
                  actualOutputTokens: "",
                  reportedCost: "",
                })
              }
            >
              开始下一次结果事件
            </button>
          </form>
        </>
      )}
    </>
  );
}
export function OperationsPage({ api }: Props) {
  const a = useAction(),
    [data, setData] = useState<Row | null>(null),
    [selected, setSelected] = useState(""),
    [verdict, setVerdict] = useState("ACCEPTED"),
    [reason, setReason] = useState("");
  const load = async () => setData(await api.v2<Row>("/operations"));
  useEffect(() => {
    void a.run(load);
  }, [api]);
  return (
    <>
      <PageTitle
        eyebrow="AGENT OPERATIONS"
        title="Agent 运营"
        actions={
          <button disabled={a.busy} onClick={() => void a.run(load)}>
            刷新
          </button>
        }
      >
        最近 30 天的运行聚合。成本是事件增量自报值，不代表供应商账单。
      </PageTitle>
      <Messages a={a} />
      {data && (
        <>
          <section className="panel">
            <h2>按身份统计</h2>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>身份</th>
                    <th>运行数</th>
                    <th>上下文单位</th>
                    <th>人工处理</th>
                    <th>平均耗时 ms</th>
                  </tr>
                </thead>
                <tbody>
                  {data.agents.map((x: Row) => (
                    <tr key={x.actor_type + x.actor_id}>
                      <td>
                        {x.actor_type} / {x.actor_id}
                      </td>
                      <td>{x.runs}</td>
                      <td>{x.context_units}</td>
                      <td>{x.escalations}</td>
                      <td>{x.avg_latency_ms}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!data.agents.length && <p>暂无运行数据。</p>}
            <JsonView title="自报 Token 与费用（按币种）" value={data.costs} />
            <JsonView title="采用与结果事件统计" value={data.usage} />
            <JsonView title="最近 100 次供给运行" value={data.runs} />
          </section>
          <section className="panel">
            <h2>反馈复核 · 最近 100 条</h2>
            <p>
              人工否决追加复核记录，不删除原始反馈或自动改变置信度。必要时到知识压缩页面追加
              DISPUTED 验证。
            </p>
            {data.feedback.map((f: Row) => (
              <article className="context-card" key={f.id}>
                <strong>
                  {f.actor_id} · {f.adopted ? "已采用" : "未采用"} ·{" "}
                  {f.outcome_type || "无结果"}
                </strong>
                <p>{f.evaluation}</p>
                <p>{f.verdict || "待复核"}</p>
                <Id id={f.id} />
                <button
                  onClick={() => {
                    setSelected(f.id);
                    setReason("");
                  }}
                >
                  复核
                </button>
                <JsonView value={f} />
              </article>
            ))}
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void a.run(async () => {
                  await api.v2("/feedback/" + selected + "/review", {
                    verdict,
                    reason,
                  });
                  await load();
                  setSelected("");
                  setReason("");
                  a.setMessage("人工复核已追加");
                });
              }}
            >
              <Field label="选中反馈">
                <input required readOnly value={selected} />
              </Field>
              <Field label="结论">
                <select
                  value={verdict}
                  onChange={(e) => setVerdict(e.target.value)}
                >
                  <option value="ACCEPTED">接受</option>
                  <option value="REJECTED">否决</option>
                </select>
              </Field>
              <Field label="复核原因">
                <textarea
                  required
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </Field>
              <button disabled={a.busy || !selected}>追加复核</button>
            </form>
          </section>
        </>
      )}
    </>
  );
}
