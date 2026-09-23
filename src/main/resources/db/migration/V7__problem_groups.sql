-- A symptom/problem can have several independent causes. Families remain revision chains.
-- Membership changes are events so an incorrect grouping can be undone without rewriting history.
CREATE TABLE exp_problem_group (
 id uuid PRIMARY KEY, space_id uuid NOT NULL REFERENCES exp_space(id),
 domain text NOT NULL, title text NOT NULL, problem text NOT NULL,
 created_by_type text NOT NULL, created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id),
 CHECK(length(trim(title))>0 AND length(trim(problem))>0));

CREATE TABLE exp_problem_group_event (
 id uuid PRIMARY KEY, space_id uuid NOT NULL, group_id uuid NOT NULL,
 family_id uuid NOT NULL, linked_version_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN ('LINK','UNLINK')),
 relation text NOT NULL CHECK(relation IN ('ROOT_CASE','ALTERNATIVE_CAUSE','SAME_CAUSE_CASE')),
 reason text NOT NULL CHECK(length(trim(reason))>0),
 created_by_type text NOT NULL, created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(space_id,id),
 FOREIGN KEY(space_id,group_id) REFERENCES exp_problem_group(space_id,id),
 FOREIGN KEY(space_id,family_id,linked_version_id) REFERENCES exp_experience_version(space_id,family_id,id));
CREATE INDEX problem_group_event_family ON exp_problem_group_event(space_id,family_id,created_at DESC,id DESC);
CREATE INDEX problem_group_event_group ON exp_problem_group_event(space_id,group_id,created_at DESC,id DESC);

DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['exp_problem_group','exp_problem_group_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY space_isolation ON %I USING(space_id=ledger_space()) WITH CHECK(space_id=ledger_space())',t);
  EXECUTE format('CREATE TRIGGER no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER no_update BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION ledger_deny()',t);
  EXECUTE format('CREATE TRIGGER audit AFTER INSERT ON %I FOR EACH ROW EXECUTE FUNCTION ledger_context_audit()',t);
 END LOOP;
END $$;

CREATE FUNCTION ledger_problem_group_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE group_domain text; family_domain text;
BEGIN
 IF ledger_actor_type() NOT IN ('HUMAN','TRUSTED_WORKFLOW') OR ledger_actor_type() IS NULL THEN
  RAISE EXCEPTION 'GOVERNANCE_FORBIDDEN' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME='exp_problem_group_event' THEN
  SELECT domain INTO group_domain FROM exp_problem_group WHERE space_id=NEW.space_id AND id=NEW.group_id;
  SELECT domain INTO family_domain FROM exp_experience_family WHERE space_id=NEW.space_id AND id=NEW.family_id;
  IF group_domain IS DISTINCT FROM family_domain THEN
   RAISE EXCEPTION 'PROBLEM_GROUP_DOMAIN_MISMATCH' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER governance BEFORE INSERT ON exp_problem_group FOR EACH ROW EXECUTE FUNCTION ledger_problem_group_guard();
CREATE TRIGGER governance BEFORE INSERT ON exp_problem_group_event FOR EACH ROW EXECUTE FUNCTION ledger_problem_group_guard();
