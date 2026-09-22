import test from "node:test";
import assert from "node:assert/strict";
import {
  draftFromCandidate,
  validateDraft,
  objectJson,
  requireId,
  allowedDispositions,
  instant,
  toLocal,
} from "../src/domain.ts";
import { LedgerApi, ApiError, query } from "../src/api.ts";
import type { Candidate, Draft } from "../src/types.ts";
const id = "11111111-1111-4111-8111-111111111111";
const draft: Draft = {
  title: "保留候选",
  summary: "验证全局组合",
  lesson: "推迟剪枝",
  validFrom: "2026-01-01T00:00:00Z",
  claims: [
    {
      claimType: "CAUSAL_HYPOTHESIS",
      originType: "AGENT_DERIVED",
      content: "可能由剪枝引起",
      derivationMethod: "对比执行记录",
      evidence: [{ evidenceId: id, supportType: "CONTEXT" }],
    },
  ],
};
const candidate = {
  id,
  status: "PENDING_REVIEW",
  revision: 7,
  extracted_json: {
    draft,
    capturedEvidenceIds: [id],
    providerData: "preserve on server",
  },
} as unknown as Candidate;
test("Draft conversion preserves Agent provenance, evidence and only request fields", () => {
  const next = draftFromCandidate(candidate);
  assert.deepEqual(next.claims, draft.claims);
  assert.equal("capturedEvidenceIds" in next, false);
  next.claims[0].content = "edited";
  assert.equal(draft.claims[0].content, "可能由剪枝引起");
});
test("Observed claims require observation type and supporting evidence", () => {
  const next = structuredClone(draft);
  next.claims = [
    {
      claimType: "OBSERVATION",
      originType: "OBSERVED",
      content: "测试通过",
      evidence: [{ evidenceId: id, supportType: "CONTEXT" }],
    },
  ];
  assert.throws(() => validateDraft(next), /SUPPORTS/);
  next.claims[0].evidence![0].supportType = "SUPPORTS";
  validateDraft(next);
  next.claims[0].claimType = "RULE";
  assert.throws(() => validateDraft(next), /OBSERVATION/);
});
test("Review cannot relabel identical Agent conclusions", () => {
  const next = structuredClone(draft);
  next.claims[0].originType = "HUMAN_ASSERTED";
  assert.throws(() => validateDraft(next, draft), /Agent/);
  next.claims[0].originType = "AGENT_DERIVED";
  next.claims[0].derivationMethod = "";
  assert.throws(() => validateDraft(next), /推导方法/);
});
test("Future activation and reversed validity intervals are rejected", () => {
  assert.throws(
    () =>
      validateDraft({
        ...draft,
        validFrom: new Date(Date.now() + 60000).toISOString(),
      }),
    /当前时间/,
  );
  assert.throws(
    () => validateDraft({ ...draft, validTo: draft.validFrom }),
    /晚于/,
  );
});
test("Structured context rejects arrays and IDs reject injection", () => {
  assert.throws(() => objectJson("[]"));
  assert.throws(() => objectJson("null"));
  assert.throws(() => requireId("../audit"));
  assert.equal(requireId(` ${id} `), id);
});
test("Allowed dispositions follow the actual backend states", () => {
  assert.deepEqual(allowedDispositions("VERIFIED"), []);
  assert.deepEqual(allowedDispositions("PENDING_REVIEW"), [
    "REJECTED",
    "MERGED",
  ]);
  assert.equal(allowedDispositions("NEW").includes("MERGED"), false);
});
test("Local date controls round trip to UTC at second precision", () => {
  const utc = "2026-01-02T12:30:15Z";
  assert.equal(instant(toLocal(utc)), new Date(utc).toISOString());
});
test("API preserves write payload, revision and same-origin authorization", async () => {
  let calls = 0;
  const transport: typeof fetch = async (url, options) => {
    calls++;
    assert.equal(url, "/api/v1/candidates/x/review");
    assert.equal(options?.method, "POST");
    assert.equal(options?.redirect, "error");
    assert.equal(
      (options?.headers as Record<string, string>).Authorization,
      "Bearer sample-token",
    );
    assert.equal(JSON.parse(options?.body as string).expectedRevision, 7);
    return new Response('{"revision":8}', { status: 200 });
  };
  assert.deepEqual(
    await new LedgerApi("sample-token", transport).request(
      "/candidates/x/review",
      { expectedRevision: 7, draft },
    ),
    { revision: 8 },
  );
  assert.equal(calls, 1);
});
test("Transport is never called with the client as its receiver", async () => {
  // Browsers brand-check fetch's receiver: invoking the stored reference as a
  // method of the client throws Illegal invocation and sends no request at all.
  let receiver: unknown;
  const transport = function (this: unknown) {
    receiver = this;
    return Promise.resolve(new Response("[]", { status: 200 }));
  } as unknown as typeof fetch;
  await new LedgerApi("sample-token", transport).request("/candidates");
  assert.ok(
    !(receiver instanceof LedgerApi),
    "native fetch would fail with Illegal invocation",
  );
});
test("Revision conflict exposes trace and does not retry mutations", async () => {
  let calls = 0;
  const transport: typeof fetch = async () => {
    calls++;
    return new Response(
      JSON.stringify({
        code: "CANDIDATE_REVISION_CONFLICT",
        traceId: "trace-1",
      }),
      { status: 409 },
    );
  };
  await assert.rejects(
    () => new LedgerApi("x", transport).request("/candidates/x/review", {}),
    (e: unknown) =>
      e instanceof ApiError &&
      e.traceId === "trace-1" &&
      e.code === "CANDIDATE_REVISION_CONFLICT",
  );
  assert.equal(calls, 1);
});
test("Write network errors mark outcome uncertain and are never replayed", async () => {
  let calls = 0;
  const transport: typeof fetch = async () => {
    calls++;
    throw new TypeError("Failed to fetch");
  };
  await assert.rejects(
    () => new LedgerApi("x", transport).request("/usages", {}),
    (e: unknown) => e instanceof ApiError && e.uncertain,
  );
  assert.equal(calls, 1);
});
test("Unauthorized responses are actionable, HTML proxy responses are rejected", async () => {
  const unauth: typeof fetch = async () =>
    new Response('{"code":"UNAUTHORIZED"}', { status: 401 });
  await assert.rejects(
    () => new LedgerApi("x", unauth).request("/audit"),
    (e: unknown) => e instanceof ApiError && /凭证/.test(e.message),
  );
  const html: typeof fetch = async () =>
    new Response("<html>proxy error</html>", { status: 200 });
  await assert.rejects(
    () => new LedgerApi("x", html).request("/audit"),
    (e: unknown) => e instanceof ApiError && e.code === "INVALID_RESPONSE",
  );
});
test("Query construction keeps Chinese reasons and separates query values", () => {
  const value = query({
    reason: "证据更正 & 核对",
    limit: 100,
    none: undefined,
  });
  assert.equal(new URLSearchParams(value).get("reason"), "证据更正 & 核对");
  assert.equal(new URLSearchParams(value).has("none"), false);
});

test("POST search failures do not imply an uncertain write", async () => {
  const transport: typeof fetch = async () => {
    throw new TypeError("offline");
  };
  await assert.rejects(
    () =>
      new LedgerApi("x", transport).request("/experiences/search", {
        query: "",
      }),
    (e: unknown) => e instanceof ApiError && !e.uncertain,
  );
});

test("V2 context and feedback keep API version, identity and event key", async () => {
  const paths: string[] = [];
  const transport: typeof fetch = async (url, options) => {
    paths.push(String(url));
    assert.equal(
      (options?.headers as Record<string, string>).Authorization,
      "Bearer agent",
    );
    if (String(url).endsWith("/feedback"))
      assert.equal(JSON.parse(options?.body as string).eventKey, "stable-key");
    return new Response('{"ok":true}', { status: 200 });
  };
  const client = new LedgerApi("agent", transport);
  await client.v2("/context", { maxContextTokens: 2500 });
  await client.v2("/feedback", { eventKey: "stable-key" });
  assert.deepEqual(paths, ["/api/v2/context", "/api/v2/feedback"]);
});
