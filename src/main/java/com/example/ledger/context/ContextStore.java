package com.example.ledger.context;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.application.LedgerService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class ContextStore {
 final Db db;public ContextStore(Db db){this.db=db;}
 public Map<String,Object> source(ActorContext a,UUID id){
  var r=db.one("""
   select v.id,v.family_id,v.title,v.summary,v.lesson,v.decision,v.action,v.outcome_summary,v.status,v.valid_from,v.valid_to,v.recorded_at,v.invalidated_at,
   v.applicability_json,v.constraints_json,f.domain,f.experience_type,s.usage_count,s.success_count,s.failure_count,s.partial_success_count,
   ve.id as assessment_id,coalesce(ve.validation_status,'VERIFIED') as validation_status,coalesce(ve.assessed_confidence,0.5) as assessed_confidence,
   (v.status='VERIFIED' and v.valid_from<=clock_timestamp() and (v.valid_to is null or clock_timestamp()<v.valid_to)) as is_current
   from exp_experience_version v join exp_experience_family f on f.space_id=v.space_id and f.id=v.family_id
   join exp_version_stats s on s.space_id=v.space_id and s.version_id=v.id
   left join lateral (select * from exp_validation_event e where e.space_id=v.space_id and e.version_id=v.id order by created_at desc,id desc limit 1) ve on true
   where v.space_id=:space and v.id=:id
   """,db.scoped(a,"id",id));
  r.put("claims",db.list("select id,claim_type,origin_type,content,derivation_method from exp_experience_claim where space_id=:space and experience_version_id=:id order by sequence_no",db.scoped(a,"id",id)));
  r.put("evidence",db.list("""
   select distinct e.id,e.status,e.content_hash,e.corrected_by_evidence_id,e.reliability,ce.support_type
   from exp_evidence e join exp_claim_evidence ce on ce.space_id=e.space_id and ce.evidence_id=e.id
   join exp_experience_claim c on c.space_id=ce.space_id and c.id=ce.claim_id
   where c.space_id=:space and c.experience_version_id=:id order by e.id,ce.support_type
   """,db.scoped(a,"id",id)));
  r.put("disputed",!db.list("select id from exp_relation where space_id=:space and relation_type='CONTRADICTS' and (from_version_id=:id or to_version_id=:id) limit 1",db.scoped(a,"id",id)).isEmpty());return r;
 }
 public String fingerprint(Map<String,Object> s){var m=new TreeMap<String,Object>(s);for(String k:List.of("is_current","usage_count","success_count","failure_count","partial_success_count","usableEvidence"))m.remove(k);return LedgerService.hash(db.stringify(m));}
 public Map<String,Object> policy(ActorContext a,UUID preview){
  if(preview!=null){a.requireGovernance();return db.one("select * from exp_retrieval_policy where space_id=:space and id=:id and enabled",db.scoped(a,"id",preview));}
  var rows=db.list("select p.* from exp_agent_binding b join exp_retrieval_policy p on p.space_id=b.space_id and p.id=b.policy_id where b.space_id=:space and b.actor_type=:actorType and b.actor_id=:actor and b.enabled and p.enabled",db.scoped(a));
  if(rows.isEmpty())throw new LedgerException("POLICY_REQUIRED","No enabled policy bound to this identity",403);return rows.getFirst();
 }
 public ContextRequests.Rules rules(Map<String,Object> p){return db.json.convertValue(p.get("rules_json"),ContextRequests.Rules.class);}
 @SuppressWarnings("unchecked") public static List<Map<String,Object>> rows(Object o){return (List<Map<String,Object>>)o;}
 public static double number(Map<String,Object> m,String key){return ((Number)m.get(key)).doubleValue();}
 public static boolean negative(Map<String,Object> s){return Set.of("FAILURE","WARNING").contains(s.get("experience_type"));}
 public boolean live(Map<String,Object> s){return Boolean.TRUE.equals(s.get("is_current"))&&!Boolean.TRUE.equals(s.get("disputed"))&&!Set.of("DISPUTED","DEPRECATED").contains(s.get("validation_status"));}
 public List<Map<String,Object>> sources(ActorContext a,JsonNode snapshot){List<Map<String,Object>> out=new ArrayList<>();for(var n:snapshot.path("sources"))out.add(source(a,UUID.fromString(n.path("versionId").asText())));return out;}
 public boolean fresh(Map<String,Object> c,List<Map<String,Object>> sources){var refs=((JsonNode)c.get("snapshot_json")).path("sources");if(refs.size()!=sources.size())return false;for(int i=0;i<refs.size();i++)if(!live(sources.get(i))||!refs.get(i).path("fingerprint").asText().equals(fingerprint(sources.get(i))))return false;return true;}
}
