import type { Candidate, Draft, Row } from "./types";
export const pretty = (value: unknown) => JSON.stringify(value ?? {}, null, 2);
export const localNow = () => toLocal(new Date().toISOString());
export function toLocal(value?: string | null): string {
  if (!value) return "";
  const d = new Date(value);
  if (!Number.isFinite(d.getTime())) return "";
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
}
export function instant(value: string): string {
  const d = new Date(value);
  if (!value || !Number.isFinite(d.getTime()))
    throw new Error("请输入有效日期与时间");
  return d.toISOString();
}
export function objectJson(value: string, label = "JSON"): Row {
  const parsed = JSON.parse(value || "{}");
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
    throw new Error(`${label} 必须是 JSON 对象`);
  return parsed;
}
export function arrayJson<T>(value: string, label = "JSON"): T[] {
  const parsed = JSON.parse(value || "[]");
  if (!Array.isArray(parsed)) throw new Error(`${label} 必须是 JSON 数组`);
  return parsed;
}
export function requireId(value: string, label = "ID"): string {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value.trim(),
    )
  )
    throw new Error(`${label} 需要有效 UUID`);
  return value.trim();
}
export function draftFromCandidate(candidate: Candidate): Draft {
  const extracted = candidate.extracted_json || {};
  const source = extracted.draft || extracted;
  // Copy only request fields. Never send capturedEvidenceIds/provider metadata as Draft fields.
  return {
    title: source.title || "",
    summary: source.summary || "",
    problem: source.problem || "",
    decision: source.decision || "",
    action: source.action || "",
    outcomeSummary: source.outcomeSummary || "",
    lesson: source.lesson || "",
    validFrom: source.validFrom || new Date().toISOString(),
    validTo: source.validTo || null,
    applicability: source.applicability || {},
    constraints: source.constraints || {},
    claims: structuredClone(source.claims || []),
    contextRefs: structuredClone(source.contextRefs || []),
    episodes: structuredClone(source.episodes || []),
  };
}
export function validateDraft(draft: Draft, original?: Draft): void {
  for (const key of ["title", "summary", "lesson"] as const)
    if (!draft[key].trim()) throw new Error("请填写标题、摘要和经验结论");
  if (new Date(draft.validFrom).getTime() > Date.now())
    throw new Error("生效时间不能晚于当前时间");
  if (draft.validTo && new Date(draft.validTo) <= new Date(draft.validFrom))
    throw new Error("失效时间必须晚于生效时间");
  if (!draft.claims.length) throw new Error("至少添加一条 Claim");
  for (const c of draft.claims) {
    if (!c.content.trim()) throw new Error("Claim 内容不能为空");
    if (c.originType.endsWith("_DERIVED") && !c.derivationMethod?.trim())
      throw new Error("推导结论必须填写推导方法");
    if (
      c.originType === "OBSERVED" &&
      (c.claimType !== "OBSERVATION" ||
        !c.evidence?.some((e) => e.supportType === "SUPPORTS"))
    )
      throw new Error("直接观察必须使用 OBSERVATION 并关联 SUPPORTS 证据");
    c.evidence?.forEach((e) => requireId(e.evidenceId, "证据 ID"));
    for (const old of original?.claims || [])
      if (
        old.content === c.content &&
        old.originType === "AGENT_DERIVED" &&
        (c.originType !== "AGENT_DERIVED" ||
          (old.claimType === "CAUSAL_HYPOTHESIS" && c.claimType === "RULE"))
      )
        throw new Error(
          "不能将 Agent 推导重新标记为观察或规则；请保留来源并新增有证据支持的结论",
        );
  }
}
export function allowedDispositions(status: string): string[] {
  return status === "PENDING_REVIEW"
    ? ["REJECTED", "MERGED"]
    : ["NEW", "ENRICHED"].includes(status)
      ? ["REJECTED", "DUPLICATE", "EXPIRED"]
      : [];
}
