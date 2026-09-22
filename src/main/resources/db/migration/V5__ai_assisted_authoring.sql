-- V1.2 AI-assisted authoring. V4 is reserved for the existing human-judgment overlay.
-- Frozen V1 experience/evidence tables remain authoritative.
CREATE TABLE exp_authoring_candidate (
 candidate_id uuid PRIMARY KEY, space_id uuid NOT NULL,
 capture_channel text NOT NULL CHECK(capture_channel IN ('HUMAN','AGENT','EXTERNAL')),
 agent_role text, task_context_json jsonb NOT NULL DEFAULT '{}', evidence_refs_json jsonb NOT NULL DEFAULT '[]',
 language text NOT NULL DEFAULT 'zh-CN', raw_content_hash text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,candidate_id),
 FOREIGN KEY(space_id,candidate_id) REFERENCES exp_candidate(space_id,id),
 CHECK(jsonb_typeof(task_context_json)='object' AND jsonb_typeof(evidence_refs_json)='array'));

CREATE TABLE exp_prompt_template (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), space_id uuid NOT NULL REFERENCES exp_space(id),
 code text NOT NULL, version integer NOT NULL CHECK(version>0),
 system_prompt text NOT NULL, user_template text NOT NULL, output_schema jsonb NOT NULL,
 enabled boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,code,version));
CREATE UNIQUE INDEX prompt_template_enabled ON exp_prompt_template(space_id,code) WHERE enabled;

CREATE TABLE exp_experience_draft (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, candidate_id uuid NOT NULL,
 draft_version integer NOT NULL CHECK(draft_version>0), structured_content_json jsonb NOT NULL,
 provider text NOT NULL, model text NOT NULL, prompt_code text NOT NULL, prompt_version integer NOT NULL,
 input_hash text NOT NULL, output_hash text NOT NULL,
 input_tokens bigint CHECK(input_tokens IS NULL OR input_tokens>=0), output_tokens bigint CHECK(output_tokens IS NULL OR output_tokens>=0),
 estimated_cost numeric CHECK(estimated_cost IS NULL OR estimated_cost>=0), currency text NOT NULL DEFAULT 'USD',
 revision_source text NOT NULL CHECK(revision_source IN ('AI_GENERATED','AI_REVISED','HUMAN_EDITED','REGENERATED','GENERATION_FAILED')),
 revision_instruction text, status text NOT NULL CHECK(status IN ('CREATED','AI_REVISED','HUMAN_EDITED','GENERATION_FAILED','ACCEPTED','REJECTED','SUPERSEDED')),
 validation_errors_json jsonb NOT NULL DEFAULT '[]', error_summary text,
 created_by_type text NOT NULL, created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,candidate_id,draft_version),
 FOREIGN KEY(space_id,candidate_id) REFERENCES exp_candidate(space_id,id),
 CHECK(jsonb_typeof(structured_content_json)='object' AND jsonb_typeof(validation_errors_json)='array'));
CREATE INDEX draft_inbox ON exp_experience_draft(space_id,status,created_at DESC);
CREATE INDEX draft_candidate ON exp_experience_draft(space_id,candidate_id,draft_version DESC);

CREATE TABLE exp_draft_revision_log (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, from_draft_id uuid, to_draft_id uuid NOT NULL,
 revision_source text NOT NULL, instruction text, diff_json jsonb NOT NULL DEFAULT '[]',
 actor_type text NOT NULL, actor_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), FOREIGN KEY(space_id,from_draft_id) REFERENCES exp_experience_draft(space_id,id),
 FOREIGN KEY(space_id,to_draft_id) REFERENCES exp_experience_draft(space_id,id),
 CHECK(jsonb_typeof(diff_json)='array'));

CREATE TABLE exp_llm_invocation (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, candidate_id uuid NOT NULL, draft_id uuid,
 operation text NOT NULL CHECK(operation IN ('GENERATE','REVISE','REGENERATE')),
 provider text NOT NULL, model text NOT NULL, prompt_code text NOT NULL, prompt_version integer NOT NULL,
 input_hash text NOT NULL, output_hash text, status text NOT NULL CHECK(status IN ('SUCCEEDED','FAILED')),
 input_tokens bigint CHECK(input_tokens IS NULL OR input_tokens>=0), output_tokens bigint CHECK(output_tokens IS NULL OR output_tokens>=0),
 estimated_cost numeric CHECK(estimated_cost IS NULL OR estimated_cost>=0), currency text NOT NULL DEFAULT 'USD',
 latency_ms bigint NOT NULL CHECK(latency_ms>=0), error_summary text, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), FOREIGN KEY(space_id,candidate_id) REFERENCES exp_candidate(space_id,id),
 FOREIGN KEY(space_id,draft_id) REFERENCES exp_experience_draft(space_id,id));
CREATE INDEX llm_invocation_candidate ON exp_llm_invocation(space_id,candidate_id,created_at DESC);

CREATE TABLE exp_draft_publication (
 draft_id uuid PRIMARY KEY, space_id uuid NOT NULL, candidate_id uuid NOT NULL, experience_version_id uuid NOT NULL,
 accepted_by text NOT NULL, accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,draft_id), UNIQUE(space_id,experience_version_id),
 FOREIGN KEY(space_id,draft_id) REFERENCES exp_experience_draft(space_id,id),
 FOREIGN KEY(space_id,candidate_id) REFERENCES exp_candidate(space_id,id),
 FOREIGN KEY(space_id,experience_version_id) REFERENCES exp_experience_version(space_id,id));

CREATE TABLE exp_experience_summary (
 version_id uuid PRIMARY KEY, space_id uuid NOT NULL, source_draft_id uuid NOT NULL,
 l0_fingerprint text NOT NULL, l1_compact text NOT NULL, l2_summary text NOT NULL,
 provider text NOT NULL, model text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,version_id), FOREIGN KEY(space_id,version_id) REFERENCES exp_experience_version(space_id,id),
 FOREIGN KEY(space_id,source_draft_id) REFERENCES exp_experience_draft(space_id,id));

CREATE FUNCTION ledger_draft_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (to_jsonb(NEW)-ARRAY['status']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status']) THEN
  RAISE EXCEPTION 'DRAFT_IMMUTABLE' USING ERRCODE='23514'; END IF;
 IF OLD.status NOT IN ('CREATED','AI_REVISED','HUMAN_EDITED') OR NEW.status NOT IN ('ACCEPTED','REJECTED','SUPERSEDED') THEN
  RAISE EXCEPTION 'DRAFT_STATE_CONFLICT' USING ERRCODE='23514'; END IF;
 IF NEW.status IN ('ACCEPTED','REJECTED') AND (ledger_actor_type() IS NULL OR ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW')) THEN
  RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER draft_guard BEFORE UPDATE ON exp_experience_draft FOR EACH ROW EXECUTE FUNCTION ledger_draft_guard();

CREATE FUNCTION ledger_authoring_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
 target:=coalesce((to_jsonb(NEW)->>'id')::uuid,(to_jsonb(NEW)->>'candidate_id')::uuid,(to_jsonb(NEW)->>'draft_id')::uuid,(to_jsonb(NEW)->>'version_id')::uuid);
 INSERT INTO exp_audit_event(space_id,actor_type,actor_id,action,target_type,target_id,reason,metadata_json)
 VALUES(NEW.space_id,ledger_actor_type(),ledger_actor_id(),
  CASE WHEN TG_OP='UPDATE' THEN 'DRAFT_'||(to_jsonb(NEW)->>'status') ELSE TG_ARGV[0] END,TG_TABLE_NAME,target,
  coalesce(current_setting('ledger.reason',true),''),jsonb_build_object('traceId',current_setting('ledger.trace_id',true)));
 RETURN NEW;
END $$;

DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['exp_authoring_candidate','exp_prompt_template','exp_experience_draft','exp_draft_revision_log','exp_llm_invocation','exp_draft_publication','exp_experience_summary'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY space_isolation ON %I USING(space_id=ledger_space()) WITH CHECK(space_id=ledger_space())',t);
  EXECUTE format('CREATE INDEX %I ON %I(space_id)','idx_'||t||'_space',t);
  EXECUTE format('CREATE TRIGGER no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny()',t);
  IF t<>'exp_experience_draft' THEN EXECUTE format('CREATE TRIGGER no_update BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t); END IF;
 END LOOP;
END $$;

CREATE TRIGGER authoring_candidate_audit AFTER INSERT ON exp_authoring_candidate FOR EACH ROW EXECUTE FUNCTION ledger_authoring_audit('CAPTURE_AUTHORING_INPUT');
CREATE TRIGGER draft_audit AFTER INSERT OR UPDATE ON exp_experience_draft FOR EACH ROW EXECUTE FUNCTION ledger_authoring_audit('CREATE_DRAFT');
CREATE TRIGGER revision_audit AFTER INSERT ON exp_draft_revision_log FOR EACH ROW EXECUTE FUNCTION ledger_authoring_audit('REVISE_DRAFT');
CREATE TRIGGER publication_audit AFTER INSERT ON exp_draft_publication FOR EACH ROW EXECUTE FUNCTION ledger_authoring_audit('PUBLISH_DRAFT');

CREATE FUNCTION ledger_seed_authoring_prompts(target_space uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE schema_json jsonb := '{"type":"object","required":["title","summary","problem","context","actions","lesson","reusablePrinciple","claims","missingInformation","tags","domain","taskType","confidence"]}'::jsonb;
BEGIN
 INSERT INTO exp_prompt_template(space_id,code,version,system_prompt,user_template,output_schema)
 VALUES
 (target_space,'EXPERIENCE_DRAFT_V1',1,
  '你是 Experience Ledger 的经验提炼器。只根据输入生成 JSON。不得虚构事实、指标、根因或 Evidence；事实不足时写入 missingInformation；区分事实与推断；保守描述适用范围与边界；重点积累能改变未来决策的判断规则，而不是一次性答案。',
  '根据原始 Candidate、任务上下文和可用 Evidence 生成结构化经验草稿。Evidence 只能引用给定 ID。输出必须符合给定 JSON Schema。',schema_json),
 (target_space,'EXPERIENCE_REVISION_V1',1,
  '你是 Experience Ledger 的草稿修订器。严格执行修订指令，同时保留原始 Candidate 的事实边界。不得把推断改写成观察，不得新增不存在的 Evidence，只输出完整 JSON 草稿。',
  '结合原始 Candidate、当前 Draft 与用户指令生成一个完整的新 Draft 版本。输出必须符合给定 JSON Schema。',schema_json)
 ON CONFLICT(space_id,code,version) DO NOTHING;
END $$;

SELECT ledger_seed_authoring_prompts(id) FROM exp_space;
CREATE FUNCTION ledger_seed_authoring_prompts_trigger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM ledger_seed_authoring_prompts(NEW.id); RETURN NEW; END $$;
CREATE TRIGGER seed_authoring_prompts AFTER INSERT ON exp_space FOR EACH ROW EXECUTE FUNCTION ledger_seed_authoring_prompts_trigger();
