-- Run after Flyway. Supply psql -v space_id=... -v space_name=...
BEGIN;
SELECT set_config('ledger.space_id', :'space_id', true);
INSERT INTO exp_space(id,name) VALUES(:'space_id'::uuid, :'space_name') ON CONFLICT DO NOTHING;
COMMIT;
