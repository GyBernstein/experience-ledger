package com.example.ledger.judgment;

import com.example.ledger.application.LedgerService;
import com.example.ledger.api.Requests.*;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.judgment.JudgmentRequests.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class JudgmentService {
 private final Db db;private final LedgerService ledger;private final JudgmentStore store;
 public JudgmentService(Db db,LedgerService ledger,JudgmentStore store){this.db=db;this.ledger=ledger;this.store=store;}
 private void human(ActorContext a){if(a.actorType()!=ActorContext.ActorType.HUMAN)throw new LedgerException("HUMAN_AUTHOR_REQUIRED","Judgment cards require a human author",403);}
 public Object gate(ActorContext a,Gate r){human(a);return JudgmentRules.gate(r);}
 public Object save(ActorContext a,UUID id,Save r){human(a);
  if(!"KEEP_JUDGMENT".equals(JudgmentRules.gate(r.gate()).get("recommendation")))return Map.of("saved",false,"gate",JudgmentRules.gate(r.gate()));
  return db.with(a,"save judgment candidate",()->{
   String payload=db.stringify(r);Map<String,Object> c;
   if(id==null){
    if(r.eventKey()==null||r.eventKey().isBlank())throw LedgerException.invalid("eventKey required for safe capture retry");
    c=ledger.capture(a,new Capture(r.rule().title()+"\n"+JudgmentRules.text(r.rule()),"EXPERIENCE","HUMAN","judgment-editor",null,null,
      "judgment:"+LedgerService.hash(a.actorId()+":"+r.eventKey()),db.tree(Map.of("judgmentInput",r,"judgmentCaptureHash",LedgerService.hash(payload))),null,null));
    String original=((JsonNode)c.get("extracted_json")).path("judgmentCaptureHash").asText();
    if(!original.equals(LedgerService.hash(payload)))throw LedgerException.conflict("CAPTURE_EVENT_CONFLICT");
    if(!"NEW".equals(c.get("status")))return Map.of("saved",true,"candidate",c);
   } else {
    c=db.one("select * from exp_candidate where space_id=:space and id=:id for update",db.scoped(a,"id",id));
    if(store.input((JsonNode)c.get("extracted_json"))==null)throw LedgerException.invalid("Not a judgment candidate");
    if(!Objects.equals(c.get("revision"),r.expectedRevision()))throw LedgerException.conflict("CANDIDATE_REVISION_CONFLICT");
    if(!Set.of("NEW","ENRICHED","PENDING_REVIEW").contains(c.get("status")))throw LedgerException.conflict("CANDIDATE_STATE_CONFLICT");
    ObjectNode extracted=(ObjectNode)((JsonNode)c.get("extracted_json")).deepCopy();extracted.set("judgmentInput",db.tree(r));
    db.update("update exp_candidate set extracted_json=cast(:data as jsonb) where space_id=:space and id=:id",db.scoped(a,"id",id,"data",db.stringify(extracted)));
    c=ledger.candidate(a,id);
   }
   for(UUID evidence:r.evidenceIds()==null?List.<UUID>of():r.evidenceIds())db.one("select id from exp_evidence where space_id=:space and id=:id and status='ACTIVE'",db.scoped(a,"id",evidence));
   c=ledger.review(a,(UUID)c.get("id"),new Review(((Number)c.get("revision")).intValue(),JudgmentRules.draft(r),"human judgment draft"));
   return Map.of("saved",true,"candidate",c);
  });
 }
 public Object publish(ActorContext a,UUID id,Publish r){human(a);return db.with(a,r.reason(),()->{
  var c=ledger.candidate(a,id);Save s=store.input((JsonNode)c.get("extracted_json"));if(s==null)throw LedgerException.invalid("Not a judgment candidate");
  UUID family=r.familyId()==null?s.sourceFamilyId():r.familyId(), previous=r.expectedSupersedesId()==null?s.sourceVersionId():r.expectedSupersedesId();
  if((family==null)!=(previous==null))throw LedgerException.invalid("Both successor identifiers required");
  return ledger.verify(a,id,new Verify(family==null?"CREATE_NEW_FAMILY":"CREATE_NEW_VERSION",s.experienceKey(),s.domain(),s.rule().negative()?"WARNING":"DECISION",family,previous,r.expectedRevision(),r.reason()));
 });}
 public Object list(ActorContext a,String q,String domain,int limit,int offset){a.requireGovernance();return db.with(a,null,()->db.list("""
  select v.id,v.family_id,v.title,v.status,v.valid_from,v.valid_to,f.domain,j.rule_json,t.native_track,coalesce(ve.validation_status,'VERIFIED') as validation_status
  from exp_judgment_rule j join exp_experience_version v on v.space_id=j.space_id and v.id=j.experience_version_id
  join exp_experience_family f on f.space_id=v.space_id and f.id=v.family_id
  join exp_experience_track t on t.space_id=f.space_id and t.family_id=f.id
  left join lateral (select validation_status from exp_validation_event where space_id=v.space_id and version_id=v.id order by created_at desc,id desc limit 1) ve on true
  where j.space_id=:space and v.status='VERIFIED' and v.valid_from<=clock_timestamp() and (v.valid_to is null or v.valid_to>clock_timestamp())
  and (:domain='' or f.domain=:domain) and (:q='' or v.search_vector @@ plainto_tsquery('simple',ledger_fts_text(:q)))
  order by v.recorded_at desc,v.id limit :limit offset :offset
  """,db.scoped(a,"q",q,"domain",domain,"limit",Math.clamp(limit,1,100),"offset",Math.max(0,offset))));}
 public Object detail(ActorContext a,UUID id){a.requireGovernance();return db.with(a,null,()->{
  var v=ledger.versionInternal(a,id);v.putAll(db.one("select domain,experience_key,experience_type from exp_experience_family where space_id=:space and id=:id",db.scoped(a,"id",v.get("family_id"))));store.attach(a,v);v.put("validations",db.list("select validation_status,assessed_confidence,reason,created_at from exp_validation_event where space_id=:space and version_id=:id order by created_at desc,id desc",db.scoped(a,"id",id)));v.put("reuseHistory",historyInternal(a,id));return v;
 });}
 public Object history(ActorContext a,UUID id){a.requireGovernance();return db.with(a,null,()->historyInternal(a,id));}
 private Object historyInternal(ActorContext a,UUID id){return db.list("select * from exp_reuse_event where space_id=:space and version_id=:id order by created_at desc,id desc",db.scoped(a,"id",id));}
 public Object reuse(ActorContext a,UUID version,Reuse r){a.requireGovernance();
  if(!Set.of("HUMAN","AGENT").contains(r.targetTrack())||!Set.of("NATIVE_ONLY","CROSS_REFERENCE","CROSS_REUSABLE").contains(r.mode())||!Set.of("HUMAN_REVIEW","TEST","REPLAY").contains(r.validationMethod()))throw LedgerException.invalid("Unknown sharing mode, track or validation method");
  var evidence=r.evidenceIds()==null?List.<UUID>of():r.evidenceIds().stream().distinct().toList();
  if(r.mode().equals("CROSS_REUSABLE")&&(r.validationMethod().equals("HUMAN_REVIEW")||evidence.isEmpty()))throw LedgerException.invalid("Reusable sharing requires test/replay evidence");
  return db.with(a,r.reason(),()->{
   var v=db.one("select *, status='VERIFIED' and valid_from<=clock_timestamp() and (valid_to is null or valid_to>clock_timestamp()) as live from exp_experience_version where space_id=:space and id=:id for update",db.scoped(a,"id",version));
   if(!r.mode().equals("NATIVE_ONLY")&&!Boolean.TRUE.equals(v.get("live")))throw LedgerException.conflict("SOURCE_NOT_CURRENT");
   var sharing=store.sharing(a,version);if(r.targetTrack().equals(sharing.get("native_track")))throw LedgerException.invalid("Sharing target must be the other track");
   var old=(Map<?,?>)sharing.get("grant");if(!Objects.equals(old==null?null:old.get("id"),r.expectedPreviousId()))throw LedgerException.conflict("REUSE_REVISION_CONFLICT");
   UUID id=UUID.randomUUID();
   for(UUID e:evidence)db.one("select id from exp_evidence where space_id=:space and id=:id and status='ACTIVE'",db.scoped(a,"id",e));
   db.update("insert into exp_reuse_event(id,space_id,version_id,target_track,previous_id,reuse_mode,validation_method,reason,actor_type,actor_id) values(:id,:space,:version,:target,:previous,:mode,:method,:reason,:actorType,:actor)",db.scoped(a,"id",id,"version",version,"target",r.targetTrack(),"previous",r.expectedPreviousId(),"mode",r.mode(),"method",r.validationMethod(),"reason",r.reason()));
   for(UUID e:evidence)db.update("insert into exp_reuse_evidence(id,space_id,reuse_event_id,evidence_id) values(:id,:space,:event,:evidence)",db.scoped(a,"id",UUID.randomUUID(),"event",id,"evidence",e));
   return store.sharing(a,version);
  });
 }
}
