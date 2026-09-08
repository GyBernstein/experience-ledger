package com.example.ledger.application;

import com.example.ledger.api.Requests.*;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class LedgerService {
 private final Db db; private final Validator validator; private final MeterRegistry metrics;
 public LedgerService(Db db,Validator validator,MeterRegistry metrics){this.db=db;this.validator=validator;this.metrics=metrics;}
 public Map<String,Object> capture(ActorContext a,Capture r){
  if(r.extracted()!=null && !r.extracted().isObject())throw LedgerException.invalid("extracted must be an object");
  rejectPrivateFields(db.tree(r));
  return db.with(a,"capture",()->{
   String key=r.dedupKey();
   if(key==null || key.isBlank()) key=r.sourceSystem()!=null && r.sourceRef()!=null && r.eventType()!=null
    ? hash(db.stringify(List.of(a.spaceId().toString(),r.sourceSystem(),r.sourceRef(),r.eventType()))) : UUID.randomUUID().toString();
   UUID id=UUID.randomUUID();
   var p=db.scoped(a,"id",id,"key",key,"content",r.content(),"candidateType",or(r.candidateType(),"EXPERIENCE"),"sourceType",or(r.sourceType(),a.actorType().name()),
    "sourceSystem",r.sourceSystem(),"sourceRef",r.sourceRef(),"eventType",r.eventType(),"extracted",db.stringify(r.extracted()));
   int inserted=db.update("""
    INSERT INTO exp_candidate(id,space_id,candidate_type,raw_content,extracted_json,source_type,source_system,source_ref,event_type,dedup_key,created_by_type,created_by)
    VALUES(:id,:space,:candidateType,:content,cast(:extracted as jsonb),:sourceType,:sourceSystem,:sourceRef,:eventType,:key,:actorType,:actor)
    ON CONFLICT(space_id,dedup_key) DO NOTHING
    """,p);
   if(inserted==0) return db.one("select * from exp_candidate where space_id=:space and dedup_key=:key",p);
   UUID episode=null;
   if(r.episode()!=null) episode=createEpisode(a,r);
   List<UUID> evidence=new ArrayList<>();
   for(EvidenceInput e:safe(r.evidence())) evidence.add(db.uuid(createEvidenceInternal(a,e),"id"));
   ObjectNode extracted=(ObjectNode)(r.extracted()==null?db.tree(Map.of()):r.extracted().deepCopy());
   extracted.set("capturedEvidenceIds",db.tree(evidence));
   db.update("update exp_candidate set episode_id=:episode,extracted_json=cast(:extracted as jsonb) where id=:id and space_id=:space",
    db.scoped(a,"id",id,"episode",episode,"extracted",db.stringify(extracted)));
   enqueue(a,"CANDIDATE",id);
   return candidateInternal(a,id,false);
  });
 }
 private UUID createEpisode(ActorContext a,Capture capture){
  var r=capture.episode(); UUID id=UUID.randomUUID();
  db.update("""
   insert into exp_episode(id,space_id,title,summary,occurred_at,completed_at,context_json,source_type,source_system,source_ref,created_by_type,created_by)
   values(:id,:space,:title,:summary,:occurred,:completed,cast(:context as jsonb),:sourceType,:sourceSystem,:sourceRef,:actorType,:actor)
   """,db.scoped(a,"id",id,"title",r.title(),"summary",or(r.summary(),r.result()),"occurred",r.occurredAt(),"completed",r.completedAt(),"context",db.stringify(r),
   "sourceType",or(capture.sourceType(),a.actorType().name()),"sourceSystem",capture.sourceSystem(),"sourceRef",capture.sourceRef())); return id;
 }
 public Map<String,Object> candidate(ActorContext a,UUID id){return db.with(a,null,()->candidateInternal(a,id,false));}
 private Map<String,Object> candidateInternal(ActorContext a,UUID id,boolean lock){return db.one("select * from exp_candidate where space_id=:space and id=:id"+(lock?" for update":""),db.scoped(a,"id",id));}
 public List<Map<String,Object>> candidates(ActorContext a,String status,int limit){return db.with(a,null,()->db.list("select * from exp_candidate where space_id=:space and (cast(:status as text) is null or status=:status) order by created_at desc,id limit :limit",db.scoped(a,"status",status,"limit",Math.clamp(limit,1,100))));}
 public Map<String,Object> review(ActorContext a,UUID id,Review r){a.requireGovernance();validateDraft(r.draft());return db.with(a,r.reason(),()->{
  var c=candidateInternal(a,id,true);checkRevision(c,r.expectedRevision());
  JsonNode old=(JsonNode)c.get("extracted_json");
  ClaimRules.preserveOrigin(old.has("draft")?old.get("draft"):old,db.tree(r.draft()));
  var state=CandidateState.valueOf((String)c.get("status"));
  if(state==CandidateState.NEW){transition(a,id,state,CandidateState.ENRICHED);state=CandidateState.ENRICHED;}
  if(state==CandidateState.ENRICHED){transition(a,id,state,CandidateState.PENDING_REVIEW);state=CandidateState.PENDING_REVIEW;}
  if(state!=CandidateState.PENDING_REVIEW)throw LedgerException.conflict("CANDIDATE_STATE_CONFLICT");
  var extracted=(ObjectNode)old.deepCopy();extracted.set("draft",db.tree(r.draft()));
  db.update("update exp_candidate set extracted_json=cast(:draft as jsonb),processing_status='READY',processing_error=null where space_id=:space and id=:id",db.scoped(a,"id",id,"draft",db.stringify(extracted)));
  return candidateInternal(a,id,false);
 });}
 public Map<String,Object> verify(ActorContext a,UUID id,Verify r){a.requireGovernance();try{return db.with(a,r.reason(),()->{
  var c=candidateInternal(a,id,true);checkRevision(c,r.expectedRevision());
  CandidateState.valueOf((String)c.get("status")).transitionTo(CandidateState.VERIFIED);
  JsonNode draftNode=((JsonNode)c.get("extracted_json")).path("draft");
  if(draftNode.isMissingNode()) throw LedgerException.invalid("Review a structured draft before verification");
  Draft draft=db.json.convertValue(draftNode,Draft.class); validateDraft(draft);
  UUID family;UUID previous=null;int no=1;
  if("CREATE_NEW_FAMILY".equals(r.mode())){
   if(r.familyId()!=null || r.expectedSupersedesId()!=null)throw LedgerException.invalid("New family cannot supersede");
   if(blank(r.experienceKey())||blank(r.domain())||blank(r.experienceType()))throw LedgerException.invalid("experienceKey, domain, experienceType required");
   family=UUID.randomUUID();
   db.update("insert into exp_experience_family(id,space_id,experience_key,domain,experience_type,created_by_type,created_by) values(:id,:space,:key,:domain,:type,:actorType,:actor)",
    db.scoped(a,"id",family,"key",r.experienceKey(),"domain",r.domain(),"type",r.experienceType()));
  }else if("CREATE_NEW_VERSION".equals(r.mode())){
   if(r.familyId()==null || r.expectedSupersedesId()==null)throw LedgerException.invalid("familyId and expectedSupersedesId required");
   family=r.familyId();
   db.one("select id from exp_experience_family where space_id=:space and id=:id for update",db.scoped(a,"id",family));
   var last=db.one("select * from exp_experience_version where space_id=:space and family_id=:id order by version_no desc limit 1",db.scoped(a,"id",family));
   if(!r.expectedSupersedesId().equals(last.get("id")) || !"VERIFIED".equals(last.get("status")))throw LedgerException.conflict("VERSION_CONFLICT");
   previous=(UUID)last.get("id");no=((Number)last.get("version_no")).intValue()+1;
  }else throw LedgerException.invalid("Unknown promotion mode");
  UUID version=UUID.randomUUID();
  var p=db.scoped(a,"id",version,"family",family,"no",no,"previous",previous,"title",draft.title(),"summary",draft.summary(),"problem",or(draft.problem(),""),
   "decision",or(draft.decision(),""),"action",or(draft.action(),""),"outcome",or(draft.outcomeSummary(),""),"lesson",draft.lesson(),"app",db.stringify(draft.applicability()),"constraints",db.stringify(draft.constraints()),
   "from",draft.validFrom(),"to",draft.validTo(),"text",retrievalText(draft));
  db.update("""
   insert into exp_experience_version(id,space_id,family_id,version_no,supersedes_id,title,summary,problem,decision,action,outcome_summary,lesson,applicability_json,constraints_json,valid_from,valid_to,retrieval_text,created_by_type,created_by)
   values(:id,:space,:family,:no,:previous,:title,:summary,:problem,:decision,:action,:outcome,:lesson,cast(:app as jsonb),cast(:constraints as jsonb),:from,:to,:text,:actorType,:actor)
   """,p);
  int sequence=0;
  for(ClaimInput claim:draft.claims()){
   UUID claimId=UUID.randomUUID();
   db.update("""
    insert into exp_experience_claim(id,space_id,experience_version_id,claim_type,content,origin_type,derivation_method,sequence_no)
    values(:id,:space,:version,:type,:content,:origin,:method,:sequence)
    """,db.scoped(a,"id",claimId,"version",version,"type",claim.claimType(),"content",claim.content(),"origin",claim.originType(),"method",claim.derivationMethod(),"sequence",sequence++));
   for(EvidenceLink link:safe(claim.evidence())){
    evidenceInternal(a,link.evidenceId());
    db.update("insert into exp_claim_evidence(space_id,claim_id,evidence_id,support_type) values(:space,:claim,:evidence,:type)",db.scoped(a,"claim",claimId,"evidence",link.evidenceId(),"type",link.supportType()));
   }
  }
  for(ContextInput ref:safe(draft.contextRefs())) db.update("insert into exp_context_ref(id,space_id,experience_version_id,ref_type,ref_value,source_system) values(:id,:space,:version,:type,:value,:source)",db.scoped(a,"id",UUID.randomUUID(),"version",version,"type",ref.refType(),"value",ref.refValue(),"source",or(ref.sourceSystem(),"")));
  List<EpisodeLink> episodes=new ArrayList<>(safe(draft.episodes()));
  UUID capturedEpisode=(UUID)c.get("episode_id");
  if(capturedEpisode!=null && episodes.stream().noneMatch(e->e.episodeId().equals(capturedEpisode)&&e.relationType().equals("SOURCE")))episodes.add(new EpisodeLink(capturedEpisode,"SOURCE"));
  for(EpisodeLink link:episodes) db.update("insert into exp_experience_episode(space_id,experience_version_id,episode_id,relation_type) values(:space,:version,:episode,:type)",db.scoped(a,"version",version,"episode",link.episodeId(),"type",link.relationType()));
  db.update("update exp_candidate set status='VERIFIED',target_version_id=:version where space_id=:space and id=:id",db.scoped(a,"id",id,"version",version));
  enqueue(a,"VERSION",version);
  return versionInternal(a,version);
 });}catch(RuntimeException e){metrics.counter("ledger.promotion.failures").increment();throw e;}}
 public Map<String,Object> supersede(ActorContext a,UUID family,Supersede r){return verify(a,r.candidateId(),new Verify("CREATE_NEW_VERSION",null,null,null,family,r.expectedSupersedesId(),r.expectedRevision(),r.reason()));}
 public Map<String,Object> disposition(ActorContext a,UUID id,Disposition r){a.requireGovernance();return db.with(a,r.reason(),()->{
  var c=candidateInternal(a,id,true);checkRevision(c,r.expectedRevision());
  CandidateState next;try{next=CandidateState.valueOf(r.status());}catch(Exception e){throw LedgerException.invalid("Unknown status");}
  if(!Set.of(CandidateState.MERGED,CandidateState.REJECTED,CandidateState.DUPLICATE,CandidateState.EXPIRED).contains(next))throw LedgerException.invalid("Unsupported disposition");
  CandidateState.valueOf((String)c.get("status")).transitionTo(next);
  if(next==CandidateState.MERGED || next==CandidateState.DUPLICATE){
   if((r.targetVersionId()==null)==(r.targetEpisodeId()==null))throw LedgerException.invalid("Exactly one merge target required");
   if(r.targetVersionId()!=null)versionInternal(a,r.targetVersionId());
   else db.one("select id from exp_episode where space_id=:space and id=:id",db.scoped(a,"id",r.targetEpisodeId()));
  }
  db.update("update exp_candidate set status=:status,target_version_id=:version,target_episode_id=:episode where space_id=:space and id=:id",db.scoped(a,"id",id,"status",next.name(),"version",r.targetVersionId(),"episode",r.targetEpisodeId()));
  return candidateInternal(a,id,false);
 });}
 public Map<String,Object> evidence(ActorContext a,UUID id){return db.with(a,null,()->evidenceInternal(a,id));}
 private Map<String,Object> evidenceInternal(ActorContext a,UUID id){return db.one("select * from exp_evidence where space_id=:space and id=:id",db.scoped(a,"id",id));}
 public Map<String,Object> createEvidence(ActorContext a,EvidenceInput r){return db.with(a,"capture evidence",()->createEvidenceInternal(a,r));}
 private Map<String,Object> createEvidenceInternal(ActorContext a,EvidenceInput r){
  UUID id=UUID.randomUUID();String snapshot=db.stringify(canonical(r.snapshot()==null?db.tree(Map.of()):r.snapshot()));
  if(r.snapshot()==null && r.snapshotUri()!=null && blank(r.contentHash()))throw LedgerException.invalid("URI-only evidence requires contentHash of the external object");
  String computed=hash(snapshot);
  if(r.snapshot()!=null && r.contentHash()!=null && !computed.equals(r.contentHash()))throw LedgerException.invalid("Snapshot contentHash mismatch");
  String contentHash=r.snapshot()==null && r.snapshotUri()!=null?r.contentHash():computed;
  db.update("""
   insert into exp_evidence(id,space_id,evidence_type,source_system,source_ref,snapshot_json,snapshot_uri,content_hash,observed_at,reliability,metadata_json)
   values(:id,:space,:type,:system,:ref,cast(:snapshot as jsonb),:uri,:hash,:observed,:reliability,cast(:metadata as jsonb))
   """,db.scoped(a,"id",id,"type",r.evidenceType(),"system",r.sourceSystem(),"ref",r.sourceRef(),"snapshot",snapshot,"uri",r.snapshotUri(),"hash",contentHash,"observed",r.observedAt(),"reliability",r.reliability()==null?0.5:r.reliability(),"metadata",db.stringify(r.metadata())));
  return evidenceInternal(a,id);
 }
 public Map<String,Object> correctEvidence(ActorContext a,UUID id,EvidenceInput r,String reason){a.requireGovernance();return db.with(a,reason,()->{
  var old=db.one("select * from exp_evidence where space_id=:space and id=:id for update",db.scoped(a,"id",id));
  if(!"ACTIVE".equals(old.get("status")))throw LedgerException.conflict("EVIDENCE_IMMUTABLE");
  var replacement=createEvidenceInternal(a,r);
  db.update("update exp_evidence set status='SUPERSEDED',corrected_by_evidence_id=:newId where space_id=:space and id=:id",db.scoped(a,"id",id,"newId",replacement.get("id")));
  return replacement;
 });}
 public Map<String,Object> usage(ActorContext a,UsageInput r){return db.with(a,"record usage",()->{
  versionInternal(a,r.versionId());UUID id=UUID.randomUUID();
  db.update("""
   insert into exp_usage(id,space_id,experience_version_id,query_context_json,retrieval_score,applicability_score,recommended,actually_used,actor_type,actor_id,metadata_json)
   values(:id,:space,:version,cast(:context as jsonb),:retrieval,:app,:recommended,:used,:actorType,:actor,cast(:metadata as jsonb))
   """,db.scoped(a,"id",id,"version",r.versionId(),"context",db.stringify(r.queryContext()),"retrieval",r.retrievalScore(),"app",r.applicabilityScore(),"recommended",r.recommended(),"used",r.actuallyUsed(),"metadata",db.stringify(r.metadata())));
  if(r.immediateOutcome()!=null) outcomeInternal(a,id,r.immediateOutcome());
  return db.one("select * from exp_usage where space_id=:space and id=:id",db.scoped(a,"id",id));
 });}
 public Map<String,Object> outcome(ActorContext a,UUID usage,OutcomeInput r){return db.with(a,"record outcome",()->outcomeInternal(a,usage,r));}
 private Map<String,Object> outcomeInternal(ActorContext a,UUID usage,OutcomeInput r){
  db.one("select id from exp_usage where space_id=:space and id=:id",db.scoped(a,"id",usage));
  if(r.evidenceId()!=null)evidenceInternal(a,r.evidenceId());UUID id=UUID.randomUUID();
  db.update("""
   insert into exp_outcome(id,space_id,usage_id,outcome_type,metrics_json,notes,observed_at,evidence_id,created_by_type,created_by)
   values(:id,:space,:usage,:type,cast(:metrics as jsonb),:notes,:observed,:evidence,:actorType,:actor)
   """,db.scoped(a,"id",id,"usage",usage,"type",r.outcomeType(),"metrics",db.stringify(r.metrics()),"notes",or(r.notes(),""),"observed",r.observedAt(),"evidence",r.evidenceId()));
  return db.one("select * from exp_outcome where space_id=:space and id=:id",db.scoped(a,"id",id));
 }
 public List<Map<String,Object>> outcomes(ActorContext a,UUID usage){return db.with(a,null,()->db.list("select * from exp_outcome where space_id=:space and usage_id=:id order by created_at,id",db.scoped(a,"id",usage)));}
 public Map<String,Object> feedback(ActorContext a,UUID version,Feedback r){return db.with(a,"experience feedback",()->{
  versionInternal(a,version);
  return capture(a,new Capture(r.content(),"EVOLUTION","FEEDBACK","ledger",version.toString(),"FEEDBACK",r.dedupKey()==null?UUID.randomUUID().toString():r.dedupKey(),db.tree(Map.of("sourceVersionId",version)),null,null));
 });}
 public Map<String,Object> relation(ActorContext a,Relation r){a.requireGovernance();return db.with(a,r.reason(),()->{
  versionInternal(a,r.fromVersionId());versionInternal(a,r.toVersionId());UUID id=UUID.randomUUID();
  db.update("insert into exp_relation(id,space_id,from_version_id,to_version_id,relation_type) values(:id,:space,:from,:to,:type)",db.scoped(a,"id",id,"from",r.fromVersionId(),"to",r.toVersionId(),"type",r.relationType()));
  return db.one("select * from exp_relation where space_id=:space and id=:id",db.scoped(a,"id",id));
 });}
 public Map<String,Object> invalidate(ActorContext a,UUID version,String reason){a.requireGovernance();return db.with(a,reason,()->{
  int changed=db.update("update exp_experience_version set status='INVALIDATED' where space_id=:space and id=:id and status='VERIFIED'",db.scoped(a,"id",version));
  if(changed!=1)throw LedgerException.conflict("VERSION_CONFLICT");return versionInternal(a,version);
 });}
 public Map<String,Object> versionInternal(ActorContext a,UUID version){return db.one("select * from exp_experience_version where space_id=:space and id=:id",db.scoped(a,"id",version));}
 public List<Map<String,Object>> audit(ActorContext a,UUID target,int limit){return db.with(a,null,()->db.list("select * from exp_audit_event where space_id=:space and (cast(:id as uuid) is null or target_id=:id) order by created_at desc,id limit :limit",db.scoped(a,"id",target,"limit",Math.clamp(limit,1,1000))));}
 public Map<String,Object> retryProcessing(ActorContext a,UUID id,boolean candidate){a.requireGovernance();return db.with(a,"retry derived processing",()->{
  if(candidate){var c=candidateInternal(a,id,true);if(CandidateState.valueOf((String)c.get("status")).terminal())throw LedgerException.conflict("CANDIDATE_TERMINAL");}
  else versionInternal(a,id);
  String type=candidate?"CANDIDATE":"VERSION";
  db.update("insert into processing_job(space_id,target_type,target_id) values(:space,:type,:id) on conflict(space_id,target_type,target_id) do update set status='PENDING',attempts=0,available_at=clock_timestamp(),lease_until=null,last_error=null where processing_job.status<>'RUNNING'",db.scoped(a,"type",type,"id",id));
  return db.one("select * from processing_job where space_id=:space and target_type=:type and target_id=:id",db.scoped(a,"type",type,"id",id));
 });}
 private void transition(ActorContext a,UUID id,CandidateState from,CandidateState to){from.transitionTo(to);db.update("update exp_candidate set status=:status where space_id=:space and id=:id",db.scoped(a,"id",id,"status",to.name()));}
 private void checkRevision(Map<String,Object> c,Integer revision){if(revision==null || ((Number)c.get("revision")).intValue()!=revision)throw LedgerException.conflict("CANDIDATE_REVISION_CONFLICT");}
 private void enqueue(ActorContext a,String type,UUID id){db.update("insert into processing_job(space_id,target_type,target_id) values(:space,:type,:id) on conflict do nothing",db.scoped(a,"type",type,"id",id));}
 private void validateDraft(Draft r){
  if(!validator.validate(r).isEmpty())throw LedgerException.invalid("Incomplete experience draft");
  if(r.validTo()!=null && !r.validTo().isAfter(r.validFrom()))throw new LedgerException("TEMPORAL_RANGE_INVALID","validTo must be after validFrom",400);
  if(r.validFrom().isAfter(Instant.now()))throw LedgerException.invalid("Future version activation is outside V1");
  for(ClaimInput c:r.claims()){
   ClaimRules.validate(c.claimType(),c.originType(),c.derivationMethod());
   if(c.originType().equals("OBSERVED") && safe(c.evidence()).stream().noneMatch(e->e.supportType().equals("SUPPORTS")))throw LedgerException.invalid("Observed claims require supporting evidence");
  }
 }
 private String retrievalText(Draft d){StringBuilder b=new StringBuilder(String.join(" ",d.title(),d.summary(),or(d.problem(),""),or(d.decision(),""),or(d.action(),""),d.lesson()));
  d.claims().forEach(c->b.append(' ').append(c.content()));safe(d.contextRefs()).forEach(c->b.append(' ').append(c.refValue()));return b.toString();}
 public static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private JsonNode canonical(JsonNode n){if(n.isObject()){var out=db.json.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(n.get(k))));return out;}if(n.isArray()){var out=db.json.createArrayNode();n.forEach(v->out.add(canonical(v)));return out;}return n;}
 private void rejectPrivateFields(JsonNode n){if(n.isObject()){n.fields().forEachRemaining(e->{String k=e.getKey().replace("_","").toLowerCase(Locale.ROOT);if(Set.of("chainofthought","privatechainofthought","privatereasoning").contains(k))throw LedgerException.invalid("Private chain-of-thought is not an accepted capture field");rejectPrivateFields(e.getValue());});}else if(n.isArray())n.forEach(this::rejectPrivateFields);}
 private static boolean blank(String s){return s==null||s.isBlank();}
 private static String or(String s,String fallback){return s==null?(fallback==null?"":fallback):s;}
 private static <T> List<T> safe(List<T> s){return s==null?List.of():s;}
}
