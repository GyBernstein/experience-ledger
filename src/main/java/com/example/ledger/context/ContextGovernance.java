package com.example.ledger.context;
import com.example.ledger.context.ContextRequests.*;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.util.*;
@Service
public class ContextGovernance {
 private final Db db;private final ContextStore store;
 public ContextGovernance(Db db,ContextStore store){this.db=db;this.store=store;}
 public Object policies(ActorContext a){a.requireGovernance();return db.with(a,null,()->db.list("select * from exp_retrieval_policy where space_id=:space order by updated_at desc,id",db.scoped(a)));}
 public Object policy(ActorContext a,UUID id,Policy r){a.requireGovernance();var rules=r.rules();
  if((rules.requireEvidence()&&rules.maxEvidence()<1)||(rules.requireNegativeCases()&&rules.maxItems()<2)||rules.deepCandidateLimit()<rules.candidateLimit()||!Set.of("OBSERVED","HUMAN_ASSERTED","AGENT_DERIVED","SYSTEM_DERIVED").containsAll(rules.allowedOrigins()))throw LedgerException.invalid("Inconsistent policy");
  return db.with(a,r.reason(),()->{var p=db.scoped(a,"id",id==null?UUID.randomUUID():id,"name",r.name(),"enabled",r.enabled(),"rules",db.stringify(rules),"revision",r.expectedRevision());
   if(id==null)db.update("insert into exp_retrieval_policy(id,space_id,name,enabled,rules_json) values(:id,:space,:name,:enabled,cast(:rules as jsonb))",p);
   else if(db.update("update exp_retrieval_policy set name=:name,enabled=:enabled,rules_json=cast(:rules as jsonb) where space_id=:space and id=:id and revision=:revision",p)!=1)throw LedgerException.conflict("POLICY_REVISION_CONFLICT");
   return db.one("select * from exp_retrieval_policy where space_id=:space and id=:id",p);
  });
 }
 public Object bindings(ActorContext a){a.requireGovernance();return db.with(a,null,()->db.list("select * from exp_agent_binding where space_id=:space order by actor_type,actor_id",db.scoped(a)));}
 public Object bind(ActorContext a,Binding r){a.requireGovernance();try{ActorContext.ActorType.valueOf(r.actorType());}catch(Exception e){throw LedgerException.invalid("Unknown actor type");}return db.with(a,r.reason(),()->{
  db.one("select id from exp_retrieval_policy where space_id=:space and id=:id",db.scoped(a,"id",r.policyId()));
  var p=db.scoped(a,"kind",r.actorType(),"who",r.actorId(),"policy",r.policyId(),"enabled",r.enabled(),"rev",r.expectedRevision());
  db.one("select pg_advisory_xact_lock(hashtextextended(:key,0))",db.params("key",a.spaceId()+":"+r.actorType()+":"+r.actorId()));
  var old=db.list("select * from exp_agent_binding where space_id=:space and actor_type=:kind and actor_id=:who for update",p);
  if(old.isEmpty()){if(r.expectedRevision()!=null&&r.expectedRevision()!=0)throw LedgerException.conflict("BINDING_REVISION_CONFLICT");p.put("id",UUID.randomUUID());db.update("insert into exp_agent_binding(id,space_id,actor_type,actor_id,policy_id,enabled) values(:id,:space,:kind,:who,:policy,:enabled)",p);}
  else if(db.update("update exp_agent_binding set policy_id=:policy,enabled=:enabled where space_id=:space and actor_type=:kind and actor_id=:who and revision=:rev",p)!=1)throw LedgerException.conflict("BINDING_REVISION_CONFLICT");
  return db.one("select * from exp_agent_binding where space_id=:space and actor_type=:kind and actor_id=:who",p);
 });}
 public Object validations(ActorContext a,UUID id){a.requireGovernance();return db.with(a,null,()->{store.source(a,id);return db.list("select * from exp_validation_event where space_id=:space and version_id=:id order by created_at desc,id desc",db.scoped(a,"id",id));});}
 public Object validate(ActorContext a,UUID version,Validation r){a.requireGovernance();if(!Set.of("VERIFIED","ADOPTED","DISPUTED","DEPRECATED").contains(r.status()))throw LedgerException.invalid("Unknown validation status");return db.with(a,r.reason(),()->{
  store.source(a,version);var p=db.scoped(a,"id",UUID.randomUUID(),"version",version,"status",r.status(),"confidence",r.assessedConfidence(),"reason",r.reason());
  db.update("insert into exp_validation_event(id,space_id,version_id,validation_status,assessed_confidence,reason,actor_type,actor_id) values(:id,:space,:version,:status,:confidence,:reason,:actorType,:actor)",p);return db.one("select * from exp_validation_event where space_id=:space and id=:id",p);
 });}
 public Object createCompact(ActorContext a,Compact r){a.requireGovernance();return db.snapshot(a,r.reason(),()->{
  var ids=new LinkedHashSet<>(r.versionIds());if(!ids.contains(r.representativeId()))throw LedgerException.invalid("Representative must belong to sources");
  var sources=ids.stream().map(id->store.source(a,id)).toList();var rep=store.source(a,r.representativeId());
  for(var s:sources)if(!store.live(s)||!r.domain().equals(s.get("domain"))||!Objects.equals(s.get("applicability_json"),rep.get("applicability_json"))||!Objects.equals(s.get("constraints_json"),rep.get("constraints_json"))||ContextStore.negative(s)!=ContextStore.negative(rep))throw LedgerException.invalid("Sources must be current and share domain, scope, constraints and polarity");
  var snap=Map.of("sources",sources.stream().map(s->Map.of("versionId",s.get("id"),"fingerprint",store.fingerprint(s))).toList(),"summaryOrigin","HUMAN_ASSERTED");
  var p=db.scoped(a,"id",UUID.randomUUID(),"domain",r.domain(),"task",r.taskType(),"title",r.title(),"summary",r.summary(),"representative",r.representativeId(),"snapshot",db.stringify(snap));
  db.update("insert into exp_compact(id,space_id,domain,task_type,title,summary,representative_id,snapshot_json) values(:id,:space,:domain,:task,:title,:summary,:representative,cast(:snapshot as jsonb))",p);return db.one("select * from exp_compact where space_id=:space and id=:id",p);
 });}
 public Object compacts(ActorContext a){a.requireGovernance();return db.snapshot(a,null,()->{var rows=db.list("select * from exp_compact where space_id=:space order by created_at desc,id limit 100",db.scoped(a));for(var r:rows)r.put("fresh",store.fresh(r,store.sources(a,(JsonNode)r.get("snapshot_json"))));return rows;});}
 public Object compactState(ActorContext a,UUID id,boolean approve,String reason){a.requireGovernance();return db.snapshot(a,reason,()->{
  var p=db.scoped(a,"id",id,"next",approve?"ACTIVE":"RETIRED");var c=db.one("select * from exp_compact where space_id=:space and id=:id for update",p);
  if(approve&&!store.fresh(c,store.sources(a,(JsonNode)c.get("snapshot_json"))))throw LedgerException.conflict("COMPACT_STALE");
  if((approve&&!c.get("status").equals("DRAFT"))||c.get("status").equals("RETIRED"))throw LedgerException.conflict("COMPACT_STATE_CONFLICT");
  db.update("update exp_compact set status=:next where space_id=:space and id=:id",p);return db.one("select * from exp_compact where space_id=:space and id=:id",p);
 });}
 public Object me(ActorContext a){return db.with(a,null,()->Map.of("actorType",a.actorType(),"actorId",a.actorId(),"spaceId",a.spaceId(),"policy",store.policy(a,null)));}
}
