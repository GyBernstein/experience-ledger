import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import { renderToString } from "react-dom/server";
import { LedgerApi } from "../src/api.ts";
import { DraftEditor } from "../src/pages/Authoring.tsx";
import type { AuthoringDraft } from "../src/types.ts";

const base = {
  id: "11111111-1111-4111-8111-111111111111",
  candidateId: "22222222-2222-4222-8222-222222222222",
  draftVersion: 1,
  provider: "unavailable",
  model: "unavailable",
  promptCode: "EXPERIENCE_DRAFT_V2",
  promptVersion: 1,
  revisionSource: "GENERATION_FAILED",
  errorSummary: "LLM 调用超时",
  createdAt: "2026-09-23T00:00:00Z",
  missingCount: 0,
  candidate: { source_type: "MANUAL_TEXT", raw_content: "一次设备故障排查" },
  history: [],
  similar: [],
} as unknown as AuthoringDraft;

test("failed AI generation opens the review page with raw input and retry", () => {
  const draft = { ...base, status: "GENERATION_FAILED", structuredContent: {} } as AuthoringDraft;
  const html = renderToString(React.createElement(DraftEditor, { api: new LedgerApi("test-token"), initial: draft }));
  assert.match(html, /一次设备故障排查/);
  assert.match(html, /LLM 调用超时/);
  assert.match(html, /重新生成/);
});

test("partial arrays in a stored draft do not crash review", () => {
  const draft = {
    ...base,
    status: "CREATED",
    structuredContent: { title: "待审核", claims: [], evidenceMappings: [], confidence: {} },
  } as unknown as AuthoringDraft;
  const html = renderToString(React.createElement(DraftEditor, { api: new LedgerApi("test-token"), initial: draft }));
  assert.match(html, /待审核/);
  assert.match(html, /保存修改/);
});
