package com.example.ledger.judgment;

import com.example.ledger.api.Requests.Draft;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.judgment.JudgmentRequests.*;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class JudgmentStore {
 private final Db db;private final Validator validator;
 public JudgmentStore(Db db,Validator validator){this.db=db;this.validator=validator;}
 public Save input(JsonNode extracted) {return extracted.has("judgmentInput")?db.json.convertValue(extracted.get("judgmentInput"),Save.class):null;}
 public String retrievalText(JsonNode extracted){Save s=input(extracted);return s==null?"":JudgmentRules.text(s.rule());}
 public void check(JsonNode extracted,Draft draft,String creator) {
  Save s=input(extracted);if(s==null)return;
  if(!"HUMAN".equals(creator)||!validator.validate(s).isEmpty())throw LedgerException.invalid("Valid human judgment input required");
  if(!"KEEP_JUDGMENT".equals(JudgmentRules.gate(s.gate()).get("recommendation")))throw LedgerException.invalid("This content does not pass the retention gate");
  JudgmentRules.requireMatching(draft,s.rule());
 }
 /** Called within the same publication transaction as immutable V1 claims. */
 public void seal(ActorContext a,Map<String,Object> candidate,UUID family,UUID version) {
  String track="HUMAN".equals(candidate.get("created_by_type"))?"HUMAN":"AGENT";
  var existing=db.list("select native_track from exp_experience_track where space_id=:space and family_id=:family",db.scoped(a,"family",family));
  if(existing.isEmpty())db.update("insert into exp_experience_track(id,space_id,family_id,native_track,provenance) values(:id,:space,:family,:track,'CAPTURE_IDENTITY')",db.scoped(a,"id",UUID.randomUUID(),"family",family,"track",track));
  else if(!track.equals(existing.getFirst().get("native_track")))throw LedgerException.conflict("NATIVE_TRACK_IMMUTABLE");
  Save s=input((JsonNode)candidate.get("extracted_json"));
  if(s!=null){
   var f=db.one("select domain,experience_type from exp_experience_family where space_id=:space and id=:id",db.scoped(a,"id",family));
   if(!s.domain().equals(f.get("domain"))||!(s.rule().negative()?"WARNING":"DECISION").equals(f.get("experience_type")))throw LedgerException.invalid("Judgment domain and polarity must match its immutable family");
   db.update("insert into exp_judgment_rule(id,space_id,experience_version_id,rule_json) values(:id,:space,:version,cast(:rule as jsonb))",db.scoped(a,"id",UUID.randomUUID(),"version",version,"rule",db.stringify(s.rule())));
  }
 }
 public Map<String,Object> sharing(ActorContext a,UUID version) {
  var row=db.one("select t.native_track,t.provenance from exp_experience_track t join exp_experience_version v on v.space_id=t.space_id and v.family_id=t.family_id where v.space_id=:space and v.id=:id",db.scoped(a,"id",version));
  var events=db.list("""
   select e.*,not exists(select 1 from exp_reuse_evidence re join exp_evidence ev on ev.space_id=re.space_id and ev.id=re.evidence_id
    where re.space_id=e.space_id and re.reuse_event_id=e.id and ev.status<>'ACTIVE') as evidence_active
   from exp_reuse_event e where e.space_id=:space and e.version_id=:id order by e.created_at desc,e.id desc limit 1
   """,db.scoped(a,"id",version));
  if(!events.isEmpty())events.getFirst().put("validation_evidence",db.list("select e.id,e.status,e.content_hash,e.reliability,'VALIDATES_REUSE' as support_type from exp_reuse_evidence r join exp_evidence e on e.space_id=r.space_id and e.id=r.evidence_id where r.space_id=:space and r.reuse_event_id=:event order by e.id",db.scoped(a,"event",events.getFirst().get("id"))));
  row.put("grant",events.isEmpty()?null:events.getFirst());return row;
 }
 public void attach(ActorContext a,Map<String,Object> source) {
  UUID id=(UUID)source.get("id");source.put("sharing",sharing(a,id));
  var rows=db.list("select rule_json from exp_judgment_rule where space_id=:space and experience_version_id=:id",db.scoped(a,"id",id));
  source.put("judgmentRule",rows.isEmpty()?null:rows.getFirst().get("rule_json"));
 }
 @SuppressWarnings("unchecked") public static String mode(Map<String,Object> source,String audience) {
  var sharing=(Map<String,Object>)source.get("sharing");
  if(sharing==null)return "NATIVE_ONLY";
  if(audience.equals(sharing.get("native_track")))return "NATIVE";
  var grant=(Map<String,Object>)sharing.get("grant");
  if(grant==null||!audience.equals(grant.get("target_track"))||!Boolean.TRUE.equals(grant.get("evidence_active")))return "NATIVE_ONLY";
  return grant.get("reuse_mode").toString();
 }
 @SuppressWarnings("unchecked") public static List<Map<String,Object>> validationEvidence(Map<String,Object> source){
  var sharing=(Map<String,Object>)source.get("sharing");var grant=sharing==null?null:(Map<String,Object>)sharing.get("grant");
  return grant==null?List.of():(List<Map<String,Object>>)grant.getOrDefault("validation_evidence",List.of());
 }
 public static boolean allowed(Map<String,Object> source,String audience){return !"NATIVE_ONLY".equals(mode(source,audience));}
 public String ruleText(Map<String,Object> source) {
  return source.get("judgmentRule")==null?"":JudgmentRules.text(db.json.convertValue(source.get("judgmentRule"),Rule.class));
 }
}
