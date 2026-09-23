import { useEffect, useState } from "react";
import {
  ArrowRight,
  Bot,
  Check,
  ChevronRight,
  FileText,
  MessageSquareText,
  Plus,
  RefreshCw,
  Save,
  Sparkles,
  X,
} from "lucide-react";
import { LedgerApi, query } from "../api";
import type {
  AuthoringDraft,
  AuthoringDraftContent,
  ReviewInboxItem,
  Row,
} from "../types";
import { experienceTypes } from "../types";
import { objectJson, pretty } from "../domain";
import {
  Badge,
  date,
  Empty,
  ErrorBox,
  Field,
  go,
  JsonView,
  Loading,
  PageTitle,
  SelectOptions,
  Success,
  useAction,
  useLoad,
  navigationGuard,
} from "../components/ui";

export function CapturePageV12({ api }: { api: LedgerApi }) {
  const [rawContent, setRaw] = useState("");
  const [sourceType, setSource] = useState("MANUAL_TEXT");
  const [sourceRef, setRef] = useState("");
  const [context, setContext] = useState("{}");
  const [evidence, setEvidence] = useState("");
  const [result, setResult] = useState<AuthoringDraft>();
  const task = useAction();
  return (
    <>
      <PageTitle eyebrow="CAPTURE / 01" title="描述发生了什么">
        保留原始事实即可。结构化、Claim 拆分和缺口识别由 Ledger 完成。
      </PageTitle>
      <div className="capture-focus">
        <form
          className="panel capture-card"
          onSubmit={(event) => {
            event.preventDefault();
            void task.run(async () => {
              const refs = evidence
                .split(/[,\n]/)
                .map((x) => x.trim())
                .filter(Boolean);
              const draft = await api.v2<AuthoringDraft>("/capture/human", {
                rawContent,
                sourceType,
                sourceRef: sourceRef || undefined,
                taskContext: objectJson(context, "附加上下文"),
                evidenceRefs: refs,
                language: "zh-CN",
                generateDraft: true,
                dedupKey: crypto.randomUUID(),
              });
              setResult(draft);
              task.setMessage(
                draft.status === "GENERATION_FAILED"
                  ? "原始记录已安全保存，但草稿生成失败。可进入审核页重新生成。"
                  : "AI 草稿已生成，请核对关键判断后发布。",
              );
            });
          }}
        >
          <div className="capture-prompt">
            <Sparkles size={22} />
            <b>只写以后还会改变你决策的部分</b>
          </div>
          <textarea
            className="raw-capture"
            rows={14}
            required
            maxLength={100000}
            value={rawContent}
            onChange={(e) => setRaw(e.target.value)}
            placeholder="粘贴原始对话、Issue、执行记录或日志。建议包含：当时的问题、约束、尝试、结果，以及你最后如何判断。"
          />
          <details className="advanced">
            <summary>附加上下文与已有 Evidence（可选）</summary>
            <p className="micro">原始输入会自动留为来源证据；如有独立日志、工单或测试结果，可填写已入账的 Evidence ID 增强佐证。</p>
            <div className="form-grid">
              <Field label="来源类型">
                <select
                  value={sourceType}
                  onChange={(e) => setSource(e.target.value)}
                >
                  {[
                    "MANUAL_TEXT",
                    "CONVERSATION",
                    "ISSUE",
                    "GIT_CHANGE",
                    "BUILD_LOG",
                    "AGENT_RUN",
                    "OTHER",
                  ].map((x) => (
                    <option key={x}>{x}</option>
                  ))}
                </select>
              </Field>
              <Field label="来源引用">
                <input
                  value={sourceRef}
                  onChange={(e) => setRef(e.target.value)}
                  placeholder="issue://123 或 conversation://456"
                />
              </Field>
              <Field label="任务上下文 JSON">
                <textarea
                  className="code-input"
                  value={context}
                  onChange={(e) => setContext(e.target.value)}
                  placeholder='{"domain":"engineering","project":"service-a"}'
                />
              </Field>
              <Field
                label="Evidence ID / sourceRef"
                hint="每行一个；只会关联 Ledger 中已存在的 Evidence。"
              >
                <textarea
                  value={evidence}
                  onChange={(e) => setEvidence(e.target.value)}
                />
              </Field>
            </div>
          </details>
          <ErrorBox error={task.error} />
          <Success>{task.message}</Success>
          <div className="actions">
            <button
              className="primary"
              disabled={task.busy || !rawContent.trim()}
            >
              <Sparkles size={17} />
              {task.busy ? "正在提炼…" : "生成经验草稿"}
            </button>
            {result && (
              <button type="button" onClick={() => go(`/drafts/${result.id}`)}>
                进入审核 <ArrowRight size={16} />
              </button>
            )}
          </div>
        </form>
        <aside className="panel capture-principles">
          <div className="eyebrow">WRITE-LIGHT</div>
          <h2>人提供事实，AI 负责整理</h2>
          <div className="principle-item">
            <b>不积累一次性答案</b>
            <span>提炼问题、判断规则、约束和 trade-off。</span>
          </div>
          <div className="principle-item">
            <b>不补造缺失事实</b>
            <span>无法确认的根因或结果会进入“待确认信息”。</span>
          </div>
          <div className="principle-item">
            <b>不自动发布</b>
            <span>AI 只生成 Draft，正式经验仍需人的显式确认。</span>
          </div>
        </aside>
      </div>
    </>
  );
}

export function ReviewInboxPage({ api }: { api: LedgerApi }) {
  const [state, setState] = useState("PENDING");
  const [channel, setChannel] = useState("");
  const [domain, setDomain] = useState("");
  const inbox = useLoad(
    (signal) =>
      api.v2<ReviewInboxItem[]>(
        `/review/inbox${query({ state, channel, domain, limit: 100 })}`,
        undefined,
        signal,
      ),
    [api, state, channel, domain],
  );
  const counts = useLoad<Row>(
    (signal) => api.v2("/review/count", undefined, signal),
    [api],
  );
  return (
    <>
      <PageTitle
        eyebrow="REVIEW / 02"
        title="待审核经验"
        actions={
          <a className="button primary" href="#/capture">
            <Plus size={17} /> 新建采集
          </a>
        }
      >
        审核 AI 提炼出的判断、边界和证据关系，不必重新填写整张表单。
      </PageTitle>
      <div className="review-stats">
        <button onClick={() => setState("PENDING")}>
          <b>{counts.data?.pending ?? "—"}</b><span>待审核</span>
        </button>
        <button onClick={() => setState("PENDING")}>
          <b>{counts.data?.needs_input ?? "—"}</b><span>待补充</span>
        </button>
        <button onClick={() => setState("FAILED")}>
          <b>{counts.data?.failed ?? "—"}</b><span>生成失败</span>
        </button>
        <button onClick={() => setState("ACCEPTED")}>
          <b>{counts.data?.accepted ?? "—"}</b><span>最近发布</span>
        </button>
      </div>
      <div className="toolbar panel">
        <Field label="队列">
          <select value={state} onChange={(e) => setState(e.target.value)}>
            <option value="PENDING">待审核</option>
            <option value="FAILED">生成失败</option>
            <option value="ACCEPTED">已发布</option>
            <option value="REJECTED">已拒绝</option>
          </select>
        </Field>
        <Field label="来源">
          <select value={channel} onChange={(e) => setChannel(e.target.value)}>
            <option value="">Human + Agent</option>
            <option value="HUMAN">Human</option>
            <option value="AGENT">Agent</option>
            <option value="EXTERNAL">External</option>
          </select>
        </Field>
        <Field label="领域">
          <input
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            placeholder="全部领域"
          />
        </Field>
        <button onClick={inbox.reload} disabled={inbox.loading}>
          <RefreshCw size={17} /> 刷新
        </button>
      </div>
      <ErrorBox error={inbox.error || counts.error} />
      {inbox.loading ? (
        <Loading />
      ) : inbox.data?.length ? (
        <div className="inbox-list">
          {inbox.data.map((item) => (
            <a className="inbox-row" href={`#/drafts/${item.id}`} key={item.id}>
              <span className={`source-orb ${item.captureChannel.toLowerCase()}`}>
                {item.captureChannel === "AGENT" ? <Bot size={18} /> : <FileText size={18} />}
              </span>
              <span className="inbox-content">
                <span className="row-title">{item.title}</span>
                <span className="micro">{item.summary || item.error}</span>
                <span className="inbox-tags">
                  <Badge value={item.captureChannel} />
                  {item.domain && <span>{item.domain}</span>}
                  {item.taskType && <span>{item.taskType}</span>}
                  {item.missingCount > 0 && <em>待确认 {item.missingCount}</em>}
                </span>
              </span>
              <span className="inbox-meta">
                <span>置信度 {Math.round((item.confidence || 0) * 100)}%</span>
                <span>{date(item.createdAt)}</span>
                <span>{item.provider} / {item.model}</span>
              </span>
              <ChevronRight size={19} />
            </a>
          ))}
        </div>
      ) : (
        !inbox.error && <Empty title="当前队列为空">新的 Human 或 Agent 草稿会出现在这里。</Empty>
      )}
    </>
  );
}

function lines(value: string[]) { return value.join("\n"); }
function fromLines(value: string) { return value.split("\n").map((x) => x.trim()).filter(Boolean); }

export function DraftReviewPage({ api, id }: { api: LedgerApi; id: string }) {
  const loaded = useLoad<AuthoringDraft>(
    (signal) => api.v2(`/drafts/${id}`, undefined, signal),
    [api, id],
  );
  return (
    <>
      <PageTitle
        eyebrow="REVIEW / DRAFT"
        title="核对经验草稿"
        actions={<a className="button" href="#/review">返回审核箱</a>}
      />
      <ErrorBox error={loaded.error} />
      {loaded.loading ? <Loading /> : loaded.data && <DraftEditor api={api} initial={loaded.data} />}
    </>
  );
}

function DraftEditor({ api, initial }: { api: LedgerApi; initial: AuthoringDraft }) {
  const [draft, setDraft] = useState(initial);
  const [doc, setDoc] = useState(() => structuredClone(initial.structuredContent));
  const [instruction, setInstruction] = useState("");
  const [reason, setReason] = useState("确认 AI 草稿的关键判断与适用边界");
  const [type, setType] = useState("DECISION");
  const [advanced, setAdvanced] = useState(() => pretty({ claims: doc.claims, evidenceMappings: doc.evidenceMappings }));
  const [dirty, setDirty] = useState(false);
  const [published, setPublished] = useState<Row>();
  const [relatedFamily, setRelatedFamily] = useState("");
  const [problemRelation, setProblemRelation] = useState("ALTERNATIVE_CAUSE");
  const task = useAction();
  const terminal = ["ACCEPTED", "REJECTED"].includes(draft.status);
  useEffect(() => { navigationGuard.dirty = dirty; return () => { navigationGuard.dirty = false; }; }, [dirty]);
  const replace = (next: AuthoringDraft) => {
    setDraft(next); setDoc(structuredClone(next.structuredContent));
    setAdvanced(pretty({ claims: next.structuredContent.claims, evidenceMappings: next.structuredContent.evidenceMappings }));
    setDirty(false); navigationGuard.dirty = false;
  };
  const edit = (patch: Partial<AuthoringDraftContent>) => { setDoc((x) => ({ ...x, ...patch })); setDirty(true); setRelatedFamily(""); };
  const content = () => {
    const parsed = JSON.parse(advanced || "{}");
    if (!Array.isArray(parsed.claims) || !Array.isArray(parsed.evidenceMappings)) throw new Error("Claims / Evidence Mapping 必须是 JSON 数组");
    return { ...doc, claims: parsed.claims, evidenceMappings: parsed.evidenceMappings };
  };
  const continueWith = (next: AuthoringDraft, message: string) => { replace(next); task.setMessage(message); if (next.id !== draft.id) go(`/drafts/${next.id}`); };
  return (
    <>
      <div className="record-strip">
        <Badge value={draft.status} /><span>Draft V{draft.draftVersion}</span>
        <span>{draft.provider} / {draft.model}</span>
        <span className="micro">Prompt {draft.promptCode} v{draft.promptVersion}</span>
        <span>结论 {(doc.claims?.length ?? 0)} 条 · 证据关联 {(doc.evidenceMappings?.length ?? 0)} 条</span>
      </div>
      <ErrorBox error={task.error} /><Success>{task.message}</Success>
      {(doc.claims?.length ?? 0) === 0 && !terminal && draft.status !== "GENERATION_FAILED" && <div className="notice warning">当前草稿尚未拆出独立结论。直接发布只会生成一条保守的兜底结论；建议先修订或补充结论。</div>}
      {published && <div className="notice success">已发布为正式经验。<a href={`#/experiences/${published.family_id}`}>查看 Experience V{published.version_no}</a></div>}
      {draft.status === "GENERATION_FAILED" && <div className="notice error">草稿生成失败：{draft.errorSummary}<button disabled={task.busy} onClick={() => void task.run(async () => continueWith(await api.v2<AuthoringDraft>(`/drafts/${draft.id}/regenerate`, { expectedDraftVersion: draft.draftVersion, reason: "retry after generation failure" }), "已重新生成。"))}>重新生成</button></div>}
      <div className="draft-review-layout">
        <aside className="panel source-pane">
          <div className="section-heading"><h2>原始输入</h2><Badge value={draft.candidate?.source_type || "SOURCE"} /></div>
          <div className="raw-source">{draft.candidate?.raw_content}</div>
          <details><summary>草稿版本历史</summary><JsonView value={draft.history || []} /></details>
          {!!draft.similar?.length && <div className="similar-block"><h3>系统发现的关联案例</h3>{draft.similar.map((x) => <div key={x.version_id}><a href={`#/experiences/${x.family_id}`} target="_blank" rel="noreferrer">{x.title}</a><p className="micro">{x.matchReason} · 根因：{x.root_cause || "尚未确认"}{x.group_title ? ` · 问题组：${x.group_title}` : ""}</p></div>)}</div>}
        </aside>
        <main className="panel draft-pane">
          <fieldset disabled={terminal || task.busy || draft.status === "GENERATION_FAILED"}>
            <div className="section-heading"><h2>AI Draft</h2><span className="confidence">置信度 {Math.round(Number(doc.confidence?.overall || 0) * 100)}%</span></div>
            {!!doc.missingInformation?.length && <div className="missing-box"><b>AI 发现 {doc.missingInformation.length} 个待确认信息</b>{doc.missingInformation.map((x, i) => <p key={i}><span>{x.field}</span>{x.message}</p>)}</div>}
            <Field label="标题"><input required value={doc.title} onChange={(e) => edit({ title: e.target.value })} /></Field>
            <Field label="摘要"><textarea required value={doc.summary} onChange={(e) => edit({ summary: e.target.value })} /></Field>
            <div className="form-grid">
              <Field label="领域"><input value={doc.domain} onChange={(e) => edit({ domain: e.target.value })} /></Field>
              <Field label="任务类型"><input value={doc.taskType} onChange={(e) => edit({ taskType: e.target.value })} /></Field>
            </div>
            {(["problem", "context", "rootCause", "decision", "outcome", "lesson", "reusablePrinciple"] as const).map((field) => <Field key={field} label={{problem:"问题",context:"情境",rootCause:"根因（可为空）",decision:"决策",outcome:"结果",lesson:"经验",reusablePrinciple:"可复用判断规则"}[field]}><textarea value={doc[field]} onChange={(e) => edit({ [field]: e.target.value })} /></Field>)}
            <div className="form-grid">
              {(["actions", "applicability", "boundaryConditions", "constraints", "alternatives", "tradeoffs"] as const).map((field) => <Field key={field} label={{actions:"行动（每行一项）",applicability:"适用条件",boundaryConditions:"边界条件",constraints:"约束",alternatives:"备选方案",tradeoffs:"权衡"}[field]}><textarea value={lines(doc[field])} onChange={(e) => edit({ [field]: fromLines(e.target.value) })} /></Field>)}
            </div>
            <details className="advanced"><summary>结论与证据关联（{(doc.claims?.length ?? 0)} / {(doc.evidenceMappings?.length ?? 0)}）</summary><p className="micro">来源快照仅作 CONTEXT，独立证据才可支持观察结论；AI 生成的结论保留 AGENT_DERIVED 来源。Evidence 只能引用已存在的 ID。</p><textarea className="code-input" rows={12} value={advanced} onChange={(e) => { setAdvanced(e.target.value); setDirty(true); }} /></details>
            <Field label="审核 / 发布理由"><textarea value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            <div className="actions">
              <button type="button" disabled={!dirty} onClick={() => void task.run(async () => continueWith(await api.v2<AuthoringDraft>(`/drafts/${draft.id}/edit`, { expectedDraftVersion: draft.draftVersion, structuredContent: content(), reason }), "手工修改已保存为新的 Draft Version。"))}><Save size={16} />保存修改</button>
              <button type="button" onClick={() => void task.run(async () => { const next = await api.v2<AuthoringDraft>(`/drafts/${draft.id}/regenerate`, { expectedDraftVersion: draft.draftVersion, reason: "human requested regeneration" }); continueWith(next, "已重新生成完整草稿，旧版本仍可追溯。"); })}><RefreshCw size={16} />重新生成</button>
            </div>
            <div className="revision-chat"><MessageSquareText size={20} /><div><b>告诉 AI 你想怎么改</b><textarea value={instruction} onChange={(e) => setInstruction(e.target.value)} placeholder="例如：不要把 Reactor 写成唯一根因；把它改为优先排查项，并补充适用边界。" /></div><button type="button" className="primary" disabled={!instruction.trim()} onClick={() => void task.run(async () => { const next = await api.v2<AuthoringDraft>(`/drafts/${draft.id}/revise`, { instruction, expectedDraftVersion: draft.draftVersion }); continueWith(next, "AI 已生成新的草稿版本，请核对变更。"); })}><Sparkles size={16} />生成修订</button></div>
            <div className="panel problem-link-picker">
              <h3>与已有问题的关系</h3>
              <p className="micro">每次发布都保留独立经验和证据。系统仅推荐关联，不会自动覆盖已有原因。</p>
              <label><input type="radio" name="relatedFamily" checked={!relatedFamily} onChange={() => setRelatedFamily("")} /> 独立发布，暂不归组</label>
              {draft.similar?.map((item) => <label key={item.family_id}>
                <input type="radio" name="relatedFamily" value={item.family_id} checked={relatedFamily === item.family_id} onChange={() => setRelatedFamily(item.family_id)} />
                同一问题：{item.title}（已有根因：{item.root_cause || "待确认"}）
              </label>)}
              {relatedFamily && <Field label="本次和已有经验的关系"><select value={problemRelation} onChange={(event) => setProblemRelation(event.target.value)}><option value="ALTERNATIVE_CAUSE">另一种原因及处理方法</option><option value="SAME_CAUSE_CASE">相同原因的独立案例</option></select></Field>}
              {!draft.similar?.length && <p className="micro">尚无相近案例；照常发布，之后仍可在经验详情页归组。</p>}
            </div>
            <div className="publish-box">
              <div><b>确认后写入组织经验</b><p>发布将生成 Experience、Claim、Evidence 关系以及 L0/L1/L2 摘要。</p></div>
              <select value={type} onChange={(e) => setType(e.target.value)}><SelectOptions values={experienceTypes} /></select>
              <button type="button" className="primary" disabled={task.busy || terminal} onClick={() => void task.run(async () => {
                let publishDraft = draft;
                if (dirty) {
                  task.setMessage("正在保存手工修改…");
                  publishDraft = await api.v2<AuthoringDraft>(`/drafts/${draft.id}/edit`, {
                    expectedDraftVersion: draft.draftVersion,
                    structuredContent: content(),
                    reason,
                  });
                  replace(publishDraft);
                }
                task.setMessage("正在发布正式经验…");
                const result = await api.v2<Row>(`/drafts/${publishDraft.id}/accept`, {
                  expectedDraftVersion: publishDraft.draftVersion,
                  mode: "CREATE_NEW_FAMILY",
                  domain: publishDraft.structuredContent.domain || "general",
                  experienceType: type,
                  reason,
                  relatedFamilyId: relatedFamily || undefined,
                  problemRelation: relatedFamily ? problemRelation : undefined,
                });
                setPublished(result.experience);
                setDraft((x) => ({ ...x, status: "ACCEPTED" }));
                navigationGuard.dirty = false;
                task.setMessage("正式经验已发布，原始 Candidate 与 Draft 版本链均已保留。");
              })}><Check size={17} />{task.busy ? "正在处理…" : dirty ? "保存修改并发布" : "接受并发布"}</button>
              <button type="button" className="danger" onClick={() => void task.run(async () => { if (!window.confirm("拒绝后仍保留 Candidate 与 Draft 审计记录。继续？")) return; await api.v2(`/drafts/${draft.id}/reject`, { expectedDraftVersion: draft.draftVersion, reason }); go("/review"); })}><X size={17} />拒绝</button>
            </div>
          </fieldset>
        </main>
      </div>
      {!!draft.diff?.length && <div className="panel"><h2>本次字段变更</h2>{draft.diff.map((x, i) => <div className="diff-row" key={i}><b>{x.field}</b><pre className="removed">- {typeof x.before === "string" ? x.before : pretty(x.before)}</pre><pre className="added">+ {typeof x.after === "string" ? x.after : pretty(x.after)}</pre></div>)}</div>}
    </>
  );
}
