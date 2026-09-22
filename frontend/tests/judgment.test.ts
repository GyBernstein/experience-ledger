import test from "node:test";
import assert from "node:assert/strict";
import { LedgerApi } from "../src/api";
import { emptyGate, worthKeeping } from "../src/judgment";
test("Unsaved answers do not pass the retention gate", () => {
  assert.equal(worthKeeping(emptyGate), false);
  assert.equal(
    worthKeeping({
      changesFutureDecision: true,
      reusable: true,
      readilyRecoverable: true,
      decisionImpact: "answer lookup",
    }),
    false,
  );
  assert.equal(
    worthKeeping({
      changesFutureDecision: true,
      reusable: true,
      readilyRecoverable: false,
      decisionImpact: "ask about load before repair",
    }),
    true,
  );
});
test("Sharing request keeps explicit mode and previous event for CAS", async () => {
  let calls = 0;
  const body = {
    targetTrack: "AGENT",
    mode: "CROSS_REFERENCE",
    validationMethod: "HUMAN_REVIEW",
    reason: "human reviewed",
    evidenceIds: [],
    expectedPreviousId: "previous",
  };
  const api = new LedgerApi("human", async (path, init) => {
    calls++;
    assert.equal(path, "/api/v2/versions/version/reuse");
    assert.deepEqual(JSON.parse(init!.body as string), body);
    return new Response(JSON.stringify({ code: "REUSE_REVISION_CONFLICT" }), {
      status: 409,
    });
  });
  await assert.rejects(() => api.v2("/versions/version/reuse", body));
  assert.equal(calls, 1);
});
