// A real-backend contract check for the same client and Draft builder used by the UI.
// Requires a running ledger and HUMAN test token. Creates records in that token's space.
import assert from "node:assert/strict";
import { LedgerApi, ApiError } from "../src/api.ts";
import { draftFromCandidate, validateDraft } from "../src/domain.ts";
import type { Candidate, Row, SearchResult, Experience } from "../src/types.ts";
const base = process.env.LEDGER_URL || "http://127.0.0.1:8080";
const token = process.env.LEDGER_TOKEN;
if (!token)
  throw new Error(
    "Set LEDGER_TOKEN to a HUMAN credential for an isolated test space",
  );
const transport: typeof fetch = (path, options) =>
  fetch(new URL(String(path), base), options);
const api = new LedgerApi(token, transport);
const now = new Date().toISOString(),
  key = `frontend-contract-${crypto.randomUUID()}`;
const evidence = await api.request<Row>("/evidence", {
  evidenceType: "TEST_RESULT",
  snapshot: { passed: 12 },
  observedAt: now,
  reliability: 0.9,
});
const captured = await api.request<Candidate>("/candidates/capture", {
  content: "前端契约验证记录",
  dedupKey: key,
});
assert.equal(
  (
    await api.request<Candidate>("/candidates/capture", {
      content: "前端契约验证记录",
      dedupKey: key,
    })
  ).id,
  captured.id,
);
let candidate = await api.request<Candidate>(`/candidates/${captured.id}`);
const draft = draftFromCandidate(candidate);
Object.assign(draft, {
  title: "前端客户端契约验证",
  summary: "验证真实后端闭环",
  lesson: "保留证据与版本引用",
  claims: [
    {
      claimType: "OBSERVATION",
      originType: "OBSERVED",
      content: "客户端验证通过",
      evidence: [{ evidenceId: evidence.id, supportType: "SUPPORTS" }],
    },
  ],
});
validateDraft(draft);
// Worker may race the first review; an integration harness can re-read the candidate,
// while the interactive UI deliberately asks the reviewer to resolve revision conflicts.
for (let i = 0; ; i++) {
  try {
    candidate = await api.request<Candidate>(
      `/candidates/${captured.id}/review`,
      {
        expectedRevision: candidate.revision,
        draft,
        reason: "frontend contract test",
      },
    );
    break;
  } catch (e) {
    if (
      !(e instanceof ApiError && e.code === "CANDIDATE_REVISION_CONFLICT") ||
      i >= 3
    )
      throw e;
    candidate = await api.request<Candidate>(`/candidates/${captured.id}`);
  }
}
await assert.rejects(
  () =>
    api.request(`/candidates/${candidate.id}/review`, {
      expectedRevision: -1,
      draft,
    }),
  (e: unknown) => e instanceof ApiError && e.status === 409,
);
const version = await api.request<Row>(`/candidates/${candidate.id}/verify`, {
  mode: "CREATE_NEW_FAMILY",
  expectedRevision: candidate.revision,
  experienceKey: key,
  domain: "frontend_contract",
  experienceType: "BEST_PRACTICE",
  reason: "frontend contract test",
});
const found = await api.request<SearchResult>("/experiences/search", {
  query: "",
  domain: "frontend_contract",
  limit: 100,
});
const pack = found.results.find((x) => x.versionId === version.id)!;
assert.ok(pack);
assert.equal(pack.keyClaims[0].origin_type, "OBSERVED");
assert.equal(pack.keyEvidence[0].id, evidence.id);
assert.ok(pack.whyMatched);
assert.equal(
  (
    await api.request<Experience[]>(`/experiences/${version.family_id}/history`)
  )[0].versionId,
  version.id,
);
const usage = await api.request<Row>("/usages", {
  versionId: version.id,
  actuallyUsed: true,
  recommended: true,
});
await api.request(`/usages/${usage.id}/outcomes`, {
  outcomeType: "SUCCESS",
  observedAt: now,
  metrics: { clientPassed: true },
});
await api.request(`/usages/${usage.id}/outcomes`, {
  outcomeType: "PARTIAL_SUCCESS",
  observedAt: now,
  notes: "Second observation",
});
assert.equal(
  (await api.request<Row[]>(`/usages/${usage.id}/outcomes`)).length,
  2,
);
const replacement = await api.request<Row>(
  `/evidence/${evidence.id}/correct?reason=frontend-contract`,
  { evidenceType: "TEST_RESULT", snapshot: { passed: 13 }, observedAt: now },
);
assert.equal(
  (await api.request<Row>(`/evidence/${evidence.id}`)).corrected_by_evidence_id,
  replacement.id,
);
assert.ok((await api.request<Row[]>("/audit?limit=100")).length > 0);
console.log(
  JSON.stringify(
    {
      checks: "PASS",
      client: "frontend/src/api.ts",
      flow: [
        "evidence",
        "idempotent capture",
        "review",
        "revision conflict",
        "verify",
        "search",
        "history",
        "usage",
        "two outcomes",
        "evidence correction",
        "audit",
      ],
    },
    null,
    2,
  ),
);
