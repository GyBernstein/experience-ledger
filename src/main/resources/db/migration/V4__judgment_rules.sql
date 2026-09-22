-- Additive capability migration; not a product version change. V1-V3 checksums stay unchanged.
CREATE TABLE exp_experience_track (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, family_id uuid NOT NULL,
 native_track text NOT NULL CHECK(native_track IN ('HUMAN','AGENT')),
 provenance text NOT NULL CHECK(provenance IN ('CAPTURE_IDENTITY','LEGACY_CAPTURE','LEGACY_FALLBACK')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id), UNIQUE(space_id,family_id),
 FOREIGN KEY(space_id,family_id) REFERENCES exp_experience_family(space_id,id));
CREATE TABLE exp_judgment_rule (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, experience_version_id uuid NOT NULL,
 rule_json jsonb NOT NULL CHECK(jsonb_typeof(rule_json)='object'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id), UNIQUE(space_id,experience_version_id),
 FOREIGN KEY(space_id,experience_version_id) REFERENCES exp_experience_version(space_id,id));
CREATE TABLE exp_reuse_event (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, version_id uuid NOT NULL, target_track text NOT NULL CHECK(target_track IN ('HUMAN','AGENT')),
 previous_id uuid, reuse_mode text NOT NULL CHECK(reuse_mode IN ('NATIVE_ONLY','CROSS_REFERENCE','CROSS_REUSABLE')),
 validation_method text NOT NULL CHECK(validation_method IN ('HUMAN_REVIEW','TEST','REPLAY')),
 reason text NOT NULL CHECK(length(trim(reason))>0), actor_type text NOT NULL, actor_id text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id), UNIQUE(previous_id),
 FOREIGN KEY(space_id,version_id) REFERENCES exp_experience_version(space_id,id),
 FOREIGN KEY(space_id,previous_id) REFERENCES exp_reuse_event(space_id,id));
CREATE INDEX reuse_latest ON exp_reuse_event(space_id,version_id,target_track,created_at DESC,id DESC);
CREATE UNIQUE INDEX reuse_one_root ON exp_reuse_event(space_id,version_id,target_track) WHERE previous_id IS NULL;
CREATE TABLE exp_reuse_evidence (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, reuse_event_id uuid NOT NULL, evidence_id uuid NOT NULL,
 UNIQUE(space_id,id), UNIQUE(reuse_event_id,evidence_id),
 FOREIGN KEY(space_id,reuse_event_id) REFERENCES exp_reuse_event(space_id,id),
 FOREIGN KEY(space_id,evidence_id) REFERENCES exp_evidence(space_id,id));
-- Existing ownership is inferred from authenticated capture identity, never the publishing reviewer.
-- Flyway runs this entire migration in one PostgreSQL transaction. The table owner
-- needs to enumerate space IDs. NO FORCE affects ONLY owner access, never the
-- runtime role. ALTER takes an exclusive lock; FORCE is restored before commit.
-- All data tables retain FORCE RLS and are read one space at a time.
ALTER TABLE exp_space NO FORCE ROW LEVEL SECURITY;
DO $$ DECLARE s uuid; BEGIN
 FOR s IN SELECT id FROM exp_space LOOP
  PERFORM set_config('ledger.space_id',s::text,true);
  PERFORM set_config('ledger.actor_type','SYSTEM',true);
  PERFORM set_config('ledger.actor_id','judgment-migration',true);
  INSERT INTO exp_experience_track(id,space_id,family_id,native_track,provenance)
  SELECT gen_random_uuid(),f.space_id,f.id,
   CASE WHEN coalesce(c.created_by_type,f.created_by_type)='HUMAN' THEN 'HUMAN' ELSE 'AGENT' END,
   CASE WHEN c.id IS NULL THEN 'LEGACY_FALLBACK' ELSE 'LEGACY_CAPTURE' END
  FROM exp_experience_family f
  LEFT JOIN exp_experience_version v ON v.space_id=f.space_id AND v.family_id=f.id AND v.version_no=1
  LEFT JOIN LATERAL (SELECT id,created_by_type FROM exp_candidate WHERE space_id=f.space_id AND target_version_id=v.id AND status='VERIFIED' ORDER BY created_at,id LIMIT 1) c ON true
  WHERE f.space_id=s;
  INSERT INTO exp_audit_event(space_id,actor_type,actor_id,action,target_type,target_id,reason,metadata_json)
  SELECT s,'SYSTEM','judgment-migration','CLASSIFY_NATIVE_TRACK','exp_experience_track',id,'V4 additive migration; no cross-track permission inferred',to_jsonb(t)
  FROM exp_experience_track t WHERE space_id=s;
 END LOOP;
END $$;
ALTER TABLE exp_space FORCE ROW LEVEL SECURITY;
CREATE FUNCTION ledger_judgment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous exp_reuse_event; native text; tx bigint;
BEGIN
 IF ledger_actor_type() IS NULL OR ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW') THEN RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='exp_judgment_rule' THEN
  SELECT t.native_track INTO native FROM exp_experience_version v JOIN exp_experience_track t ON t.space_id=v.space_id AND t.family_id=v.family_id WHERE v.space_id=NEW.space_id AND v.id=NEW.experience_version_id;
  IF native IS DISTINCT FROM 'HUMAN' THEN RAISE EXCEPTION 'HUMAN_RULE_REQUIRED' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='exp_experience_track' THEN
  IF NOT EXISTS(SELECT 1 FROM exp_experience_version WHERE space_id=NEW.space_id AND family_id=NEW.family_id AND version_no=1 AND creation_tx=txid_current()) THEN RAISE EXCEPTION 'TRACK_SEALED' USING ERRCODE='23514'; END IF;
 ELSIF TG_TABLE_NAME='exp_reuse_event' THEN
  PERFORM 1 FROM exp_experience_version WHERE space_id=NEW.space_id AND id=NEW.version_id FOR UPDATE;
  SELECT t.native_track INTO native FROM exp_experience_version v JOIN exp_experience_track t ON t.space_id=v.space_id AND t.family_id=v.family_id WHERE v.space_id=NEW.space_id AND v.id=NEW.version_id;
  IF native IS NULL OR native=NEW.target_track THEN RAISE EXCEPTION 'CROSS_TRACK_REQUIRED' USING ERRCODE='23514'; END IF;
  SELECT * INTO previous FROM exp_reuse_event e WHERE e.space_id=NEW.space_id AND e.version_id=NEW.version_id AND e.target_track=NEW.target_track ORDER BY created_at DESC,id DESC LIMIT 1;
  IF NEW.previous_id IS DISTINCT FROM previous.id THEN RAISE EXCEPTION 'REUSE_REVISION_CONFLICT' USING ERRCODE='23514'; END IF;
  IF NEW.reuse_mode='CROSS_REUSABLE' AND NEW.validation_method='HUMAN_REVIEW' THEN RAISE EXCEPTION 'TEST_OR_REPLAY_REQUIRED' USING ERRCODE='23514'; END IF;
  NEW.actor_type:=ledger_actor_type(); NEW.actor_id:=ledger_actor_id(); NEW.created_at:=clock_timestamp();
 ELSIF TG_TABLE_NAME='exp_reuse_evidence' THEN
  SELECT xmin::text::bigint INTO tx FROM exp_reuse_event WHERE space_id=NEW.space_id AND id=NEW.reuse_event_id;
  IF tx IS DISTINCT FROM (txid_current() % 4294967296) THEN RAISE EXCEPTION 'REUSE_EVIDENCE_SEALED' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['exp_experience_track','exp_judgment_rule','exp_reuse_event','exp_reuse_evidence'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY space_isolation ON %I USING(space_id=ledger_space()) WITH CHECK(space_id=ledger_space())',t);
  EXECUTE format('CREATE TRIGGER no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER no_update BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER governance BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ledger_judgment_guard()',t);
  EXECUTE format('CREATE TRIGGER audit AFTER INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ledger_context_audit()',t);
 END LOOP;
END $$;
CREATE TRIGGER sealed_parent BEFORE INSERT ON exp_judgment_rule FOR EACH ROW EXECUTE FUNCTION ledger_child_guard();
-- Deferred check: the event and its evidence references are one atomic insert.
CREATE FUNCTION ledger_reuse_validation_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.reuse_mode='CROSS_REUSABLE' AND NOT EXISTS(
  SELECT 1 FROM exp_reuse_evidence r JOIN exp_evidence e ON e.space_id=r.space_id AND e.id=r.evidence_id
  WHERE r.space_id=NEW.space_id AND r.reuse_event_id=NEW.id AND e.status='ACTIVE'
 ) THEN RAISE EXCEPTION 'REUSE_VALIDATION_EVIDENCE_REQUIRED' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER validation_evidence AFTER INSERT ON exp_reuse_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_reuse_validation_check();
