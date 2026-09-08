-- Deferred checks see the complete graph at COMMIT, while publication remains a single transaction.
CREATE FUNCTION ledger_publication_check() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v uuid;
BEGIN
 IF TG_TABLE_NAME='exp_experience_version' THEN
  v:=NEW.id;
  IF NOT EXISTS(SELECT 1 FROM exp_experience_claim WHERE space_id=NEW.space_id AND experience_version_id=v) THEN
   RAISE EXCEPTION 'CLAIMS_REQUIRED' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.origin_type='OBSERVED' AND NOT EXISTS(SELECT 1 FROM exp_claim_evidence WHERE space_id=NEW.space_id AND claim_id=NEW.id AND support_type='SUPPORTS') THEN
   RAISE EXCEPTION 'OBSERVED_EVIDENCE_REQUIRED' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER publication_check AFTER INSERT ON exp_experience_version DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_publication_check();
CREATE CONSTRAINT TRIGGER observed_check AFTER INSERT ON exp_experience_claim DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_publication_check();
CREATE FUNCTION ledger_evidence_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status<>'ACTIVE' OR NEW.corrected_by_evidence_id IS NOT NULL THEN RAISE EXCEPTION 'INVALID_EVIDENCE_CORRECTION' USING ERRCODE='23514'; END IF;
 NEW.captured_at:=clock_timestamp(); NEW.created_at:=NEW.captured_at;
 RETURN NEW;
END $$;
CREATE TRIGGER evidence_insert BEFORE INSERT ON exp_evidence FOR EACH ROW EXECUTE FUNCTION ledger_evidence_insert();
