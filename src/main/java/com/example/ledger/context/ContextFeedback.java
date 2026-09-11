package com.example.ledger.context;
import com.example.ledger.context.ContextRequests.*;
import com.example.ledger.api.Requests.UsageInput;
import com.example.ledger.api.Requests.OutcomeInput;
import com.example.ledger.application.LedgerService;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
@Service
public class ContextFeedback {
 private final Db db;private final ContextService context;private final LedgerService ledger;
 public ContextFeedback(Db db,ContextService context,LedgerService ledger){this.db=db;this.context=context;this.ledger=ledger;}
 public Object feedback(ActorContext a,Feedback r){
  if(r.outcomeType()!=null&&!Set.of("SUCCESS","FAILURE","PARTIAL_SUCCESS","INCONCLUSIVE").contains(r.outcomeType()))throw LedgerException.invalid("Unknown outcome type");
  if(r.reportedCost()!=null&&(r.currency()==null||r.currency().isBlank()))throw LedgerException.invalid("Cost requires currency");
  return db.with(a,"agent feedback",()->{
   db.one("select pg_advisory_xact_lock(hashtextextended(:key,0))",db.params("key",a.spaceId()+":"+a.actorType()+":"+a.actorId()));
   var p=db.scoped(a,"key",r.eventKey(),"run",r.runId(),"version",r.versionId());String hash=LedgerService.hash(db.stringify(r));
   var old=db.list("select * from exp_agent_feedback where space_id=:space and actor_type=:actorType and actor_id=:actor and event_key=:key",p);
   if(!old.isEmpty()){if(!hash.equals(old.getFirst().get("payload_hash")))throw LedgerException.conflict("EVENT_KEY_REUSED_WITH_DIFFERENT_PAYLOAD");return old.getFirst();}
   var run=context.ownedRun(a,r.runId());boolean supplied=false;
   for(var item:((JsonNode)run.get("response_json")).path("selected"))for(var id:item.path("versionIds"))if(id.asText().equals(r.versionId().toString()))supplied=true;
   if(!supplied)throw LedgerException.invalid("Version was not supplied in this run");
   var previous=db.list("select * from exp_agent_feedback where space_id=:space and actor_type=:actorType and actor_id=:actor and run_id=:run and version_id=:version order by created_at,id limit 1",p);
   UUID usage;if(previous.isEmpty()){var u=ledger.usage(a,new UsageInput(r.versionId(),db.tree(Map.of("runId",r.runId())),null,null,true,r.adopted(),null,null));usage=(UUID)u.get("id");}
   else{if(!Objects.equals(previous.getFirst().get("adopted"),r.adopted()))throw LedgerException.conflict("ADOPTION_ALREADY_RECORDED");usage=(UUID)previous.getFirst().get("usage_id");}
   if(r.outcomeType()!=null)ledger.outcome(a,usage,new OutcomeInput(r.outcomeType(),null,r.evaluation(),Instant.now(),null));
   p.putAll(db.params("id",UUID.randomUUID(),"usage",usage,"hash",hash,"adopted",r.adopted(),"outcome",r.outcomeType(),"evaluation",r.evaluation(),"input",r.actualInputTokens(),"output",r.actualOutputTokens(),"cost",r.reportedCost(),"currency",r.currency()==null?"":r.currency()));
   db.update("insert into exp_agent_feedback(id,space_id,run_id,version_id,usage_id,actor_type,actor_id,event_key,payload_hash,adopted,outcome_type,evaluation,actual_input_tokens,actual_output_tokens,reported_cost,currency) values(:id,:space,:run,:version,:usage,:actorType,:actor,:key,:hash,:adopted,:outcome,:evaluation,:input,:output,:cost,:currency)",p);
   return db.one("select * from exp_agent_feedback where space_id=:space and id=:id",p);
  });
 }
 public Object review(ActorContext a,UUID feedbackId,Review r){a.requireGovernance();if(!Set.of("ACCEPTED","REJECTED").contains(r.verdict()))throw LedgerException.invalid("Unknown review verdict");return db.with(a,r.reason(),()->{
  db.one("select id from exp_agent_feedback where space_id=:space and id=:id",db.scoped(a,"id",feedbackId));var p=db.scoped(a,"id",UUID.randomUUID(),"feedback",feedbackId,"verdict",r.verdict(),"reason",r.reason());
  db.update("insert into exp_feedback_review(id,space_id,feedback_id,verdict,reason,actor_id) values(:id,:space,:feedback,:verdict,:reason,:actor)",p);return db.one("select * from exp_feedback_review where space_id=:space and id=:id",p);
 });}
 public Object operations(ActorContext a){a.requireGovernance();return db.snapshot(a,null,()->{
  var p=db.scoped(a);return Map.of("windowDays",30,"agents",db.list("select actor_type,actor_id,count(*) as runs,sum(context_units) as context_units,sum(baseline_units) as baseline_units,round(avg(latency_ms)) as avg_latency_ms,count(*) filter(where response_json->>'status'='ESCALATE_HUMAN') as escalations from exp_context_run where space_id=:space and created_at>clock_timestamp()-interval '30 days' group by actor_type,actor_id order by runs desc",p),
  "costs",db.list("select actor_type,actor_id,currency,sum(actual_input_tokens) as input_tokens,sum(actual_output_tokens) as output_tokens,sum(reported_cost) as reported_cost from exp_agent_feedback where space_id=:space and created_at>clock_timestamp()-interval '30 days' group by actor_type,actor_id,currency",p),
  "usage",db.list("select actor_type,actor_id,count(distinct usage_id) as usage_count,count(distinct usage_id) filter(where adopted) as adopted_count,count(outcome_type) as outcome_event_count from exp_agent_feedback where space_id=:space and created_at>clock_timestamp()-interval '30 days' group by actor_type,actor_id",p),
  "feedback",db.list("select f.*,r.verdict from exp_agent_feedback f left join lateral (select verdict from exp_feedback_review r where r.space_id=f.space_id and r.feedback_id=f.id order by created_at desc,id desc limit 1) r on true where f.space_id=:space order by f.created_at desc,f.id limit 100",p),
  "runs",db.list("select id,actor_type,actor_id,context_units,baseline_units,latency_ms,created_at,response_json->>'status' as status from exp_context_run where space_id=:space order by created_at desc,id limit 100",p));
 });}
}
