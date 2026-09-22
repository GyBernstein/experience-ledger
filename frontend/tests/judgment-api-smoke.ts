// Real HTTP workflow. Writes only to the isolated test identity's space.
import assert from "node:assert/strict";
import { LedgerApi, ApiError } from "../src/api";
import type { Row, Candidate } from "../src/types";
import { emptyRule } from "../src/judgment";
const base = process.env.LEDGER_URL || "http://127.0.0.1:8080",
  token = process.env.LEDGER_TOKEN;
if (!token)
  throw new Error("Set LEDGER_TOKEN to an isolated HUMAN test credential");
const api = new LedgerApi(token, (p, o) => fetch(new URL(String(p), base), o));
const domain = "judgment-ui-" + crypto.randomUUID();
const rule = {
  ...emptyRule,
  title: "先检查工况",
  futureDecision: "决定是否拆检",
  situation: "振动上升",
  questions: ["负载变化了吗？"],
  judgment: "排除工况变化后再判断轴承故障",
  hardConstraints: ["不可超安全负载"],
  tradeoff: "误停机成本和漏检风险",
  verification: "比较同工况趋势",
  boundaries: "传感器失效时不能套用",
  reviseWhen: "有新反例时重审",
  counterexample: "盲目换轴承无效",
  authorConfidence: 0.95,
};
const save = {
  rule,
  gate: {
    changesFutureDecision: true,
    reusable: true,
    readilyRecoverable: false,
    decisionImpact: "先问工况再拆检",
  },
  domain,
  experienceKey: crypto.randomUUID(),
  validFrom: new Date().toISOString(),
  applicability: { assetType: "pump" },
  constraints: { maintenanceWindow: true },
  evidenceIds: [],
  eventKey: crypto.randomUUID(),
};
assert.equal(
  (
    await api.v2<Row>("/judgments/candidates", {
      ...save,
      gate: { ...save.gate, changesFutureDecision: false },
    })
  ).saved,
  false,
);
let c = (await api.v2<{ candidate: Candidate }>("/judgments/candidates", save))
  .candidate;
assert.equal(
  (await api.v2<{ candidate: Candidate }>("/judgments/candidates", save))
    .candidate.id,
  c.id,
);
await assert.rejects(
  () =>
    api.v2("/judgments/candidates", {
      ...save,
      rule: { ...rule, title: "changed payload" },
    }),
  (e: unknown) => e instanceof ApiError && e.status === 409,
);
c = (
  await api.v2<{ candidate: Candidate }>(`/judgments/candidates/${c.id}`, {
    ...save,
    rule: { ...rule, questions: ["负载变化了吗？", "传感器健康吗？"] },
    expectedRevision: c.revision,
  })
).candidate;
const v = await api.v2<Row>(`/judgments/candidates/${c.id}/publish`, {
  expectedRevision: c.revision,
  reason: "reviewed questions and boundaries",
});
const detail = await api.v2<Row>(`/judgments/${v.id}`);
assert.equal(detail.sharing.native_track, "HUMAN");
assert.equal(detail.sharing.grant, null);
assert.equal(detail.judgmentRule.questions.length, 2);
assert.equal(
  (await api.v2<Row[]>(`/judgments?domain=${encodeURIComponent(domain)}`))[0]
    .id,
  v.id,
);
const policy = await api.v2<Row>("/policies", {
  name: domain,
  enabled: true,
  reason: "test judgment gateway",
  rules: {
    domains: [domain],
    taskTypes: ["diagnosis"],
    allowedOrigins: ["HUMAN_ASSERTED"],
    maxContextTokens: 8000,
    maxEvidence: 5,
    maxItems: 4,
    minConfidence: 0.4,
    minEvidenceReliability: 0.5,
    maxAgeDays: 3650,
    requireEvidence: false,
    requireNegativeCases: false,
    allowUnknownScope: false,
    allowDeepSearch: false,
    candidateLimit: 50,
    deepCandidateLimit: 100,
  },
});
const ctx = {
  domain,
  taskType: "diagnosis",
  assetType: "pump",
  context: { maintenanceWindow: true },
  maxContextTokens: 8000,
  policyId: policy.id,
};
assert.equal((await api.v2<Row>("/context", ctx)).selected.length, 0);
const share = await api.v2<Row>(`/versions/${v.id}/reuse`, {
  targetTrack: "AGENT",
  mode: "CROSS_REFERENCE",
  validationMethod: "HUMAN_REVIEW",
  reason: "human reviewed reference",
  evidenceIds: [],
});
let packet = await api.v2<Row>("/context", ctx);
assert.equal(packet.selected.length, 1);
assert.match(packet.contextText, /传感器健康/);
assert.match(packet.contextText, /不可超安全负载/);
assert.equal(
  packet.budget.contextUnits,
  new TextEncoder().encode(packet.contextText).length,
);
if (process.env.LEDGER_AGENT_TOKEN) {
  const agent = new LedgerApi(process.env.LEDGER_AGENT_TOKEN, (p, o) =>
      fetch(new URL(String(p), base), o),
    ),
    who = process.env.LEDGER_AGENT_ID || "demo-agent";
  const old = (await api.v2<Row[]>("/bindings")).find(
    (x) => x.actor_type === "AGENT" && x.actor_id === who,
  );
  await api.v2("/bindings", {
    actorType: "AGENT",
    actorId: who,
    policyId: policy.id,
    enabled: true,
    expectedRevision: old?.revision || 0,
    reason: "isolated test",
  });
  const { policyId, ...agentCtx } = ctx;
  packet = await agent.v2<Row>("/context", agentCtx);
  assert.equal(packet.selected.length, 1);
  await assert.rejects(
    () => agent.v2("/judgments/candidates", save),
    (e: unknown) => e instanceof ApiError && e.status === 403,
  );
}
await api.v2(`/versions/${v.id}/reuse`, {
  targetTrack: "AGENT",
  mode: "NATIVE_ONLY",
  validationMethod: "HUMAN_REVIEW",
  reason: "new uncertainty",
  evidenceIds: [],
  expectedPreviousId: share.grant.id,
});
assert.equal((await api.v2<Row>("/context", ctx)).selected.length, 0);
console.log(
  "PASS: judgment capture gate, idempotency, edit CAS, publish, browse, shared Gateway packet, Agent identity, revoke",
);
