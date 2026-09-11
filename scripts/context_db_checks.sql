-- Isolated database only, V1-V3 migrated, demo Space seeded, non-superuser role.
BEGIN;
SELECT set_config('ledger.space_id','11111111-1111-1111-1111-111111111111',true),set_config('ledger.actor_type','HUMAN',true),set_config('ledger.actor_id','context-db-test',true);
DO $$ DECLARE p uuid:=gen_random_uuid(); r uuid:=gen_random_uuid(); t text; BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN RAISE EXCEPTION 'Non-superuser required'; END IF;
 FOREACH t IN ARRAY ARRAY['exp_retrieval_policy','exp_agent_binding','exp_validation_event','exp_compact','exp_context_run','exp_agent_feedback','exp_feedback_review'] LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_class WHERE oid=t::regclass AND relrowsecurity AND relforcerowsecurity) THEN RAISE EXCEPTION 'Missing forced RLS %',t; END IF;
 END LOOP;
 INSERT INTO exp_retrieval_policy(id,space_id,name,rules_json) VALUES(p,ledger_space(),'test','{}');
 UPDATE exp_retrieval_policy SET name='updated' WHERE id=p;
 IF (SELECT revision FROM exp_retrieval_policy WHERE id=p)<>2 THEN RAISE EXCEPTION 'Missing revision'; END IF;
 IF NOT EXISTS(SELECT 1 FROM exp_audit_event WHERE target_id=p AND metadata_json->'before'->>'name'='test' AND metadata_json->'after'->>'name'='updated') THEN RAISE EXCEPTION 'Missing audit'; END IF;
 INSERT INTO exp_context_run(id,space_id,actor_type,actor_id,policy_id,policy_revision,request_json,response_json,context_units,baseline_units,latency_ms) VALUES(r,ledger_space(),'HUMAN','test',p,2,'{}','{}',0,0,0);
 BEGIN UPDATE exp_context_run SET context_units=1 WHERE id=r; RAISE EXCEPTION 'Mutation allowed'; EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN DELETE FROM exp_context_run WHERE id=r; RAISE EXCEPTION 'Deletion allowed'; EXCEPTION WHEN check_violation OR insufficient_privilege THEN NULL; END;
 BEGIN TRUNCATE exp_context_run CASCADE; RAISE EXCEPTION 'Truncation allowed'; EXCEPTION WHEN check_violation OR insufficient_privilege THEN NULL; END;
 PERFORM set_config('ledger.actor_type','AGENT',true);
 BEGIN INSERT INTO exp_retrieval_policy(id,space_id,name,rules_json) VALUES(gen_random_uuid(),ledger_space(),'forbidden','{}'); RAISE EXCEPTION 'Agent governance allowed'; EXCEPTION WHEN check_violation THEN NULL; END;
 PERFORM set_config('ledger.space_id','22222222-2222-2222-2222-222222222222',true);
 IF EXISTS(SELECT 1 FROM exp_retrieval_policy WHERE id=p) OR EXISTS(SELECT 1 FROM exp_context_run WHERE id=r) THEN RAISE EXCEPTION 'Cross-space read'; END IF;
 BEGIN INSERT INTO exp_context_run(id,space_id,actor_type,actor_id,policy_id,policy_revision,request_json,response_json,context_units,baseline_units,latency_ms) VALUES(gen_random_uuid(),'11111111-1111-1111-1111-111111111111','AGENT','test',p,2,'{}','{}',0,0,0); RAISE EXCEPTION 'Cross-space write'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
ROLLBACK;
