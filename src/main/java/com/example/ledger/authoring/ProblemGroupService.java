package com.example.ledger.authoring;

import com.example.ledger.domain.ActorContext;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.infrastructure.Db;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** Navigation between independent experience families describing the same problem. */
@Service
public class ProblemGroupService {
 private final Db db;
 public ProblemGroupService(Db db){this.db=db;}

 /** An older database may temporarily serve authoring while the V7 migration is pending. */
 public boolean available(ActorContext a){return db.with(a,null,()->Boolean.TRUE.equals(db.one("select to_regclass('exp_problem_group') is not null and to_regclass('exp_problem_group_event') is not null as ready",Map.of()).get("ready")));}

 public List<Map<String,Object>> suggest(ActorContext a,JsonNode draft){return db.with(a,null,()->{
  String domain=draft.path("domain").asText("general");if(domain.isBlank())domain="general";
  String problem=draft.path("problem").asText().trim();
  String title=draft.path("title").asText().trim();
  if(problem.isBlank()&&title.isBlank())return List.of();
  var rows=db.list("""
   with last_link as (
    select distinct on (family_id) family_id,group_id,action from exp_problem_group_event
    where space_id=:space order by family_id,created_at desc,id desc
   )
   select f.id family_id,v.id version_id,v.title,v.problem,v.summary,
    v.constraints_json->>'rootCause' root_cause,v.applicability_json applicability,
    case when l.action='LINK' then l.group_id else null end group_id,
    case when g.id is not null then g.title else null end group_title,
    ts_rank_cd(v.search_vector,plainto_tsquery('simple',ledger_fts_text(:problem)),32) problem_rank,
    ts_rank_cd(v.search_vector,plainto_tsquery('simple',ledger_fts_text(:title)),32) title_rank
   from exp_experience_version v join exp_experience_family f on f.space_id=v.space_id and f.id=v.family_id
   left join last_link l on l.family_id=f.id and l.action='LINK'
   left join exp_problem_group g on g.space_id=f.space_id and g.id=l.group_id
   where v.space_id=:space and f.domain=:domain and v.status='VERIFIED'
    and v.valid_from<=clock_timestamp() and (v.valid_to is null or clock_timestamp()<v.valid_to)
    and ((:problem<>'' and (v.search_vector @@ plainto_tsquery('simple',ledger_fts_text(:problem))
       or lower(v.problem)=lower(:problem)))
     or (:title<>'' and v.search_vector @@ plainto_tsquery('simple',ledger_fts_text(:title))))
   order by problem_rank desc,title_rank desc,v.recorded_at desc,v.id limit 5
   """,db.scoped(a,"domain",domain,"problem",problem,"title",title));
  for(var row:rows){String existing=Objects.toString(row.get("root_cause"),"").trim();String incoming=draft.path("rootCause").asText().trim();
   row.put("relationHint",existing.isBlank()||incoming.isBlank()?"CHECK_CAUSE":existing.equalsIgnoreCase(incoming)?"SAME_CAUSE":"POSSIBLE_ALTERNATIVE_CAUSE");
   row.put("matchReason",problem.equalsIgnoreCase(Objects.toString(row.get("problem"),""))?"问题描述一致；请核对适用条件和根因":"症状或标题相近；请核对是否同一问题");}
  return rows;
 });}

 public void validateReference(ActorContext a,UUID family,String domain,String relation){
  if(!Set.of("ALTERNATIVE_CAUSE","SAME_CAUSE_CASE").contains(relation))throw LedgerException.invalid("Invalid problem group relation");
  db.with(a,null,()->{var target=db.one("""
   select f.domain from exp_experience_family f join exp_experience_version v on v.space_id=f.space_id and v.family_id=f.id
   where f.space_id=:space and f.id=:id and v.status='VERIFIED' and v.valid_from<=clock_timestamp()
    and (v.valid_to is null or clock_timestamp()<v.valid_to) limit 1
   """,db.scoped(a,"id",family));
   if(!domain.equals(target.get("domain")))throw LedgerException.invalid("Problem group domain must match both experiences");return null;});
 }

 /** referenceFamilyId can be an existing member or a standalone verified experience. */
 public Map<String,Object> attachToReference(ActorContext a,UUID newVersionId,UUID referenceFamilyId,String relation,String reason){a.requireGovernance();return db.with(a,reason,()->{
  var newcomer=db.one("select v.id,v.family_id,v.problem,v.title,f.domain from exp_experience_version v join exp_experience_family f on f.space_id=v.space_id and f.id=v.family_id where v.space_id=:space and v.id=:id and v.status='VERIFIED'",db.scoped(a,"id",newVersionId));
  UUID newcomerFamily=(UUID)newcomer.get("family_id");if(newcomerFamily.equals(referenceFamilyId))throw LedgerException.invalid("Cannot group a family with itself");
  // Lock both families in a stable order, preventing duplicate groups under concurrent reviews.
  for(UUID family:List.of(newcomerFamily,referenceFamilyId).stream().sorted().toList())
   db.one("select id from exp_experience_family where space_id=:space and id=:id for update",db.scoped(a,"id",family));
  var target=db.one("""
   select v.id,v.family_id,v.problem,v.title,f.domain from exp_experience_version v
   join exp_experience_family f on f.space_id=v.space_id and f.id=v.family_id
   where v.space_id=:space and f.id=:id and v.status='VERIFIED'
   order by v.version_no desc limit 1
   """,db.scoped(a,"id",referenceFamilyId));
  if(!newcomer.get("domain").equals(target.get("domain")))throw LedgerException.invalid("Problem group domain must match both experiences");
  UUID group=activeGroup(a,referenceFamilyId);
  if(group==null){
   group=UUID.randomUUID();String problem=Objects.toString(target.get("problem"),"").trim();String title=Objects.toString(target.get("title"),"").trim();
   db.update("insert into exp_problem_group(id,space_id,domain,title,problem,created_by_type,created_by) values(:id,:space,:domain,:title,:problem,:actorType,:actor)",db.scoped(a,"id",group,"domain",target.get("domain"),"title",title.isBlank()?problem:title,"problem",problem.isBlank()?title:problem));
   event(a,group,referenceFamilyId,(UUID)target.get("id"),"LINK","ROOT_CASE",reason);
  }
  link(a,group,newcomerFamily,newVersionId,relation,reason);
  return detailInTx(a,group);
 });}

 public Map<String,Object> linkExisting(ActorContext a,UUID group,UUID family,String relation,String reason){a.requireGovernance();return db.with(a,reason,()->{
  db.one("select id from exp_experience_family where space_id=:space and id=:id for update",db.scoped(a,"id",family));
  var version=db.one("select id from exp_experience_version where space_id=:space and family_id=:id and status='VERIFIED' order by version_no desc limit 1",db.scoped(a,"id",family));
  db.one("select id from exp_problem_group where space_id=:space and id=:id",db.scoped(a,"id",group));
  link(a,group,family,(UUID)version.get("id"),relation,reason);return detailInTx(a,group);
 });}
 public Map<String,Object> attachFamily(ActorContext a,UUID family,UUID referenceFamily,String relation,String reason){a.requireGovernance();UUID version=db.with(a,null,()->(UUID)db.one("select id from exp_experience_version where space_id=:space and family_id=:id and status='VERIFIED' order by version_no desc limit 1",db.scoped(a,"id",family)).get("id"));return attachToReference(a,version,referenceFamily,relation,reason);}
 public Map<String,Object> unlink(ActorContext a,UUID group,UUID family,String reason){a.requireGovernance();return db.with(a,reason,()->{
  db.one("select id from exp_experience_family where space_id=:space and id=:id for update",db.scoped(a,"id",family));
  if(!group.equals(activeGroup(a,family)))throw LedgerException.conflict("PROBLEM_GROUP_CHANGED");
  var version=db.one("select id from exp_experience_version where space_id=:space and family_id=:id order by version_no desc limit 1",db.scoped(a,"id",family));
  event(a,group,family,(UUID)version.get("id"),"UNLINK","ROOT_CASE",reason);return detailInTx(a,group);
 });}
 public Map<String,Object> detail(ActorContext a,UUID group){return db.with(a,null,()->detailInTx(a,group));}
 public Map<String,Object> byFamily(ActorContext a,UUID family){return db.with(a,null,()->{
  db.one("select id from exp_experience_family where space_id=:space and id=:id",db.scoped(a,"id",family));
  UUID group=activeGroup(a,family);return group==null?Map.of():detailInTx(a,group);
 });}
 public List<Map<String,Object>> suggestForFamily(ActorContext a,UUID family){a.requireGovernance();var current=db.with(a,null,()->db.one("""
  select f.domain,v.title,v.problem,v.constraints_json->>'rootCause' root_cause from exp_experience_family f
  join exp_experience_version v on v.space_id=f.space_id and v.family_id=f.id
  where f.space_id=:space and f.id=:id and v.status='VERIFIED' order by v.version_no desc limit 1
  """,db.scoped(a,"id",family)));
  var doc=db.json.createObjectNode();doc.put("domain",(String)current.get("domain"));doc.put("title",(String)current.get("title"));doc.put("problem",(String)current.get("problem"));doc.put("rootCause",Objects.toString(current.get("root_cause"),""));
  return suggest(a,doc).stream().filter(row->!family.equals(row.get("family_id"))).toList();
 }

 private UUID activeGroup(ActorContext a,UUID family){var rows=db.list("select group_id,action from exp_problem_group_event where space_id=:space and family_id=:id order by created_at desc,id desc limit 1",db.scoped(a,"id",family));return rows.isEmpty()||!"LINK".equals(rows.getFirst().get("action"))?null:(UUID)rows.getFirst().get("group_id");}
 private void link(ActorContext a,UUID group,UUID family,UUID version,String relation,String reason){
  if(!Set.of("ALTERNATIVE_CAUSE","SAME_CAUSE_CASE").contains(relation))throw LedgerException.invalid("Invalid problem group relation");
  UUID previous=activeGroup(a,family);if(group.equals(previous))return;
  if(previous!=null)throw LedgerException.conflict("PROBLEM_GROUP_CHANGED");
  event(a,group,family,version,"LINK",relation,reason);
 }
 private void event(ActorContext a,UUID group,UUID family,UUID version,String action,String relation,String reason){
  if(reason==null||reason.isBlank())throw LedgerException.invalid("Reason is required for grouping");
  db.update("insert into exp_problem_group_event(id,space_id,group_id,family_id,linked_version_id,action,relation,reason,created_by_type,created_by) values(:id,:space,:group,:family,:version,:action,:relation,:reason,:actorType,:actor)",db.scoped(a,"id",UUID.randomUUID(),"group",group,"family",family,"version",version,"action",action,"relation",relation,"reason",reason));
 }
 private Map<String,Object> detailInTx(ActorContext a,UUID group){var result=new LinkedHashMap<>(db.one("select * from exp_problem_group where space_id=:space and id=:id",db.scoped(a,"id",group)));
  result.put("members",db.list("""
   with current as (select distinct on (family_id) family_id,group_id,action,relation,reason from exp_problem_group_event
    where space_id=:space order by family_id,created_at desc,id desc)
   select e.family_id,e.relation,e.reason,v.id version_id,v.title,v.problem,v.summary,v.decision,v.action,
    v.outcome_summary,v.constraints_json->>'rootCause' root_cause,v.applicability_json,
    v.status,v.valid_from,v.valid_to,s.evidence_count
   from current e join exp_experience_version v on v.space_id=:space and v.family_id=e.family_id and v.status='VERIFIED'
   left join exp_version_stats s on s.space_id=v.space_id and s.version_id=v.id
   where e.group_id=:id and e.action='LINK' and v.valid_from<=clock_timestamp()
     and (v.valid_to is null or clock_timestamp()<v.valid_to)
   order by v.recorded_at desc,v.id
   """,db.scoped(a,"id",group)));
  return result;
 }
}
