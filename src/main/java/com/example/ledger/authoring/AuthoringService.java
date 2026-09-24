package com.example.ledger.authoring;

import com.example.ledger.api.Requests;
import com.example.ledger.application.LedgerService;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class AuthoringService {
 private final Db db;private final LedgerService ledger;private final DraftAssistProvider provider;private final PromptService prompts;private final DraftSchema schema;private final ProblemGroupService groups;
 public AuthoringService(Db db,LedgerService ledger,DraftAssistProvider provider,PromptService prompts,DraftSchema schema,ProblemGroupService groups){this.db=db;this.ledger=ledger;this.provider=provider;this.prompts=prompts;this.schema=schema;this.groups=groups;}

 public Map<String,Object> captureHuman(ActorContext actor,AuthoringRequests.Capture r){actor.requireGovernance();return capture(actor,"HUMAN",null,r.rawContent(),r.sourceType(),r.sourceRef(),r.taskContext(),r.evidenceRefs(),r.language(),!Boolean.FALSE.equals(r.generateDraft()),r.dedupKey());}
 public Map<String,Object> captureAgent(ActorContext actor,AuthoringRequests.AgentCapture r){
  if(actor.actorType()!=ActorContext.ActorType.AGENT&&actor.actorType()!=ActorContext.ActorType.SYSTEM&&actor.actorType()!=ActorContext.ActorType.TRUSTED_WORKFLOW)throw new LedgerException("FORBIDDEN","Agent capture requires an Agent/System identity",403);
  ObjectNode context=object(r.taskContext());put(context,"task",r.task());put(context,"result",r.result());put(context,"outcome",r.outcome());
  return capture(actor,"AGENT",r.agentRole(),r.rawContent(),r.sourceType(),r.sourceRef(),context,r.evidenceRefs(),r.language(),true,r.dedupKey());
 }
 public Map<String,Object> capture(ActorContext actor,AuthoringRequests.Capture r){return capture(actor,actor.actorType()==ActorContext.ActorType.AGENT?"AGENT":"EXTERNAL",null,r.rawContent(),r.sourceType(),r.sourceRef(),r.taskContext(),r.evidenceRefs(),r.language(),!Boolean.FALSE.equals(r.generateDraft()),r.dedupKey());}

 private Map<String,Object> capture(ActorContext actor,String channel,String agentRole,String raw,String sourceType,String sourceRef,JsonNode taskContext,List<String> evidenceRefs,String language,boolean generate,String dedupKey){
  ObjectNode extracted=db.json.createObjectNode();extracted.put("authoringVersion","1.2");extracted.put("captureChannel",channel);
  String sourceSystem=channel.equals("AGENT")?actor.actorId():"authoring-ui";
  ObjectNode sourceSnapshot=db.json.createObjectNode();sourceSnapshot.put("rawContent",raw);sourceSnapshot.put("sourceType",sourceType);
  ObjectNode sourceMetadata=db.json.createObjectNode();sourceMetadata.put("provenance","RAW_INPUT");sourceMetadata.put("verification","UNVERIFIED");sourceMetadata.put("captureChannel",channel);
  String evidenceType=switch(channel){case "HUMAN"->"USER_NOTE";case "AGENT"->"AGENT_OBSERVATION";default->"EXTERNAL_SOURCE";};
  var sourceEvidence=new Requests.EvidenceInput(evidenceType,sourceSystem,sourceRef,sourceSnapshot,null,null,Instant.now(),0.5,sourceMetadata);
  var captured=ledger.capture(actor,new Requests.Capture(raw,"EXPERIENCE",sourceType,sourceSystem,sourceRef,"AUTHORING_CAPTURE",dedupKey,extracted,null,List.of(sourceEvidence)));UUID candidate=(UUID)captured.get("id");
  Set<String> linkedEvidence=new LinkedHashSet<>(evidenceRefs==null?List.of():evidenceRefs);linkedEvidence.addAll(sourceEvidenceIds(captured));
  db.with(actor,"store authoring context",()->{db.update("""
   insert into exp_authoring_candidate(candidate_id,space_id,capture_channel,agent_role,task_context_json,evidence_refs_json,language,raw_content_hash)
   values(:id,:space,:channel,:role,cast(:context as jsonb),cast(:evidence as jsonb),:language,:hash) on conflict(space_id,candidate_id) do nothing
   """,db.scoped(actor,"id",candidate,"channel",channel,"role",agentRole,"context",db.stringify(object(taskContext)),"evidence",db.stringify(linkedEvidence),"language",blank(language)?"zh-CN":language,"hash",schema.hash(raw)));return null;});
  if(generate){var existing=db.with(actor,null,()->db.list("select id from exp_experience_draft where space_id=:space and candidate_id=:id order by draft_version desc limit 1",db.scoped(actor,"id",candidate)));if(!existing.isEmpty())return draft(actor,(UUID)existing.getFirst().get("id"));return generate(actor,candidate,"GENERATE","AI_GENERATED",null);}
  return candidateView(actor,candidate);
 }

 public Map<String,Object> generate(ActorContext actor,UUID candidate){actor.requireGovernance();return generate(actor,candidate,"REGENERATE","REGENERATED",null);}
 private Map<String,Object> generate(ActorContext actor,UUID candidate,String operation,String source,String instruction){
  var input=input(actor,candidate);var prompt=prompts.current(actor,"EXPERIENCE_DRAFT_V2");long started=System.nanoTime();DraftAssistProvider.Result result=null;ObjectNode normalized;
  try{
   result=provider.generate(new DraftAssistProvider.GenerateRequest(actor,(String)input.candidate().get("source_type"),(String)input.candidate().get("raw_content"),input.context(),input.evidence(),input.language(),prompt));
   try{normalized=sourceContext(schema.normalize(result.content(),(String)input.candidate().get("raw_content"),input.evidenceIds()),input);}
   catch(LedgerException invalid){
    var repairPrompt=prompts.current(actor,"EXPERIENCE_REVISION_V2");result=provider.revise(new DraftAssistProvider.RevisionRequest(actor,(String)input.candidate().get("raw_content"),input.context(),input.evidence(),input.language(),result.content(),"修复 JSON Schema："+invalid.getMessage()+"。不得补造事实。",repairPrompt));
    normalized=sourceContext(schema.normalize(result.content(),(String)input.candidate().get("raw_content"),input.evidenceIds()),input);prompt=repairPrompt;
   }
  }catch(RuntimeException error){
   String message=cut(Objects.toString(error.getMessage(),error.getClass().getSimpleName()),1000);DraftAssistProvider.Result failed=result==null?new DraftAssistProvider.Result(db.tree(Map.of()),"unavailable","unavailable",null,null,null,"USD"):result;
   return insertDraft(actor,input,db.json.createObjectNode(),failed,prompt,operation,"GENERATION_FAILED",instruction,started,message);
  }
  // Persisting and loading suggestions are database operations, not AI generation.
  return insertDraft(actor,input,normalized,result,prompt,operation,source,instruction,started,null);
 }

 public Map<String,Object> revise(ActorContext actor,UUID draftId,AuthoringRequests.Revision r){actor.requireGovernance();var current=current(actor,draftId,r.expectedDraftVersion());var input=input(actor,(UUID)current.get("candidate_id"));var prompt=prompts.current(actor,"EXPERIENCE_REVISION_V2");long started=System.nanoTime();
  try{var result=provider.revise(new DraftAssistProvider.RevisionRequest(actor,(String)input.candidate().get("raw_content"),input.context(),input.evidence(),input.language(),(JsonNode)current.get("structured_content_json"),r.instruction(),prompt));var normalized=sourceContext(schema.normalize(result.content(),(String)input.candidate().get("raw_content"),input.evidenceIds()),input);return insertDraft(actor,input,normalized,result,prompt,"REVISE","AI_REVISED",r.instruction(),started,null);}catch(RuntimeException e){throw e;}
 }

 public Map<String,Object> edit(ActorContext actor,UUID draftId,AuthoringRequests.ManualEdit r){actor.requireGovernance();var current=current(actor,draftId,r.expectedDraftVersion());var input=input(actor,(UUID)current.get("candidate_id"));ObjectNode normalized=sourceContext(schema.normalize(r.structuredContent(),(String)input.candidate().get("raw_content"),input.evidenceIds()),input);preserveAgentOrigins((JsonNode)current.get("structured_content_json"),normalized,r.structuredContent());
  var result=new DraftAssistProvider.Result(normalized,"human","manual-edit",null,null,0d,"USD");var prompt=prompts.current(actor,"EXPERIENCE_REVISION_V2");return insertDraft(actor,input,normalized,result,prompt,"REVISE","HUMAN_EDITED",r.reason(),System.nanoTime(),null);
 }

 public Map<String,Object> regenerate(ActorContext actor,UUID draftId,AuthoringRequests.Regenerate r){actor.requireGovernance();var row=db.with(actor,null,()->{var found=db.one("select * from exp_experience_draft where space_id=:space and id=:id",db.scoped(actor,"id",draftId));if(((Number)found.get("draft_version")).intValue()!=r.expectedDraftVersion()||Set.of("ACCEPTED","REJECTED","SUPERSEDED").contains(found.get("status")))throw LedgerException.conflict("DRAFT_REVISION_CONFLICT");var latest=db.one("select id from exp_experience_draft where space_id=:space and candidate_id=:candidate order by draft_version desc limit 1",db.scoped(actor,"candidate",found.get("candidate_id")));if(!draftId.equals(latest.get("id")))throw LedgerException.conflict("DRAFT_REVISION_CONFLICT");return found;});return generate(actor,(UUID)row.get("candidate_id"),"REGENERATE","REGENERATED",r.reason());}

 public Map<String,Object> accept(ActorContext actor,UUID draftId,AuthoringRequests.Publication r){actor.requireGovernance();var existing=publication(actor,draftId);if(existing!=null){if(r.relatedFamilyId()!=null){var result=new LinkedHashMap<>(existing);var version=(Map<?,?>)existing.get("experience");result.put("problemGroup",groups.attachToReference(actor,(UUID)version.get("experience_version_id"),r.relatedFamilyId(),r.problemRelation(),r.reason()));return result;}return existing;}
  var row=current(actor,draftId,r.expectedDraftVersion());UUID candidateId=(UUID)row.get("candidate_id");JsonNode doc=(JsonNode)row.get("structured_content_json");var candidate=ledger.candidate(actor,candidateId);List<Requests.ClaimInput> claims=publicationClaims(actor,doc,sourceEvidenceIds(candidate));
  ObjectNode applicability=db.json.createObjectNode();applicability.set("conditions",doc.path("applicability"));applicability.set("boundaryConditions",doc.path("boundaryConditions"));
  ObjectNode constraints=db.json.createObjectNode();constraints.set("items",doc.path("constraints"));constraints.set("alternatives",doc.path("alternatives"));constraints.set("tradeoffs",doc.path("tradeoffs"));constraints.put("rootCause",doc.path("rootCause").asText());constraints.put("reusablePrinciple",doc.path("reusablePrinciple").asText());
  String lesson=first(doc,"lesson","reusablePrinciple","decision");String action=join(doc.path("actions"));
  var draft=new Requests.Draft(doc.path("title").asText(),doc.path("summary").asText(),first(doc,"problem","context"),doc.path("decision").asText(),action,doc.path("outcome").asText(),lesson,Instant.now().minusMillis(1),null,applicability,constraints,claims,List.of(),List.of());
  String mode=blank(r.mode())?"CREATE_NEW_FAMILY":r.mode();String requestedDomain=blank(r.domain())?first(doc,"domain"):r.domain();final String domain=blank(requestedDomain)?"general":requestedDomain;String type=blank(r.experienceType())?"DECISION":r.experienceType();String key=blank(r.experienceKey())?domain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+","-")+":"+candidateId:r.experienceKey();
  if(r.relatedFamilyId()!=null){if(!mode.equals("CREATE_NEW_FAMILY"))throw LedgerException.invalid("Alternative causes must be published as new families");groups.validateReference(actor,r.relatedFamilyId(),domain,r.problemRelation());}
  var reviewed=ledger.review(actor,candidateId,new Requests.Review(((Number)candidate.get("revision")).intValue(),draft,r.reason()));
  var version=ledger.verify(actor,candidateId,new Requests.Verify(mode,key,domain,type,r.familyId(),r.expectedSupersedesId(),((Number)reviewed.get("revision")).intValue(),r.reason()));UUID versionId=(UUID)version.get("id");
  db.with(actor,r.reason(),()->{db.update("update exp_experience_draft set status='ACCEPTED' where space_id=:space and id=:draft and status in ('CREATED','AI_REVISED','HUMAN_EDITED')",db.scoped(actor,"draft",draftId));db.update("insert into exp_draft_publication(draft_id,space_id,candidate_id,experience_version_id,accepted_by) values(:draft,:space,:candidate,:version,:actor)",db.scoped(actor,"draft",draftId,"candidate",candidateId,"version",versionId));db.update("""
   insert into exp_experience_summary(version_id,space_id,source_draft_id,l0_fingerprint,l1_compact,l2_summary,provider,model)
   values(:version,:space,:draft,:l0,:l1,:l2,:provider,:model)
   """,db.scoped(actor,"version",versionId,"draft",draftId,"l0",schema.hash(Map.of("domain",domain,"taskType",doc.path("taskType").asText(),"principle",doc.path("reusablePrinciple").asText())),"l1",schema.compact(doc),"l2",schema.summary(doc),"provider",row.get("provider"),"model",row.get("model")));return null;});
  var published=new LinkedHashMap<String,Object>();published.put("draftId",draftId);published.put("candidateId",candidateId);published.put("experience",version);published.put("compact",Map.of("l1",schema.compact(doc),"l2",schema.summary(doc)));
  if(r.relatedFamilyId()!=null)published.put("problemGroup",groups.attachToReference(actor,versionId,r.relatedFamilyId(),r.problemRelation(),r.reason()));return published;
 }

 public Map<String,Object> reject(ActorContext actor,UUID draftId,AuthoringRequests.Reject r){actor.requireGovernance();var row=current(actor,draftId,r.expectedDraftVersion());UUID candidate=(UUID)row.get("candidate_id");db.with(actor,r.reason(),()->{db.update("update exp_experience_draft set status='REJECTED' where space_id=:space and id=:id",db.scoped(actor,"id",draftId));return null;});var c=ledger.candidate(actor,candidate);ledger.disposition(actor,candidate,new Requests.Disposition(((Number)c.get("revision")).intValue(),"REJECTED",null,null,r.reason()));return draft(actor,draftId);}

 public Map<String,Object> draft(ActorContext actor,UUID id){return db.with(actor,null,()->{var row=db.one("select * from exp_experience_draft where space_id=:space and id=:id",db.scoped(actor,"id",id));return draftView(actor,row,true);});}
 public Map<String,Object> candidateView(ActorContext actor,UUID id){return db.with(actor,null,()->{var c=db.one("select * from exp_candidate where space_id=:space and id=:id",db.scoped(actor,"id",id));var rows=db.list("select * from exp_experience_draft where space_id=:space and candidate_id=:id order by draft_version desc limit 1",db.scoped(actor,"id",id));return Map.of("candidate",c,"latestDraft",rows.isEmpty()?Map.of():draftView(actor,rows.getFirst(),false));});}
 public List<Map<String,Object>> inbox(ActorContext actor,String state,String channel,String domain,int limit){return db.with(actor,null,()->{
  String status=switch(Objects.toString(state,"PENDING")){case "FAILED"->"GENERATION_FAILED";case "ACCEPTED"->"ACCEPTED";case "REJECTED"->"REJECTED";default->null;};
  var rows=db.list("""
   select d.*,c.raw_content,c.source_type,c.source_ref,c.created_at candidate_created_at,a.capture_channel,a.agent_role,
    jsonb_array_length(coalesce(d.structured_content_json->'missingInformation','[]'::jsonb)) missing_count
   from exp_experience_draft d join exp_candidate c on c.space_id=d.space_id and c.id=d.candidate_id
   join exp_authoring_candidate a on a.space_id=d.space_id and a.candidate_id=d.candidate_id
   where d.space_id=:space and d.draft_version=(select max(x.draft_version) from exp_experience_draft x where x.space_id=d.space_id and x.candidate_id=d.candidate_id)
    and (cast(:status as text) is null and d.status in ('CREATED','AI_REVISED','HUMAN_EDITED') or d.status=:status)
    and (cast(:channel as text) is null or a.capture_channel=:channel)
    and (cast(:domain as text) is null or d.structured_content_json->>'domain'=:domain)
   order by c.created_at desc,d.id limit :limit
   """,db.scoped(actor,"status",status,"channel",blank(channel)?null:channel,"domain",blank(domain)?null:domain,"limit",Math.clamp(limit,1,100)));List<Map<String,Object>> result=new ArrayList<>();for(var row:rows)result.add(inboxView(row));return result;
 });}
 public Map<String,Object> counts(ActorContext actor){return db.with(actor,null,()->db.one("""
  with latest as (select distinct on(candidate_id) candidate_id,status,structured_content_json from exp_experience_draft where space_id=:space order by candidate_id,draft_version desc)
  select count(*) filter(where status in ('CREATED','AI_REVISED','HUMAN_EDITED')) pending,
   count(*) filter(where status in ('CREATED','AI_REVISED','HUMAN_EDITED') and jsonb_array_length(coalesce(structured_content_json->'missingInformation','[]'::jsonb))>0) needs_input,
   count(*) filter(where status='GENERATION_FAILED') failed,count(*) filter(where status='ACCEPTED') accepted,count(*) filter(where status='REJECTED') rejected from latest
  """,db.scoped(actor)));}

 private Map<String,Object> insertDraft(ActorContext actor,Input input,ObjectNode content,DraftAssistProvider.Result result,DraftAssistProvider.Prompt prompt,String operation,String source,String instruction,long started,String error){return db.with(actor,instruction,()->{
  UUID candidate=(UUID)input.candidate().get("id");db.one("select id from exp_candidate where space_id=:space and id=:id for update",db.scoped(actor,"id",candidate));var previous=db.list("select * from exp_experience_draft where space_id=:space and candidate_id=:id order by draft_version desc limit 1",db.scoped(actor,"id",candidate));int version=previous.isEmpty()?1:((Number)previous.getFirst().get("draft_version")).intValue()+1;
  if(!previous.isEmpty()&&Set.of("CREATED","AI_REVISED","HUMAN_EDITED").contains(previous.getFirst().get("status")))db.update("update exp_experience_draft set status='SUPERSEDED' where space_id=:space and id=:id",db.scoped(actor,"id",previous.getFirst().get("id")));
  UUID id=UUID.randomUUID();String status=error==null?(source.equals("HUMAN_EDITED")?"HUMAN_EDITED":source.equals("AI_REVISED")?"AI_REVISED":"CREATED"):"GENERATION_FAILED";String outputHash=schema.hash(content);String inputHash=schema.hash(Map.of("candidate",candidate,"raw",input.candidate().get("raw_content"),"context",input.context(),"prompt",prompt.code()+":"+prompt.version(),"instruction",Objects.toString(instruction,"")));
  db.update("""
   insert into exp_experience_draft(id,space_id,candidate_id,draft_version,structured_content_json,provider,model,prompt_code,prompt_version,input_hash,output_hash,input_tokens,output_tokens,estimated_cost,currency,revision_source,revision_instruction,status,validation_errors_json,error_summary,created_by_type,created_by)
   values(:id,:space,:candidate,:version,cast(:content as jsonb),:provider,:model,:promptCode,:promptVersion,:inputHash,:outputHash,:inputTokens,:outputTokens,:cost,:currency,:source,:instruction,:status,'[]'::jsonb,:error,:actorType,:actor)
   """,db.scoped(actor,"id",id,"candidate",candidate,"version",version,"content",db.stringify(content),"provider",result.provider(),"model",result.model(),"promptCode",prompt.code(),"promptVersion",prompt.version(),"inputHash",inputHash,"outputHash",outputHash,"inputTokens",result.inputTokens(),"outputTokens",result.outputTokens(),"cost",result.estimatedCost(),"currency",Objects.toString(result.currency(),"USD"),"source",source,"instruction",instruction,"status",status,"error",error));
  ArrayNode diff=previous.isEmpty()?db.json.createArrayNode():schema.diff((JsonNode)previous.getFirst().get("structured_content_json"),content);db.update("insert into exp_draft_revision_log(id,space_id,from_draft_id,to_draft_id,revision_source,instruction,diff_json,actor_type,actor_id) values(:log,:space,:from,:to,:source,:instruction,cast(:diff as jsonb),:actorType,:actor)",db.scoped(actor,"log",UUID.randomUUID(),"from",previous.isEmpty()?null:previous.getFirst().get("id"),"to",id,"source",source,"instruction",instruction,"diff",db.stringify(diff)));
  db.update("insert into exp_llm_invocation(id,space_id,candidate_id,draft_id,operation,provider,model,prompt_code,prompt_version,input_hash,output_hash,status,input_tokens,output_tokens,estimated_cost,currency,latency_ms,error_summary) values(:call,:space,:candidate,:draft,:operation,:provider,:model,:promptCode,:promptVersion,:inputHash,:outputHash,:callStatus,:inputTokens,:outputTokens,:cost,:currency,:latency,:error)",db.scoped(actor,"call",UUID.randomUUID(),"candidate",candidate,"draft",id,"operation",operation,"provider",result.provider(),"model",result.model(),"promptCode",prompt.code(),"promptVersion",prompt.version(),"inputHash",inputHash,"outputHash",outputHash,"callStatus",error==null?"SUCCEEDED":"FAILED","inputTokens",result.inputTokens(),"outputTokens",result.outputTokens(),"cost",result.estimatedCost(),"currency",Objects.toString(result.currency(),"USD"),"latency",Math.max(0,(System.nanoTime()-started)/1_000_000),"error",error));
  var row=db.one("select * from exp_experience_draft where space_id=:space and id=:id",db.scoped(actor,"id",id));var view=draftView(actor,row,true);view.put("diff",diff);return view;
 });}

 private Input input(ActorContext actor,UUID candidate){return db.with(actor,null,()->{var c=db.one("select * from exp_candidate where space_id=:space and id=:id",db.scoped(actor,"id",candidate));var meta=db.one("select * from exp_authoring_candidate where space_id=:space and candidate_id=:id",db.scoped(actor,"id",candidate));JsonNode refs=(JsonNode)meta.get("evidence_refs_json");List<DraftAssistProvider.EvidenceRef> evidence=new ArrayList<>();Set<String> ids=new LinkedHashSet<>();Set<String> sourceIds=sourceEvidenceIds(c);if(refs!=null)for(JsonNode ref:refs){String value=ref.asText();List<Map<String,Object>> found=db.list("select id,evidence_type,source_system,source_ref,reliability from exp_evidence where space_id=:space and (case when :isUuid then id=cast(:ref as uuid) else source_ref=:ref end) and status='ACTIVE' limit 1",db.scoped(actor,"isUuid",uuid(value),"ref",value));if(!found.isEmpty()){var e=found.getFirst();String id=e.get("id").toString();ids.add(id);evidence.add(new DraftAssistProvider.EvidenceRef(id,(String)e.get("evidence_type"),(String)e.get("source_system"),(String)e.get("source_ref"),((Number)e.get("reliability")).doubleValue(),sourceIds.contains(id)));}}
  return new Input(c,(JsonNode)meta.get("task_context_json"),evidence,ids,sourceIds,(String)meta.get("language"));});}
 private record Input(Map<String,Object> candidate,JsonNode context,List<DraftAssistProvider.EvidenceRef> evidence,Set<String> evidenceIds,Set<String> sourceEvidenceIds,String language){}
 private Set<String> sourceEvidenceIds(Map<String,Object> candidate){Set<String> ids=new LinkedHashSet<>();JsonNode extracted=(JsonNode)candidate.get("extracted_json");if(extracted!=null)for(JsonNode id:extracted.path("capturedEvidenceIds"))if(uuid(id.asText()))ids.add(id.asText());return ids;}
 private ObjectNode sourceContext(ObjectNode doc,Input input){
  ArrayNode mappings=doc.withArray("evidenceMappings");
  for(JsonNode mapping:mappings)if(input.sourceEvidenceIds().contains(mapping.path("evidenceRef").asText()))((ObjectNode)mapping).put("relation","CONTEXT");
  for(int i=0;i<doc.path("claims").size();i++)for(String id:input.sourceEvidenceIds()){
   boolean found=false;for(JsonNode mapping:mappings)if(mapping.path("claimIndex").asInt(-1)==i&&id.equals(mapping.path("evidenceRef").asText())){found=true;break;}
   if(!found)mappings.addObject().put("claimIndex",i).put("evidenceRef",id).put("relation","CONTEXT");
  }
  return doc;
 }
 private Map<String,Object> current(ActorContext actor,UUID id,Integer expected){return db.with(actor,null,()->{var row=db.one("select * from exp_experience_draft where space_id=:space and id=:id",db.scoped(actor,"id",id));if(((Number)row.get("draft_version")).intValue()!=expected||!Set.of("CREATED","AI_REVISED","HUMAN_EDITED").contains(row.get("status")))throw LedgerException.conflict("DRAFT_REVISION_CONFLICT");var latest=db.one("select id from exp_experience_draft where space_id=:space and candidate_id=:candidate order by draft_version desc limit 1",db.scoped(actor,"candidate",row.get("candidate_id")));if(!id.equals(latest.get("id")))throw LedgerException.conflict("DRAFT_REVISION_CONFLICT");return row;});}
 private Map<String,Object> draftView(ActorContext actor,Map<String,Object> row,boolean details){Map<String,Object> v=new LinkedHashMap<>();for(String key:List.of("id","candidate_id","draft_version","provider","model","prompt_code","prompt_version","input_tokens","output_tokens","estimated_cost","currency","revision_source","revision_instruction","status","error_summary","created_by_type","created_by","created_at"))v.put(camel(key),row.get(key));v.put("structuredContent",row.get("structured_content_json"));v.put("missingCount",((JsonNode)row.get("structured_content_json")).path("missingInformation").size());if(details){UUID candidate=(UUID)row.get("candidate_id");v.put("candidate",db.one("select * from exp_candidate where space_id=:space and id=:id",db.scoped(actor,"id",candidate)));v.put("history",db.list("select id,draft_version,revision_source,status,provider,model,created_at from exp_experience_draft where space_id=:space and candidate_id=:id order by draft_version desc",db.scoped(actor,"id",candidate)));if(groups.available(actor))v.put("similar",groups.suggest(actor,(JsonNode)row.get("structured_content_json")));else{v.put("similar",List.of());v.put("similarWarning","当前数据库未完成 V7 问题归组迁移；草稿可以继续审核和独立发布，请使用迁移账户执行 Flyway 后再使用关联案例推荐。");}}return v;}
 private Map<String,Object> inboxView(Map<String,Object> row){Map<String,Object> v=new LinkedHashMap<>();v.put("id",row.get("id"));v.put("candidateId",row.get("candidate_id"));v.put("draftVersion",row.get("draft_version"));v.put("status",row.get("status"));v.put("title",((JsonNode)row.get("structured_content_json")).path("title").asText("待审核经验"));v.put("summary",((JsonNode)row.get("structured_content_json")).path("summary").asText());v.put("domain",((JsonNode)row.get("structured_content_json")).path("domain").asText());v.put("taskType",((JsonNode)row.get("structured_content_json")).path("taskType").asText());v.put("confidence",((JsonNode)row.get("structured_content_json")).path("confidence").path("overall").asDouble());v.put("missingCount",row.get("missing_count"));v.put("captureChannel",row.get("capture_channel"));v.put("agentRole",row.get("agent_role"));v.put("sourceType",row.get("source_type"));v.put("sourceRef",row.get("source_ref"));v.put("createdAt",row.get("candidate_created_at"));v.put("provider",row.get("provider"));v.put("model",row.get("model"));v.put("error",row.get("error_summary"));return v;}
 private Map<String,Object> publication(ActorContext actor,UUID draft){return db.with(actor,null,()->{var rows=db.list("select p.draft_id,p.candidate_id,p.experience_version_id,v.family_id,v.version_no,v.title from exp_draft_publication p join exp_experience_version v on v.space_id=p.space_id and v.id=p.experience_version_id where p.space_id=:space and p.draft_id=:id",db.scoped(actor,"id",draft));return rows.isEmpty()?null:Map.of("draftId",draft,"candidateId",rows.getFirst().get("candidate_id"),"experience",rows.getFirst());});}
 private List<Requests.ClaimInput> publicationClaims(ActorContext actor,JsonNode doc,Set<String> sourceEvidenceIds){
  Map<Integer,List<Requests.EvidenceLink>> links=new HashMap<>();
  for(JsonNode m:doc.path("evidenceMappings")){try{
   UUID id=UUID.fromString(m.path("evidenceRef").asText());ledger.evidence(actor,id);
   String relation=sourceEvidenceIds.contains(id.toString())?"CONTEXT":m.path("relation").asText("CONTEXT");
   links.computeIfAbsent(m.path("claimIndex").asInt(),x->new ArrayList<>()).add(new Requests.EvidenceLink(id,relation));
  }catch(Exception ignored){}}
  List<Requests.ClaimInput> result=new ArrayList<>();int i=0;
  for(JsonNode c:doc.path("claims")){
   List<Requests.EvidenceLink> evidence=new ArrayList<>(links.getOrDefault(i,List.of()));
   for(String id:sourceEvidenceIds)if(evidence.stream().noneMatch(link->link.evidenceId().toString().equals(id)))evidence.add(new Requests.EvidenceLink(UUID.fromString(id),"CONTEXT"));
   boolean observed=c.path("kind").asText().equals("OBSERVED")&&evidence.stream().anyMatch(x->x.supportType().equals("SUPPORTS"));
   String origin=observed?"OBSERVED":c.path("originType").asText("AGENT_DERIVED");
   if(!Set.of("HUMAN_ASSERTED","AGENT_DERIVED").contains(origin)&&!observed)origin="AGENT_DERIVED";
   String type=observed?"OBSERVATION":c.path("claimType").asText("RECOMMENDATION");
   String method=origin.equals("AGENT_DERIVED")?firstNonBlank(c.path("derivationMethod").asText(),"LLM extraction from immutable candidate input"):null;
   result.add(new Requests.ClaimInput(type,c.path("content").asText(),origin,method,evidence));i++;
  }
  if(result.isEmpty()){
   List<Requests.EvidenceLink> sourceLinks=sourceEvidenceIds.stream().map(id->new Requests.EvidenceLink(UUID.fromString(id),"CONTEXT")).toList();
   result.add(new Requests.ClaimInput("LESSON",first(doc,"reusablePrinciple","lesson","decision"),"AGENT_DERIVED","Draft synthesis from immutable candidate input",sourceLinks));
  }
  return result;
 }
 private void preserveAgentOrigins(JsonNode before,ObjectNode normalized,JsonNode submitted){Map<String,String> old=new HashMap<>();before.path("claims").forEach(c->old.put(c.path("content").asText(),c.path("originType").asText()));int i=0;for(JsonNode c:normalized.withArray("claims")){String content=c.path("content").asText();String submittedOrigin=submitted.path("claims").path(i).path("originType").asText();if("AGENT_DERIVED".equals(old.get(content)))((ObjectNode)c).put("originType","AGENT_DERIVED");else if("HUMAN_ASSERTED".equals(submittedOrigin))((ObjectNode)c).put("originType","HUMAN_ASSERTED");i++;}}
 private ObjectNode object(JsonNode n){return n!=null&&n.isObject()?(ObjectNode)n.deepCopy():db.json.createObjectNode();}
 private void put(ObjectNode n,String key,String value){if(!blank(value))n.put(key,value);}
 private String first(JsonNode n,String... fields){for(String f:fields){String s=n.path(f).asText();if(!blank(s))return s;}return "";}
 private String firstNonBlank(String... values){for(String s:values)if(!blank(s))return s;return "";}
 private String join(JsonNode n){List<String> values=new ArrayList<>();if(n.isArray())n.forEach(x->values.add(x.asText()));return String.join("\n",values);}
 private boolean blank(String s){return s==null||s.isBlank();}
 private boolean uuid(String s){try{UUID.fromString(s);return true;}catch(Exception e){return false;}}
 private String cut(String s,int max){return s.length()<=max?s:s.substring(0,max);}
 private String camel(String s){StringBuilder b=new StringBuilder();boolean upper=false;for(char c:s.toCharArray()){if(c=='_'){upper=true;continue;}b.append(upper?Character.toUpperCase(c):c);upper=false;}return b.toString();}
}
