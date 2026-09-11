-- Additive V1.1 layer. V1 immutable records and lifecycle remain authoritative.
CREATE TABLE exp_retrieval_policy (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES exp_space(id), name text NOT NULL,
 revision integer NOT NULL DEFAULT 1, enabled boolean NOT NULL DEFAULT true, rules_json jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id));
CREATE TABLE exp_agent_binding (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, actor_type text NOT NULL, actor_id text NOT NULL,
 policy_id uuid NOT NULL, enabled boolean NOT NULL DEFAULT true, revision integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,actor_type,actor_id), FOREIGN KEY(space_id,policy_id) REFERENCES exp_retrieval_policy(space_id,id));
CREATE TABLE exp_validation_event (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, version_id uuid NOT NULL,
 validation_status text NOT NULL CHECK(validation_status IN ('VERIFIED','ADOPTED','DISPUTED','DEPRECATED')),
 assessed_confidence numeric NOT NULL CHECK(assessed_confidence BETWEEN 0 AND 1), reason text NOT NULL,
 actor_type text NOT NULL, actor_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), FOREIGN KEY(space_id,version_id) REFERENCES exp_experience_version(space_id,id));
CREATE INDEX validation_latest ON exp_validation_event(space_id,version_id,created_at DESC,id DESC);
CREATE TABLE exp_compact (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, domain text NOT NULL, task_type text NOT NULL, title text NOT NULL, summary text NOT NULL,
 representative_id uuid NOT NULL, snapshot_json jsonb NOT NULL, status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','ACTIVE','RETIRED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), FOREIGN KEY(space_id,representative_id) REFERENCES exp_experience_version(space_id,id));
CREATE INDEX compact_domain_task ON exp_compact(space_id,domain,task_type,status);
CREATE TABLE exp_context_run (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, actor_type text NOT NULL, actor_id text NOT NULL, policy_id uuid NOT NULL, policy_revision integer NOT NULL,
 request_json jsonb NOT NULL, response_json jsonb NOT NULL, context_units integer NOT NULL, baseline_units integer NOT NULL, latency_ms bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id), FOREIGN KEY(space_id,policy_id) REFERENCES exp_retrieval_policy(space_id,id));
CREATE INDEX context_actor ON exp_context_run(space_id,actor_type,actor_id,created_at DESC);
CREATE TABLE exp_agent_feedback (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, run_id uuid NOT NULL, version_id uuid NOT NULL, usage_id uuid NOT NULL,
 actor_type text NOT NULL, actor_id text NOT NULL, event_key text NOT NULL, payload_hash text NOT NULL, adopted boolean NOT NULL,
 outcome_type text, evaluation text NOT NULL, actual_input_tokens bigint CHECK(actual_input_tokens>=0), actual_output_tokens bigint CHECK(actual_output_tokens>=0),
 reported_cost numeric CHECK(reported_cost>=0), currency text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,actor_type,actor_id,event_key),
 FOREIGN KEY(space_id,run_id) REFERENCES exp_context_run(space_id,id), FOREIGN KEY(space_id,version_id) REFERENCES exp_experience_version(space_id,id), FOREIGN KEY(space_id,usage_id) REFERENCES exp_usage(space_id,id));
CREATE TABLE exp_feedback_review (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, feedback_id uuid NOT NULL, verdict text NOT NULL CHECK(verdict IN ('ACCEPTED','REJECTED')),
 reason text NOT NULL, actor_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id),
 FOREIGN KEY(space_id,feedback_id) REFERENCES exp_agent_feedback(space_id,id));
CREATE FUNCTION ledger_context_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ledger_actor_type() IS NULL OR ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW') THEN RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.id<>OLD.id OR NEW.space_id<>OLD.space_id THEN RAISE EXCEPTION 'IDENTITY_IMMUTABLE' USING ERRCODE='23514'; END IF;
  IF TG_TABLE_NAME='exp_compact' THEN
   IF (to_jsonb(NEW)-ARRAY['status','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','updated_at']) OR NOT ((OLD.status='DRAFT' AND NEW.status IN ('ACTIVE','RETIRED')) OR (OLD.status='ACTIVE' AND NEW.status='RETIRED')) THEN RAISE EXCEPTION 'COMPACT_IMMUTABLE' USING ERRCODE='23514'; END IF;
  ELSE
   IF TG_TABLE_NAME='exp_agent_binding' THEN
    IF NEW.actor_id<>OLD.actor_id OR NEW.actor_type<>OLD.actor_type THEN RAISE EXCEPTION 'BINDING_IDENTITY_IMMUTABLE' USING ERRCODE='23514'; END IF;
   END IF;
   NEW.revision:=OLD.revision+1;
  END IF;
  NEW.updated_at:=clock_timestamp();
 END IF; RETURN NEW;
END $$;
CREATE FUNCTION ledger_context_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO exp_audit_event(space_id,actor_type,actor_id,action,target_type,target_id,reason,metadata_json)
 VALUES(NEW.space_id,ledger_actor_type(),ledger_actor_id(),TG_OP,TG_TABLE_NAME,NEW.id,coalesce(current_setting('ledger.reason',true),''),
 jsonb_build_object('before',CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE NULL END,'after',to_jsonb(NEW)));
 RETURN NEW;
END $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['exp_retrieval_policy','exp_agent_binding','exp_validation_event','exp_compact','exp_context_run','exp_agent_feedback','exp_feedback_review'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY space_isolation ON %I USING(space_id=ledger_space()) WITH CHECK(space_id=ledger_space())',t);
  EXECUTE format('CREATE TRIGGER no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny()',t);
  IF t IN ('exp_retrieval_policy','exp_agent_binding','exp_compact') THEN
   EXECUTE format('CREATE TRIGGER governance BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_context_guard()',t);
  ELSE
   EXECUTE format('CREATE TRIGGER no_update BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
   IF t IN ('exp_validation_event','exp_feedback_review') THEN EXECUTE format('CREATE TRIGGER governance BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ledger_context_guard()',t); END IF;
  END IF;
  IF t='exp_context_run' THEN EXECUTE format('CREATE TRIGGER audit AFTER INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ledger_audit(''SUPPLY_CONTEXT'')',t);
  ELSE EXECUTE format('CREATE TRIGGER audit AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_context_audit()',t); END IF;
 END LOOP;
END $$;
