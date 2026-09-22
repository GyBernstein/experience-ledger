import { useEffect, useState } from "react";
import { Plus, ArrowLeft, BookOpen, ShieldCheck } from "lucide-react";
import { LedgerApi, query as queryString } from "../api";
import type { Candidate, Row } from "../types";
import { objectJson, requireId } from "../domain";
import {
  Field,
  PageTitle,
  ErrorBox,
  Success,
  useAction,
  useLoad,
  Loading,
  Empty,
  Badge,
  JsonView,
  date,
  navigationGuard,
} from "../components/ui";
import {
  emptyRule,
  emptyGate,
  worthKeeping,
  lines,
  reuseLabels,
  ruleLabels,
  type JudgmentRule,
  type RetentionGate,
} from "../judgment";

export function JudgmentsPage({ api }: { api: LedgerApi }) {
  const [search, setSearch] = useState(""),
    [domain, setDomain] = useState(""),
    [filter, setFilter] = useState({ query: "", domain: "", offset: 0 });
  const data = useLoad(
    () => api.v2<Row[]>(`/judgments${queryString({ ...filter, limit: 30 })}`),
    [api, filter],
  );
  const candidates = useLoad(
    () => api.request<Candidate[]>("/candidates?limit=100"),
    [api],
  );
  const pending =
    candidates.data?.filter(
      (c) =>
        c.extracted_json?.judgmentInput &&
        ["NEW", "ENRICHED", "PENDING_REVIEW"].includes(c.status),
    ) || [];
  return (
    <>
      <PageTitle
        eyebrow="HUMAN JUDGMENT"
        title="值得留下的判断"
        actions={
          <a className="button primary" href="#/judgments/new">
            <Plus size={17} /> 沉淀一条判断
          </a>
        }
      >
        保存以后还会改变决策的规则。一次性的答案，可以不记住。
      </PageTitle>
      <section className="panel judgment-intro">
        <BookOpen size={25} />
        <div>
          <h2>经验的价值，在于下次能做出更好的判断</h2>
          <p>
            识别问题 → 提出好问题 → 守住约束 → 权衡方案 →
            检查结果。确认后留给人使用，另行审核后共享给 Agent。
          </p>
        </div>
      </section>
      <form
        className="panel search-form"
        onSubmit={(e) => {
          e.preventDefault();
          setFilter({ query: search, domain, offset: 0 });
        }}
      >
        <Field label="查找判断规则">
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="例如：振动、上线、方案取舍"
          />
        </Field>
        <Field label="领域">
          <input
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            placeholder="全部领域"
          />
        </Field>
        <button className="primary">查找</button>
      </form>
      <ErrorBox error={data.error} />
      {data.loading && <Loading />}
      <div className="judgment-grid">
        {data.data?.map((v) => (
          <article className="panel judgment-card" key={v.id}>
            <div className="card-top">
              <span className="badge">人的判断</span>
              <span>{v.domain}</span>
              {["DISPUTED", "DEPRECATED"].includes(v.validation_status) && (
                <span className="badge">需重审 · {v.validation_status}</span>
              )}
              {v.rule_json.negative && <Badge value="WARNING" />}
            </div>
            <h2>
              <a href={`#/judgments/version/${v.id}`}>{v.title}</a>
            </h2>
            <p className="muted">{v.rule_json.futureDecision}</p>
            <div className="lesson-preview">{v.rule_json.judgment}</div>
            <p>
              <strong>不适用于：</strong>
              {v.rule_json.boundaries}
            </p>
            <a href={`#/judgments/version/${v.id}`}>查看判断依据与共享状态 →</a>
          </article>
        ))}
      </div>
      {data.data?.length === 0 && (
        <Empty title="还没有当前有效的判断规则">
          从一条以后会改变你决策的经验开始。
        </Empty>
      )}
      <div className="actions">
        <button
          disabled={!filter.offset || data.loading}
          onClick={() =>
            setFilter((f) => ({ ...f, offset: Math.max(0, f.offset - 30) }))
          }
        >
          上一页
        </button>
        <button
          disabled={data.data?.length !== 30 || data.loading}
          onClick={() => setFilter((f) => ({ ...f, offset: f.offset + 30 }))}
        >
          下一页
        </button>
      </div>
      <section className="panel">
        <h2>待确认的判断</h2>
        <p className="muted">
          最近 100 条候选中的判断卡；尚未发布，也不会给 Agent 使用。
        </p>
        <ErrorBox error={candidates.error} />
        {pending.map((c) => (
          <p key={c.id}>
            <a href={`#/judgments/review/${c.id}`}>
              {c.extracted_json.judgmentInput.rule.title}
            </a>{" "}
            · <Badge value={c.status} />
          </p>
        ))}
        {!pending.length && <p>暂无待确认判断。</p>}
      </section>
    </>
  );
}

function idFromHash() {
  return window.location.hash.split("/")[3];
}
export function JudgmentEditor({
  api,
  id,
  sourceId,
}: {
  api: LedgerApi;
  id?: string;
  sourceId?: string;
}) {
  const initial = useLoad(
    async () =>
      id
        ? { candidate: await api.request<Candidate>(`/candidates/${id}`) }
        : sourceId
          ? { source: await api.v2<Row>(`/judgments/${sourceId}`) }
          : {},
    [api, id, sourceId],
  );
  if (initial.loading) return <Loading />;
  if (initial.error) return <ErrorBox error={initial.error} />;
  return (
    <EditorForm
      key={id || sourceId || "new"}
      api={api}
      reload={initial.reload}
      initial={(initial.data as any)?.candidate}
      source={(initial.data as any)?.source}
    />
  );
}
function EditorForm({
  api,
  initial,
  source,
  reload,
}: {
  api: LedgerApi;
  initial?: Candidate;
  source?: Row;
  reload: () => void;
}) {
  const input = initial?.extracted_json?.judgmentInput;
  const [rule, setRule] = useState<JudgmentRule>(
      input?.rule || source?.judgmentRule || { ...emptyRule },
    ),
    [gate, setGate] = useState<RetentionGate>(input?.gate || { ...emptyGate });
  const [domain, setDomain] = useState(input?.domain || source?.domain || ""),
    [key, setKey] = useState(
      input?.experienceKey || `JDG-${crypto.randomUUID().slice(0, 8)}`,
    );
  const [validFrom, setFrom] = useState(
      input?.validFrom || new Date().toISOString(),
    ),
    [validTo, setTo] = useState(input?.validTo || "");
  const [app, setApp] = useState(
      JSON.stringify(
        input?.applicability || source?.applicability_json || {},
        null,
        2,
      ),
    ),
    [constraints, setConstraints] = useState(
      JSON.stringify(
        input?.constraints || source?.constraints_json || {},
        null,
        2,
      ),
    ),
    [evidence, setEvidence] = useState((input?.evidenceIds || []).join("\n"));
  const [candidate, setCandidate] = useState<Candidate | undefined>(initial),
    [published, setPublished] = useState<Row>(),
    [eventKey] = useState(crypto.randomUUID()),
    [dirty, setDirty] = useState(false),
    [reason, setReason] = useState(""),
    [confirmed, setConfirmed] = useState(false);
  const task = useAction(),
    terminal =
      !!candidate &&
      !["NEW", "ENRICHED", "PENDING_REVIEW"].includes(candidate.status),
    keep = worthKeeping(gate);
  useEffect(() => {
    navigationGuard.dirty = dirty;
    const before = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", before);
    return () => {
      navigationGuard.dirty = false;
      window.removeEventListener("beforeunload", before);
    };
  }, [dirty]);
  function change<K extends keyof JudgmentRule>(k: K, value: JudgmentRule[K]) {
    setRule((r) => ({ ...r, [k]: value }));
    setDirty(true);
    setConfirmed(false);
  }
  if (initial && !input)
    return (
      <ErrorBox error={new Error("该候选不是判断卡，请使用候选审核页面。")} />
    );
  return (
    <>
      <a className="back-link" href="#/judgments">
        <ArrowLeft size={16} /> 返回判断库
      </a>
      <PageTitle
        eyebrow="CAPTURE → CONFIRM → SHARE"
        title={candidate ? "确认这条判断" : "沉淀一条判断"}
      >
        先决定值不值得记，再写下以后能用的规则。未保存的输入只留在当前页面。
      </PageTitle>
      <ErrorBox error={task.error} />
      <Success>{task.message}</Success>
      {published && (
        <section className="panel">
          <h2>已发布，当前仅人类侧可用</h2>
          <a
            className="button primary"
            href={`#/judgments/version/${published.id}`}
          >
            查看规则并审核共享
          </a>
        </section>
      )}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          void task.run(async () => {
            if (!keep) throw new Error("不符合保留条件，无需保存这次内容。");
            const cleanRule = {
              ...rule,
              questions: rule.questions.map((s) => s.trim()).filter(Boolean),
              hardConstraints: rule.hardConstraints
                .map((s) => s.trim())
                .filter(Boolean),
            };
            const response = await api.v2<{
              saved: boolean;
              candidate: Candidate;
            }>(
              candidate
                ? `/judgments/candidates/${candidate.id}`
                : "/judgments/candidates",
              {
                rule: cleanRule,
                gate,
                domain,
                experienceKey: key,
                validFrom: new Date(validFrom).toISOString(),
                validTo: validTo ? new Date(validTo).toISOString() : null,
                applicability: objectJson(app, "适用范围"),
                constraints: objectJson(constraints, "机器可检查约束"),
                evidenceIds: lines(evidence).map((x) =>
                  requireId(x, "证据 ID"),
                ),
                eventKey,
                expectedRevision: candidate?.revision,
                sourceFamilyId: source?.family_id || input?.sourceFamilyId,
                sourceVersionId: source?.id || input?.sourceVersionId,
              },
            );
            if (!response.saved)
              throw new Error("保留检查未通过，服务器没有保存。");
            setCandidate(response.candidate);
            setRule(cleanRule);
            setDirty(false);
            setConfirmed(false);
            task.setMessage("已保存为待确认判断。请核对内容后发布。");
          });
        }}
      >
        <fieldset disabled={task.busy || terminal || !!published}>
          <section className="panel retention-gate">
            <h2>01 · 这件事值得记住吗？</h2>
            {[
              ["changesFutureDecision", "以后遇到类似问题，它会改变我的决策"],
              ["reusable", "能提炼成跨次使用的判断规则"],
              ["readilyRecoverable", "只是随时能查到的一次性答案"],
            ].map(([k, label]) => (
              <label className="check" key={k}>
                <input
                  type="checkbox"
                  checked={Boolean(gate[k as keyof RetentionGate])}
                  onChange={(e) => {
                    setGate((g) => ({ ...g, [k]: e.target.checked }));
                    setDirty(true);
                  }}
                />
                {label}
              </label>
            ))}
            <Field label="下次你会因此改问什么、改变哪项选择？">
              <textarea
                rows={2}
                maxLength={1200}
                value={gate.decisionImpact}
                onChange={(e) => {
                  setGate((g) => ({ ...g, decisionImpact: e.target.value }));
                  setDirty(true);
                }}
                placeholder="例如：先核对负载变化，再决定是否安排拆检。"
              />
            </Field>
            <p className={keep ? "gate-keep" : "muted"}>
              {keep
                ? "值得留下：写成判断规则，再由人确认。"
                : "可以不记住。没有未来决策价值的内容，不会写入账本。"}
            </p>
          </section>
          <section className="panel">
            <h2>02 · 留下判断方法</h2>
            <Field label="给这条判断起个名字">
              <input
                required
                maxLength={200}
                value={rule.title}
                onChange={(e) => change("title", e.target.value)}
                placeholder="振动升高时，先排除工况变化"
              />
            </Field>
            <div className="judgment-form-grid">
              {Object.entries(ruleLabels).map(([k, label]) => {
                const field = k as keyof JudgmentRule,
                  multi = k === "questions" || k === "hardConstraints";
                const value = multi
                  ? (rule[field] as string[]).join("\n")
                  : String(rule[field] ?? "");
                return (
                  <Field
                    key={k}
                    label={label + (k === "counterexample" ? "（选填）" : " *")}
                    hint={
                      multi
                        ? "每行一项，最多 10 项；每项最多 400 字。"
                        : undefined
                    }
                  >
                    <textarea
                      required={k !== "counterexample"}
                      rows={k === "judgment" ? 4 : 3}
                      maxLength={
                        k === "judgment"
                          ? 1800
                          : k === "reviseWhen"
                            ? 1000
                            : multi
                              ? 4000
                              : 1200
                      }
                      value={value}
                      onChange={(e) =>
                        change(
                          field,
                          (multi
                            ? e.target.value.split("\n")
                            : e.target.value) as never,
                        )
                      }
                    />
                  </Field>
                );
              })}
            </div>
            <Field label="领域 *">
              <input
                required
                maxLength={100}
                value={domain}
                onChange={(e) => {
                  setDomain(e.target.value);
                  setDirty(true);
                }}
                placeholder="equipment / engineering / quality"
              />
            </Field>
            <label className="check">
              <input
                type="checkbox"
                checked={rule.negative}
                disabled={!!source || !!input?.sourceVersionId}
                onChange={(e) => change("negative", e.target.checked)}
              />
              这是一条防止重蹈覆辙的警示
            </label>
            <details>
              <summary>适用条件、证据与有效期</summary>
              <p className="muted">
                自然语言边界会完整提供给
                Agent。需要机器强制匹配的型号、负载、版本等，请填写结构化条件；未填写不代表系统理解了自然语言约束。
              </p>
              <Field label="适用范围 JSON">
                <textarea
                  value={app}
                  onChange={(e) => {
                    setApp(e.target.value);
                    setDirty(true);
                  }}
                />
              </Field>
              <Field label="机器可检查约束 JSON">
                <textarea
                  value={constraints}
                  onChange={(e) => {
                    setConstraints(e.target.value);
                    setDirty(true);
                  }}
                />
              </Field>
              <Field label="支持判断的证据 ID（每行一个，可暂不填）">
                <textarea
                  value={evidence}
                  onChange={(e) => {
                    setEvidence(e.target.value);
                    setDirty(true);
                  }}
                />
              </Field>
              <a href="#/evidence">去证据页录入原始记录</a>
              <Field label="作者自评置信度（不是验证分数）">
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.05}
                  value={rule.authorConfidence}
                  onChange={(e) =>
                    change("authorConfidence", Number(e.target.value))
                  }
                />
              </Field>
              <Field label="生效时间（ISO 时间）">
                <input
                  required
                  value={validFrom}
                  onChange={(e) => {
                    setFrom(e.target.value);
                    setDirty(true);
                  }}
                />
              </Field>
              <Field label="失效时间（留空表示未设定）">
                <input
                  value={validTo}
                  onChange={(e) => {
                    setTo(e.target.value);
                    setDirty(true);
                  }}
                />
              </Field>
              <Field label="经验编号">
                <input
                  required
                  value={key}
                  onChange={(e) => {
                    setKey(e.target.value);
                    setDirty(true);
                  }}
                />
              </Field>
            </details>
          </section>
          <div className="actions">
            <button className="primary" disabled={!keep}>
              保存为待确认判断
            </button>
            <button
              type="button"
              onClick={() => {
                if (
                  !window.confirm(
                    "清空未保存的输入？已保存候选需到审核页明确拒绝。",
                  )
                )
                  return;
                navigationGuard.dirty = false;
                setDirty(false);
                window.location.hash = "/judgments";
              }}
            >
              这次不记，离开
            </button>
          </div>
        </fieldset>
      </form>
      {candidate && !terminal && !published && (
        <section className="panel">
          <h2>03 · 人工确认</h2>
          <p>
            确认你认可的是判断规则和适用边界。发布后内容冻结；修改需要新版本。
          </p>
          <label className="check">
            <input
              type="checkbox"
              checked={confirmed}
              disabled={dirty}
              onChange={(e) => setConfirmed(e.target.checked)}
            />
            我已核对规则、约束与反例，愿意在上述条件下保留它
          </label>
          <Field label="确认说明">
            <textarea
              value={reason}
              maxLength={2000}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
          <button
            className="primary"
            disabled={task.busy || dirty || !confirmed || !reason.trim()}
            onClick={() =>
              void task.run(async () => {
                const v = await api.v2<Row>(
                  `/judgments/candidates/${candidate.id}/publish`,
                  {
                    expectedRevision: candidate.revision,
                    reason,
                    familyId: source?.family_id,
                    expectedSupersedesId: source?.id,
                  },
                );
                setPublished(v);
                setDirty(false);
                task.setMessage("已发布；共享给 Agent 还需单独审核。");
              })
            }
          >
            <ShieldCheck size={17} /> 确认并发布
            {source || input?.sourceVersionId ? "后继版本" : ""}
          </button>
          <a className="button" href={`#/review/${candidate.id}`}>
            拒绝、合并或查看完整审核
          </a>
          <button
            disabled={task.busy}
            onClick={() => {
              if (dirty && !window.confirm("丢弃本地修改并重新加载？")) return;
              navigationGuard.dirty = false;
              if (idFromHash() === candidate.id) reload();
              else window.location.hash = `/judgments/review/${candidate.id}`;
            }}
          >
            重新加载服务器草稿
          </button>
        </section>
      )}
      {terminal && (
        <p>
          该候选已结束审核：
          <Badge value={candidate!.status} />
          {candidate?.target_version_id && (
            <a href={`#/judgments/version/${candidate.target_version_id}`}>
              查看已发布版本
            </a>
          )}
        </p>
      )}
    </>
  );
}
export function JudgmentDetail({ api, id }: { api: LedgerApi; id: string }) {
  const data = useLoad(() => api.v2<Row>(`/judgments/${id}`), [api, id]),
    task = useAction();
  const [mode, setMode] = useState("CROSS_REFERENCE"),
    [method, setMethod] = useState("TEST"),
    [reason, setReason] = useState(""),
    [evidence, setEvidence] = useState(""),
    [checked, setChecked] = useState(false);
  useEffect(() => {
    setChecked(false);
    setReason("");
    setEvidence("");
    setMode("CROSS_REFERENCE");
  }, [id]);
  if (data.loading) return <Loading />;
  if (data.error) return <ErrorBox error={data.error} />;
  const v = data.data!,
    rule = v.judgmentRule as JudgmentRule | null,
    sharing = v.sharing,
    grant = sharing.grant;
  const live =
    v.status === "VERIFIED" &&
    (!v.valid_to || new Date(v.valid_to).getTime() > Date.now());
  return (
    <>
      <a className="back-link" href="#/judgments">
        <ArrowLeft size={16} /> 返回判断库
      </a>
      <PageTitle
        eyebrow={`${sharing.native_track} / ${v.status}`}
        title={v.title}
        actions={
          rule && live ? (
            <a className="button" href={`#/judgments/new?source=${v.id}`}>
              修订为新版本
            </a>
          ) : undefined
        }
      >
        {rule?.futureDecision || v.summary}
      </PageTitle>
      <ErrorBox error={task.error} />
      <Success>{task.message}</Success>
      {rule ? (
        <div className="judgment-grid">
          {Object.entries(ruleLabels).map(([k, label]) => (
            <section
              className={`panel judgment-section ${k === "hardConstraints" ? "judgment-constraints" : ""}`}
              key={k}
            >
              <h2>{label}</h2>
              {Array.isArray(rule[k as keyof JudgmentRule]) ? (
                <ul>
                  {(rule[k as keyof JudgmentRule] as string[]).map((s, i) => (
                    <li key={i}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p>{String(rule[k as keyof JudgmentRule] || "未填写")}</p>
              )}
            </section>
          ))}
        </div>
      ) : (
        <section className="panel">
          <h2>经验正文</h2>
          <p>{v.lesson}</p>
          <p>{v.decision}</p>
          <p>{v.action}</p>
        </section>
      )}
      <section className="panel">
        <h2>使用后，记录结果</h2>
        {["DISPUTED", "DEPRECATED"].includes(
          v.validations?.[0]?.validation_status,
        ) && (
          <p className="notice error">
            此规则已被标记为有争议或弃用，Gateway 不会提供。请先重审。
          </p>
        )}
        <JsonView title="独立验证与置信度历史" value={v.validations} />
        <p className="muted">
          作者自评不会提高检索验证分。需要人工评估时，可到「知识压缩」页追加版本验证记录。
        </p>
        <a className="button" href="#/compacts">
          评估验证状态与置信度
        </a>
        <p>
          这条判断是否改变了你的选择？结果是否符合预期？结果记录不会自动提高置信度。
        </p>
        <a className="button" href={`#/usage?version=${id}`}>
          记录使用与结果
        </a>
        <a
          className="button"
          href={`#/experiences/${v.family_id}?version=${id}`}
        >
          查看证据、版本和验证信息
        </a>
      </section>
      <section className="panel">
        <h2>共享给 {sharing.native_track === "HUMAN" ? "Agent" : "人"}</h2>
        <p>
          当前授权：
          <strong>{reuseLabels[grant?.reuse_mode || "NATIVE_ONLY"]}</strong>
          {grant && ` · ${date(grant.created_at)}`}
        </p>
        {!live && (
          <p className="notice">该版本已失效或被替代，不再供 Gateway 使用。</p>
        )}
        {grant?.evidence_active === false && (
          <p className="notice">
            共享验证证据已更正。Gateway 已停止提供，需要重新验证并授权。
          </p>
        )}
        <p className="muted">
          共享只针对这个版本、当前空间内策略允许的身份。后继版本需要重新审核；参考材料不会变成可执行指令。
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void task.run(async () => {
              await api.v2(`/versions/${id}/reuse`, {
                targetTrack:
                  sharing.native_track === "HUMAN" ? "AGENT" : "HUMAN",
                mode,
                validationMethod:
                  mode === "CROSS_REUSABLE" ? method : "HUMAN_REVIEW",
                reason,
                evidenceIds:
                  mode === "CROSS_REUSABLE"
                    ? lines(evidence).map((x) => requireId(x, "验证证据 ID"))
                    : [],
                expectedPreviousId: grant?.id || null,
              });
              setChecked(false);
              task.setMessage(
                mode === "NATIVE_ONLY"
                  ? "已撤回跨侧共享。"
                  : "已记录共享审核。Gateway 仍会检查策略、证据、适用范围和预算。",
              );
              data.reload();
            });
          }}
        >
          <fieldset disabled={task.busy}>
            <Field label="共享方式">
              <select
                value={mode}
                onChange={(e) => {
                  setMode(e.target.value);
                  setChecked(false);
                }}
              >
                <option value="CROSS_REFERENCE">供参考：仍需逐次判断</option>
                <option value="CROSS_REUSABLE">
                  已验证复用：需测试或回放依据
                </option>
                <option value="NATIVE_ONLY">撤回共享：仅原生侧保留</option>
              </select>
            </Field>
            {mode === "CROSS_REUSABLE" && (
              <>
                <Field label="验证方法">
                  <select
                    value={method}
                    onChange={(e) => setMethod(e.target.value)}
                  >
                    <option value="TEST">测试</option>
                    <option value="REPLAY">历史回放</option>
                  </select>
                </Field>
                <Field label="验证证据 ID（每行一个）">
                  <textarea
                    required
                    value={evidence}
                    onChange={(e) => setEvidence(e.target.value)}
                  />
                </Field>
                <a href="#/evidence">录入测试或回放证据</a>
              </>
            )}
            <Field label="审核依据 / 撤回原因">
              <textarea
                required
                maxLength={2000}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </Field>
            <label className="check">
              <input
                type="checkbox"
                checked={checked}
                onChange={(e) => setChecked(e.target.checked)}
              />
              我已核对适用范围、边界与验证依据
            </label>
            <button
              className="primary"
              disabled={!checked || (!live && mode !== "NATIVE_ONLY")}
            >
              确认{mode === "NATIVE_ONLY" ? "撤回" : "共享"}
            </button>
          </fieldset>
        </form>
        <JsonView
          title="本次共享的验证证据引用"
          value={grant?.validation_evidence || []}
        />
        <h3>共享历史</h3>
        {v.reuseHistory.map((e: Row) => (
          <p key={e.id}>
            {date(e.created_at)} · {reuseLabels[e.reuse_mode]} · {e.reason}
          </p>
        ))}
      </section>
      <JsonView
        title="结构化条件与来源信息"
        value={{
          applicability: v.applicability_json,
          constraints: v.constraints_json,
          sharing,
          authorConfidence: rule?.authorConfidence,
        }}
      />
      <a className="button" href={`#/audit?target=${id}`}>
        查看版本审计
      </a>
    </>
  );
}
