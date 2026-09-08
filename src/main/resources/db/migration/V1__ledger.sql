-- Frozen V1: all business relationships use composite (space_id,id) FKs.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE exp_space (id uuid PRIMARY KEY, name text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE FUNCTION ledger_space() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('ledger.space_id',true),'')::uuid $$;
CREATE FUNCTION ledger_actor_type() RETURNS text LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('ledger.actor_type',true),'') $$;
CREATE FUNCTION ledger_actor_id() RETURNS text LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('ledger.actor_id',true),'') $$;
CREATE TABLE exp_experience_family (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES exp_space(id), experience_key text NOT NULL,
 domain text NOT NULL, experience_type text NOT NULL CHECK (experience_type IN ('BEST_PRACTICE','PROBLEM_SOLUTION','FAILURE','DECISION','WORKAROUND','OPTIMIZATION','WARNING','INVESTIGATION')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by_type text NOT NULL, created_by text NOT NULL,
 UNIQUE(space_id,id), UNIQUE(space_id,experience_key));
-- Lightweight CJK unigram/bigram expansion; no external tokenizer dependency.
CREATE FUNCTION ledger_fts_text(input text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT input || ' ' || coalesce(string_agg(piece,' '),'') FROM (
  SELECT substring(input from i for 1) AS piece FROM generate_series(1,length(input)) i WHERE substring(input from i for 1) ~ '[一-鿿]'
  UNION ALL
  SELECT substring(input from i for 2) FROM generate_series(1,length(input)-1) i WHERE substring(input from i for 2) ~ '^[一-鿿]{2}$'
 ) tokens
$$;
CREATE TABLE exp_experience_version (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, family_id uuid NOT NULL, version_no integer NOT NULL CHECK(version_no>0),
 title text NOT NULL, summary text NOT NULL, problem text NOT NULL DEFAULT '', decision text NOT NULL DEFAULT '', action text NOT NULL DEFAULT '', outcome_summary text NOT NULL DEFAULT '', lesson text NOT NULL,
 applicability_json jsonb NOT NULL DEFAULT '{}', constraints_json jsonb NOT NULL DEFAULT '{}',
 status text NOT NULL DEFAULT 'VERIFIED' CHECK(status IN ('VERIFIED','SUPERSEDED','INVALIDATED')),
 valid_from timestamptz NOT NULL, valid_to timestamptz,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(), invalidated_at timestamptz,
 supersedes_id uuid UNIQUE, retrieval_text text NOT NULL,
 search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple',ledger_fts_text(retrieval_text))) STORED,
 embedding vector(384), embedding_status text NOT NULL DEFAULT 'PENDING' CHECK(embedding_status IN ('PENDING','READY','FAILED','DISABLED')),
 embedding_model text, created_by_type text NOT NULL, created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 creation_tx bigint NOT NULL DEFAULT txid_current(),
 UNIQUE(space_id,id), UNIQUE(space_id,family_id,id), UNIQUE(family_id,version_no),
 FOREIGN KEY(space_id,family_id) REFERENCES exp_experience_family(space_id,id),
 FOREIGN KEY(space_id,family_id,supersedes_id) REFERENCES exp_experience_version(space_id,family_id,id),
 CHECK(valid_to IS NULL OR valid_to>valid_from), CHECK(invalidated_at IS NULL OR invalidated_at>=recorded_at),
 CHECK((status='VERIFIED')=(invalidated_at IS NULL)),
 CHECK((embedding_status='READY')=(embedding IS NOT NULL)), CHECK(embedding IS NULL OR embedding_model IS NOT NULL),
 CHECK(jsonb_typeof(applicability_json)='object' AND jsonb_typeof(constraints_json)='object'));
CREATE TABLE exp_episode (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES exp_space(id), title text NOT NULL, summary text NOT NULL,
 occurred_at timestamptz NOT NULL, completed_at timestamptz, context_json jsonb NOT NULL DEFAULT '{}',
 source_type text NOT NULL, source_system text, source_ref text,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by_type text NOT NULL, created_by text NOT NULL,
 UNIQUE(space_id,id), CHECK(completed_at IS NULL OR completed_at>=occurred_at));
CREATE TABLE exp_candidate (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES exp_space(id), candidate_type text NOT NULL DEFAULT 'EXPERIENCE',
 raw_content text NOT NULL CHECK(length(trim(raw_content))>0), extracted_json jsonb NOT NULL DEFAULT '{}',
 source_type text NOT NULL, source_system text, source_ref text, event_type text,
 dedup_key text NOT NULL, status text NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','ENRICHED','PENDING_REVIEW','VERIFIED','DUPLICATE','MERGED','REJECTED','EXPIRED')),
 similar_family_ids uuid[] NOT NULL DEFAULT '{}', embedding vector(384),
 embedding_status text NOT NULL DEFAULT 'PENDING' CHECK(embedding_status IN ('PENDING','READY','FAILED','DISABLED')), embedding_model text,
 episode_id uuid, target_version_id uuid, target_episode_id uuid,
 processing_status text NOT NULL DEFAULT 'PENDING' CHECK(processing_status IN ('PENDING','RUNNING','READY','FAILED')),
 processing_error text, revision integer NOT NULL DEFAULT 0,
 created_by_type text NOT NULL, created_by text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(space_id,dedup_key),
 FOREIGN KEY(space_id,episode_id) REFERENCES exp_episode(space_id,id),
 FOREIGN KEY(space_id,target_version_id) REFERENCES exp_experience_version(space_id,id),
 FOREIGN KEY(space_id,target_episode_id) REFERENCES exp_episode(space_id,id),
 CHECK((embedding_status='READY')=(embedding IS NOT NULL)), CHECK(embedding IS NULL OR embedding_model IS NOT NULL));
CREATE TABLE exp_evidence (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES exp_space(id),
 evidence_type text NOT NULL CHECK(evidence_type IN ('MES_RECORD','ERP_RECORD','EMS_RECORD','SQL_RESULT','TEST_RESULT','DOCUMENT','CHAT','TICKET','GIT_COMMIT','PULL_REQUEST','CODE','USER_NOTE','ENGINEER_CONFIRMATION','AGENT_OBSERVATION','AGENT_TOOL_RESULT','EXTERNAL_SOURCE')),
 source_system text, source_ref text, snapshot_json jsonb NOT NULL DEFAULT '{}', snapshot_uri text, content_hash text NOT NULL,
 observed_at timestamptz NOT NULL, captured_at timestamptz NOT NULL DEFAULT clock_timestamp(), reliability numeric NOT NULL DEFAULT 0.5 CHECK(reliability BETWEEN 0 AND 1),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','SUPERSEDED')), corrected_by_evidence_id uuid UNIQUE,
 metadata_json jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id),
 FOREIGN KEY(space_id,corrected_by_evidence_id) REFERENCES exp_evidence(space_id,id),
 CHECK((status='ACTIVE')=(corrected_by_evidence_id IS NULL)), CHECK(corrected_by_evidence_id IS DISTINCT FROM id));
CREATE TABLE exp_experience_claim (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, experience_version_id uuid NOT NULL,
 claim_type text NOT NULL CHECK(claim_type IN ('OBSERVATION','RULE','LESSON','RECOMMENDATION','CONSTRAINT','CAUSAL_HYPOTHESIS','WARNING')),
 content text NOT NULL CHECK(length(trim(content))>0),
 origin_type text NOT NULL CHECK(origin_type IN ('OBSERVED','HUMAN_ASSERTED','AGENT_DERIVED','SYSTEM_DERIVED')),
 derivation_method text, sequence_no integer NOT NULL CHECK(sequence_no>=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(experience_version_id,sequence_no),
 FOREIGN KEY(space_id,experience_version_id) REFERENCES exp_experience_version(space_id,id),
 CHECK(origin_type NOT IN ('AGENT_DERIVED','SYSTEM_DERIVED') OR (derivation_method IS NOT NULL AND length(trim(derivation_method))>0)),
 CHECK(origin_type<>'OBSERVED' OR claim_type='OBSERVATION'));
CREATE TABLE exp_claim_evidence (
 space_id uuid NOT NULL, claim_id uuid NOT NULL, evidence_id uuid NOT NULL,
 support_type text NOT NULL CHECK(support_type IN ('SUPPORTS','CONTRADICTS','CONTEXT')), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(claim_id,evidence_id,support_type),
 FOREIGN KEY(space_id,claim_id) REFERENCES exp_experience_claim(space_id,id), FOREIGN KEY(space_id,evidence_id) REFERENCES exp_evidence(space_id,id));
CREATE TABLE exp_experience_episode (
 space_id uuid NOT NULL, experience_version_id uuid NOT NULL, episode_id uuid NOT NULL,
 relation_type text NOT NULL CHECK(relation_type IN ('SOURCE','VALIDATION','FAILURE_CASE','APPLICATION')), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(experience_version_id,episode_id,relation_type),
 FOREIGN KEY(space_id,experience_version_id) REFERENCES exp_experience_version(space_id,id), FOREIGN KEY(space_id,episode_id) REFERENCES exp_episode(space_id,id));
CREATE TABLE exp_context_ref (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, experience_version_id uuid NOT NULL,
 ref_type text NOT NULL, ref_value text NOT NULL, source_system text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(space_id,id), UNIQUE(experience_version_id,ref_type,ref_value,source_system),
 FOREIGN KEY(space_id,experience_version_id) REFERENCES exp_experience_version(space_id,id));
CREATE TABLE exp_relation (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, from_version_id uuid NOT NULL, to_version_id uuid NOT NULL,
 relation_type text NOT NULL CHECK(relation_type IN ('SIMILAR_TO','SUPPORTS','CONTRADICTS','DERIVED_FROM','CAUSED_BY','RELATED_TO')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id), UNIQUE(from_version_id,to_version_id,relation_type),
 FOREIGN KEY(space_id,from_version_id) REFERENCES exp_experience_version(space_id,id), FOREIGN KEY(space_id,to_version_id) REFERENCES exp_experience_version(space_id,id), CHECK(from_version_id<>to_version_id));
CREATE TABLE exp_usage (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, experience_version_id uuid NOT NULL,
 query_context_json jsonb NOT NULL DEFAULT '{}', retrieval_score double precision, applicability_score double precision,
 recommended boolean NOT NULL, actually_used boolean NOT NULL, actor_type text NOT NULL, actor_id text NOT NULL,
 used_at timestamptz NOT NULL DEFAULT clock_timestamp(), metadata_json jsonb NOT NULL DEFAULT '{}',
 UNIQUE(space_id,id), FOREIGN KEY(space_id,experience_version_id) REFERENCES exp_experience_version(space_id,id));
CREATE TABLE exp_outcome (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, usage_id uuid NOT NULL,
 outcome_type text NOT NULL CHECK(outcome_type IN ('SUCCESS','PARTIAL_SUCCESS','FAILURE','INCONCLUSIVE')),
 metrics_json jsonb NOT NULL DEFAULT '{}', notes text NOT NULL DEFAULT '', observed_at timestamptz NOT NULL,
 evidence_id uuid, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by_type text NOT NULL, created_by text NOT NULL,
 UNIQUE(space_id,id), FOREIGN KEY(space_id,usage_id) REFERENCES exp_usage(space_id,id), FOREIGN KEY(space_id,evidence_id) REFERENCES exp_evidence(space_id,id));
CREATE TABLE exp_audit_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), space_id uuid NOT NULL REFERENCES exp_space(id), actor_type text NOT NULL, actor_id text NOT NULL,
 action text NOT NULL, target_type text NOT NULL, target_id uuid NOT NULL, before_ref text, after_ref text,
 reason text NOT NULL DEFAULT '', metadata_json jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id));
-- Technical projection; source events remain authoritative and projection is rebuildable.
CREATE TABLE exp_version_stats (
 space_id uuid NOT NULL, version_id uuid PRIMARY KEY,
 evidence_count bigint NOT NULL DEFAULT 0, usage_count bigint NOT NULL DEFAULT 0,
 success_count bigint NOT NULL DEFAULT 0, partial_success_count bigint NOT NULL DEFAULT 0, failure_count bigint NOT NULL DEFAULT 0,
 human_verified boolean NOT NULL, last_verified_at timestamptz NOT NULL,
 FOREIGN KEY(space_id,version_id) REFERENCES exp_experience_version(space_id,id));
CREATE TABLE processing_job (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), space_id uuid NOT NULL REFERENCES exp_space(id),
 target_type text NOT NULL CHECK(target_type IN ('CANDIDATE','VERSION')), target_id uuid NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','DONE','FAILED')),
 attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL DEFAULT clock_timestamp(), lease_until timestamptz,
 last_error text, UNIQUE(space_id,target_type,target_id));
CREATE INDEX version_fts ON exp_experience_version USING gin(search_vector);
CREATE INDEX version_vector ON exp_experience_version USING hnsw(embedding vector_cosine_ops);
CREATE INDEX candidate_vector ON exp_candidate USING hnsw(embedding vector_cosine_ops);
CREATE INDEX version_temporal ON exp_experience_version(space_id,recorded_at,invalidated_at,valid_from,valid_to);
CREATE INDEX context_lookup ON exp_context_ref(space_id,ref_type,ref_value);
CREATE INDEX evidence_source ON exp_evidence(space_id,source_system,source_ref);
CREATE INDEX candidate_source ON exp_candidate(space_id,source_system,source_ref,event_type);
CREATE INDEX episode_source ON exp_episode(space_id,source_system,source_ref);
CREATE INDEX usage_version ON exp_usage(space_id,experience_version_id);
CREATE INDEX outcome_usage ON exp_outcome(space_id,usage_id);
CREATE INDEX claim_version ON exp_experience_claim(space_id,experience_version_id);
CREATE INDEX relation_target ON exp_relation(space_id,to_version_id);
CREATE INDEX job_poll ON processing_job(space_id,status,available_at);
CREATE INDEX audit_target ON exp_audit_event(space_id,target_id,created_at);

CREATE FUNCTION ledger_deny() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE: % % prohibited',TG_TABLE_NAME,TG_OP USING ERRCODE='23514'; END $$;
CREATE FUNCTION ledger_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prev exp_experience_version; latest integer;
BEGIN
 IF TG_OP='INSERT' THEN
  IF ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW') OR ledger_actor_type() IS NULL THEN
   RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
  PERFORM 1 FROM exp_experience_family WHERE id=NEW.family_id AND space_id=NEW.space_id FOR UPDATE;
  SELECT coalesce(max(version_no),0) INTO latest FROM exp_experience_version WHERE family_id=NEW.family_id AND space_id=NEW.space_id;
  IF NEW.version_no<>latest+1 OR (latest=0)<>(NEW.supersedes_id IS NULL) THEN
   RAISE EXCEPTION 'INVALID_SUPERSESSION' USING ERRCODE='23514'; END IF;
  NEW.recorded_at:=clock_timestamp(); NEW.created_at:=NEW.recorded_at; NEW.creation_tx:=txid_current();
  IF NEW.status<>'VERIFIED' OR NEW.invalidated_at IS NOT NULL THEN RAISE EXCEPTION 'VERSION_CONFLICT' USING ERRCODE='23514'; END IF;
  IF NEW.valid_from>NEW.recorded_at THEN RAISE EXCEPTION 'FUTURE_VERSION_UNSUPPORTED' USING ERRCODE='23514'; END IF;
  IF NEW.supersedes_id IS NOT NULL THEN
   SELECT * INTO prev FROM exp_experience_version WHERE id=NEW.supersedes_id AND space_id=NEW.space_id FOR UPDATE;
   IF NOT FOUND OR prev.family_id<>NEW.family_id OR prev.version_no<>latest OR prev.status<>'VERIFIED' THEN
    RAISE EXCEPTION 'INVALID_SUPERSESSION' USING ERRCODE='23514'; END IF;
   UPDATE exp_experience_version SET status='SUPERSEDED',invalidated_at=NEW.recorded_at WHERE id=prev.id AND space_id=NEW.space_id;
  END IF;
  NEW.created_by_type:=ledger_actor_type(); NEW.created_by:=ledger_actor_id();
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','invalidated_at','embedding','embedding_status','embedding_model','search_vector']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['status','invalidated_at','embedding','embedding_status','embedding_model','search_vector']) THEN
   RAISE EXCEPTION 'VERSION_IMMUTABLE' USING ERRCODE='23514'; END IF;
  IF (NEW.status,NEW.invalidated_at) IS DISTINCT FROM (OLD.status,OLD.invalidated_at) THEN
   IF ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW') OR ledger_actor_type() IS NULL THEN RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
   IF OLD.status<>'VERIFIED' OR NEW.status NOT IN ('SUPERSEDED','INVALIDATED') THEN RAISE EXCEPTION 'VERSION_CONFLICT' USING ERRCODE='23514'; END IF;
   IF NEW.status='INVALIDATED' THEN NEW.invalidated_at:=clock_timestamp(); END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER version_guard BEFORE INSERT OR UPDATE ON exp_experience_version FOR EACH ROW EXECUTE FUNCTION ledger_version_guard();
CREATE FUNCTION ledger_chain_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='SUPERSEDED' AND NOT EXISTS(SELECT 1 FROM exp_experience_version WHERE supersedes_id=NEW.id AND space_id=NEW.space_id AND recorded_at=NEW.invalidated_at) THEN
  RAISE EXCEPTION 'INVALID_SUPERSESSION: missing atomic successor' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER chain_check AFTER UPDATE ON exp_experience_version DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_chain_check();
CREATE FUNCTION ledger_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v uuid; tx bigint;
BEGIN
 IF TG_TABLE_NAME='exp_claim_evidence' THEN SELECT experience_version_id INTO v FROM exp_experience_claim WHERE id=NEW.claim_id AND space_id=NEW.space_id;
 ELSE v:=NEW.experience_version_id; END IF;
 SELECT creation_tx INTO tx FROM exp_experience_version WHERE id=v AND space_id=NEW.space_id;
 IF tx IS DISTINCT FROM txid_current() THEN RAISE EXCEPTION 'VERSION_IMMUTABLE: associations are sealed at commit' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION ledger_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE replacement exp_evidence;
BEGIN
 IF (to_jsonb(NEW)-ARRAY['status','corrected_by_evidence_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','corrected_by_evidence_id']) THEN
  RAISE EXCEPTION 'EVIDENCE_IMMUTABLE' USING ERRCODE='23514'; END IF;
 IF OLD.status<>'ACTIVE' OR NEW.status<>'SUPERSEDED' OR NEW.corrected_by_evidence_id IS NULL THEN
  RAISE EXCEPTION 'EVIDENCE_IMMUTABLE' USING ERRCODE='23514'; END IF;
 SELECT * INTO replacement FROM exp_evidence WHERE id=NEW.corrected_by_evidence_id AND space_id=NEW.space_id;
 IF NOT FOUND OR replacement.status<>'ACTIVE' OR replacement.captured_at<OLD.captured_at THEN
  RAISE EXCEPTION 'INVALID_EVIDENCE_CORRECTION' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER evidence_guard BEFORE UPDATE ON exp_evidence FOR EACH ROW EXECUTE FUNCTION ledger_evidence_guard();
CREATE FUNCTION ledger_candidate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.space_id<>OLD.space_id OR NEW.id<>OLD.id OR NEW.dedup_key<>OLD.dedup_key OR NEW.created_by<>OLD.created_by OR NEW.created_by_type<>OLD.created_by_type THEN
  RAISE EXCEPTION 'CANDIDATE_IDENTITY_IMMUTABLE' USING ERRCODE='23514'; END IF;
 IF OLD.status IN ('VERIFIED','DUPLICATE','MERGED','REJECTED','EXPIRED') THEN RAISE EXCEPTION 'CANDIDATE_TERMINAL' USING ERRCODE='23514'; END IF;
 IF NEW.status<>OLD.status AND NOT (
  (OLD.status='NEW' AND NEW.status IN ('ENRICHED','DUPLICATE','REJECTED','EXPIRED')) OR
  (OLD.status='ENRICHED' AND NEW.status IN ('PENDING_REVIEW','DUPLICATE','REJECTED','EXPIRED')) OR
  (OLD.status='PENDING_REVIEW' AND NEW.status IN ('VERIFIED','MERGED','REJECTED'))) THEN
   RAISE EXCEPTION 'CANDIDATE_STATE_CONFLICT' USING ERRCODE='23514'; END IF;
 IF NEW.status IN ('VERIFIED','MERGED','REJECTED','DUPLICATE','EXPIRED') AND (ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW') OR ledger_actor_type() IS NULL) THEN
  RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
 IF NEW.status='VERIFIED' AND NEW.target_version_id IS NULL THEN RAISE EXCEPTION 'PROMOTION_TARGET_REQUIRED' USING ERRCODE='23514'; END IF;
 IF NEW.status IN ('MERGED','DUPLICATE') AND NEW.target_version_id IS NULL AND NEW.target_episode_id IS NULL THEN RAISE EXCEPTION 'MERGE_TARGET_REQUIRED' USING ERRCODE='23514'; END IF;
 NEW.updated_at:=clock_timestamp(); NEW.revision:=OLD.revision+1;
 RETURN NEW;
END $$;
CREATE TRIGGER candidate_guard BEFORE UPDATE ON exp_candidate FOR EACH ROW EXECUTE FUNCTION ledger_candidate_guard();
CREATE FUNCTION ledger_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE act text; target uuid; oldref text;
BEGIN
 act:=TG_ARGV[0]; target:=(to_jsonb(NEW)->>'id')::uuid;
 IF TG_TABLE_NAME='exp_claim_evidence' THEN target:=NEW.claim_id; END IF;
 IF TG_OP='UPDATE' THEN
  IF TG_TABLE_NAME='exp_experience_version' THEN
   IF NEW.status=OLD.status THEN RETURN NEW; END IF;
   act:=CASE NEW.status WHEN 'SUPERSEDED' THEN 'SUPERSEDE' ELSE 'INVALIDATE' END; oldref:=OLD.id::text;
  ELSIF TG_TABLE_NAME='exp_candidate' THEN
   IF NEW.status=OLD.status THEN RETURN NEW; END IF;
   act:=CASE NEW.status WHEN 'VERIFIED' THEN 'VERIFY' WHEN 'REJECTED' THEN 'REJECT' WHEN 'MERGED' THEN 'MERGE' ELSE NEW.status END; oldref:=OLD.status;
  END IF;
 END IF;
 INSERT INTO exp_audit_event(space_id,actor_type,actor_id,action,target_type,target_id,before_ref,after_ref,reason,metadata_json)
 VALUES(NEW.space_id,ledger_actor_type(),ledger_actor_id(),act,TG_TABLE_NAME,target,oldref,target::text,
 coalesce(current_setting('ledger.reason',true),''),jsonb_build_object('traceId',current_setting('ledger.trace_id',true),'txId',txid_current()));
 RETURN NEW;
END $$;
CREATE TRIGGER version_audit AFTER INSERT OR UPDATE ON exp_experience_version FOR EACH ROW EXECUTE FUNCTION ledger_audit('CREATE_VERSION');
CREATE TRIGGER candidate_audit AFTER INSERT OR UPDATE ON exp_candidate FOR EACH ROW EXECUTE FUNCTION ledger_audit('CREATE_CANDIDATE');
CREATE TRIGGER evidence_audit AFTER INSERT ON exp_evidence FOR EACH ROW EXECUTE FUNCTION ledger_audit('CREATE_EVIDENCE');
CREATE TRIGGER evidence_correction_audit AFTER UPDATE ON exp_evidence FOR EACH ROW EXECUTE FUNCTION ledger_audit('SUPERSEDE_EVIDENCE');
CREATE TRIGGER claim_evidence_audit AFTER INSERT ON exp_claim_evidence FOR EACH ROW EXECUTE FUNCTION ledger_audit('LINK_EVIDENCE');
CREATE TRIGGER relation_audit AFTER INSERT ON exp_relation FOR EACH ROW EXECUTE FUNCTION ledger_audit('LINK_RELATION');
CREATE TRIGGER usage_audit AFTER INSERT ON exp_usage FOR EACH ROW EXECUTE FUNCTION ledger_audit('RECORD_USAGE');
CREATE TRIGGER outcome_audit AFTER INSERT ON exp_outcome FOR EACH ROW EXECUTE FUNCTION ledger_audit('RECORD_OUTCOME');
CREATE TRIGGER episode_audit AFTER INSERT ON exp_episode FOR EACH ROW EXECUTE FUNCTION ledger_audit('CREATE_EPISODE');

CREATE FUNCTION ledger_stats() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v uuid;
BEGIN
 IF TG_TABLE_NAME='exp_experience_version' THEN
  INSERT INTO exp_version_stats(space_id,version_id,human_verified,last_verified_at) VALUES(NEW.space_id,NEW.id,NEW.created_by_type='HUMAN',NEW.recorded_at);
 ELSIF TG_TABLE_NAME='exp_usage' THEN
  UPDATE exp_version_stats SET usage_count=usage_count+1 WHERE space_id=NEW.space_id AND version_id=NEW.experience_version_id;
 ELSIF TG_TABLE_NAME='exp_outcome' THEN
  SELECT experience_version_id INTO v FROM exp_usage WHERE space_id=NEW.space_id AND id=NEW.usage_id;
  UPDATE exp_version_stats SET success_count=success_count+(NEW.outcome_type='SUCCESS')::int,
   partial_success_count=partial_success_count+(NEW.outcome_type='PARTIAL_SUCCESS')::int,
   failure_count=failure_count+(NEW.outcome_type='FAILURE')::int WHERE space_id=NEW.space_id AND version_id=v;
 ELSE
  SELECT experience_version_id INTO v FROM exp_experience_claim WHERE space_id=NEW.space_id AND id=NEW.claim_id;
  UPDATE exp_version_stats SET evidence_count=(SELECT count(DISTINCT ce.evidence_id) FROM exp_claim_evidence ce JOIN exp_experience_claim c ON c.space_id=ce.space_id AND c.id=ce.claim_id WHERE c.experience_version_id=v AND c.space_id=NEW.space_id)
   WHERE space_id=NEW.space_id AND version_id=v;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER version_stats AFTER INSERT ON exp_experience_version FOR EACH ROW EXECUTE FUNCTION ledger_stats();
CREATE TRIGGER usage_stats AFTER INSERT ON exp_usage FOR EACH ROW EXECUTE FUNCTION ledger_stats();
CREATE TRIGGER outcome_stats AFTER INSERT ON exp_outcome FOR EACH ROW EXECUTE FUNCTION ledger_stats();
CREATE TRIGGER evidence_stats AFTER INSERT ON exp_claim_evidence FOR EACH ROW EXECUTE FUNCTION ledger_stats();

ALTER TABLE exp_space ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_space FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_space USING (id=ledger_space()) WITH CHECK (id=ledger_space());
CREATE TRIGGER no_delete BEFORE DELETE ON exp_space FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_space FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_space FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_experience_family ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_experience_family FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_experience_family USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_experience_family_space ON exp_experience_family(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_experience_family FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_experience_family FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_experience_family FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_experience_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_experience_version FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_experience_version USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_experience_version_space ON exp_experience_version(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_experience_version FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_experience_version FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_episode ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_episode FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_episode USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_episode_space ON exp_episode(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_episode FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_episode FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_episode FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_candidate FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_candidate USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_candidate_space ON exp_candidate(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_candidate FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_candidate FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_evidence USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_evidence_space ON exp_evidence(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_evidence FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_evidence FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_experience_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_experience_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_experience_claim USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_experience_claim_space ON exp_experience_claim(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_experience_claim FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_experience_claim FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_experience_claim FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER sealed_parent BEFORE INSERT ON exp_experience_claim FOR EACH ROW EXECUTE FUNCTION ledger_child_guard();

ALTER TABLE exp_claim_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_claim_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_claim_evidence USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_claim_evidence_space ON exp_claim_evidence(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_claim_evidence FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_claim_evidence FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_claim_evidence FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER sealed_parent BEFORE INSERT ON exp_claim_evidence FOR EACH ROW EXECUTE FUNCTION ledger_child_guard();

ALTER TABLE exp_experience_episode ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_experience_episode FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_experience_episode USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_experience_episode_space ON exp_experience_episode(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_experience_episode FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_experience_episode FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_experience_episode FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER sealed_parent BEFORE INSERT ON exp_experience_episode FOR EACH ROW EXECUTE FUNCTION ledger_child_guard();

ALTER TABLE exp_context_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_context_ref FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_context_ref USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_context_ref_space ON exp_context_ref(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_context_ref FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_context_ref FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_context_ref FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER sealed_parent BEFORE INSERT ON exp_context_ref FOR EACH ROW EXECUTE FUNCTION ledger_child_guard();

ALTER TABLE exp_relation ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_relation FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_relation USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_relation_space ON exp_relation(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_relation FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_relation FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_relation FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_usage USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_usage_space ON exp_usage(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_usage FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_usage FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_usage FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_outcome ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_outcome FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_outcome USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_outcome_space ON exp_outcome(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_outcome FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_outcome FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_outcome FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_audit_event USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_audit_event_space ON exp_audit_event(space_id);
CREATE TRIGGER no_delete BEFORE DELETE ON exp_audit_event FOR EACH ROW EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON exp_audit_event FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny();
CREATE TRIGGER no_update BEFORE UPDATE ON exp_audit_event FOR EACH ROW EXECUTE FUNCTION ledger_deny();

ALTER TABLE exp_version_stats ENABLE ROW LEVEL SECURITY;
ALTER TABLE exp_version_stats FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON exp_version_stats USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX exp_version_stats_space ON exp_version_stats(space_id);

ALTER TABLE processing_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE processing_job FORCE ROW LEVEL SECURITY;
CREATE POLICY space_isolation ON processing_job USING (space_id=ledger_space()) WITH CHECK (space_id=ledger_space());
CREATE INDEX processing_job_space ON processing_job(space_id);
