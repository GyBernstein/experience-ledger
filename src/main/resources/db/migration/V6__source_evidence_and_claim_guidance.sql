-- Keep V1.2 authoring prompts append-only: new codes preserve the original templates and draft provenance.
CREATE FUNCTION ledger_seed_authoring_prompts_v2(target_space uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE schema_json jsonb := '{"type":"object","required":["title","summary","problem","context","actions","lesson","reusablePrinciple","claims","evidenceMappings","missingInformation","tags","domain","taskType","confidence"],"properties":{"claims":{"type":"array","items":{"type":"object","required":["content","kind","claimType"],"properties":{"content":{"type":"string"},"kind":{"enum":["OBSERVED","DERIVED"]},"claimType":{"enum":["OBSERVATION","RULE","LESSON","RECOMMENDATION","CONSTRAINT","CAUSAL_HYPOTHESIS","WARNING"]},"confidence":{"type":"number"}}}},"evidenceMappings":{"type":"array","items":{"type":"object","required":["claimIndex","evidenceRef","relation"],"properties":{"claimIndex":{"type":"integer"},"evidenceRef":{"type":"string"},"relation":{"enum":["SUPPORTS","CONTRADICTS","CONTEXT"]}}}}}}'::jsonb;
BEGIN
 INSERT INTO exp_prompt_template(space_id,code,version,system_prompt,user_template,output_schema)
 VALUES
 (target_space,'EXPERIENCE_DRAFT_V2',1,
  '你是 Experience Ledger 的经验提炼器。仅根据输入生成 JSON，不得虚构事实、根因、指标或 Evidence。按可独立复用的判断点拆分 claims：观察、约束、判断规则、失败尝试可分别成条；只在确有不同判断点时生成多条，信息不足时可以为零条并填写 missingInformation，不要凑数量。一个 claim 只表达一个判断点，区分 OBSERVED 与 DERIVED。availableEvidence 中 rawInput=true 的来源快照只能标记为 CONTEXT，不能当作独立佐证或凭此认定 OBSERVED；其余已有 Evidence 也只能引用给定 ID。保守描述适用边界，重点积累会改变未来决策的判断规则。',
  '根据原始 Candidate、任务上下文和可用 Evidence 生成结构化经验草稿。逐条输出有价值的 claims，并按 claimIndex 建立证据引用；来源快照仅作 CONTEXT。输出符合给定 JSON Schema。',schema_json),
 (target_space,'EXPERIENCE_REVISION_V2',1,
  '你是 Experience Ledger 的草稿修订器。严格执行修订指令，保留独立判断点和原始事实边界。可以增减 claims，但不要凑数量或把多个不同判断合成一条。不得把推断改写成观察，不得新增不存在的 Evidence；rawInput=true 的来源快照只能作为 CONTEXT。只输出完整 JSON 草稿。',
  '结合原始 Candidate、当前 Draft 与用户指令生成完整的新 Draft 版本。逐条核对 claims、evidenceMappings、适用边界与待确认信息，输出符合给定 JSON Schema。',schema_json)
 ON CONFLICT(space_id,code,version) DO NOTHING;
END $$;

SELECT ledger_seed_authoring_prompts_v2(id) FROM exp_space;
CREATE FUNCTION ledger_seed_authoring_prompts_v2_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM ledger_seed_authoring_prompts_v2(NEW.id);
 RETURN NEW;
END $$;
CREATE TRIGGER seed_authoring_prompts_v2 AFTER INSERT ON exp_space
FOR EACH ROW EXECUTE FUNCTION ledger_seed_authoring_prompts_v2_trigger();
