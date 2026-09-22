package com.example.ledger.authoring;

import com.example.ledger.domain.LedgerException;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class DraftSchema {
 public static final List<String> TEXT_FIELDS=List.of("title","summary","problem","context","rootCause","decision","outcome","lesson","reusablePrinciple","domain","taskType");
 public static final List<String> ARRAY_FIELDS=List.of("actions","applicability","boundaryConditions","constraints","alternatives","tradeoffs","claims","evidenceMappings","missingInformation","possibleCounterExamples","tags");
 private final ObjectMapper json;
 public DraftSchema(ObjectMapper json){this.json=json;}

 public ObjectNode normalize(JsonNode source,String rawContent,Set<String> availableEvidence){
  if(source==null||!source.isObject())throw LedgerException.invalid("LLM draft must be a JSON object");
  ObjectNode out=json.createObjectNode();
  for(String field:TEXT_FIELDS)out.put(field,text(source.get(field)));
  if(out.path("title").asText().isBlank())out.put("title",firstLine(rawContent));
  if(out.path("summary").asText().isBlank())out.put("summary",truncate(rawContent,600));
  if(out.path("problem").asText().isBlank()&&out.path("context").asText().isBlank())out.put("context",truncate(rawContent,4000));
  for(String field:List.of("actions","applicability","boundaryConditions","constraints","alternatives","tradeoffs","possibleCounterExamples","tags"))out.set(field,stringArray(source.get(field)));
  out.set("missingInformation",missing(source.get("missingInformation")));
  out.set("claims",claims(source.get("claims")));
  out.set("evidenceMappings",evidenceMappings(source.get("evidenceMappings"),availableEvidence,out.path("claims").size()));
  out.set("confidence",confidence(source.get("confidence")));
  List<String> errors=validate(out);
  if(!errors.isEmpty())throw new LedgerException("DRAFT_SCHEMA_INVALID",String.join("; ",errors),422);
  return out;
 }

 public List<String> validate(JsonNode draft){
  List<String> errors=new ArrayList<>();
  if(draft==null||!draft.isObject())return List.of("Draft 必须是 JSON 对象");
  for(String f:List.of("title","summary"))if(draft.path(f).asText().isBlank())errors.add(f+" 不能为空");
  if(draft.path("problem").asText().isBlank()&&draft.path("context").asText().isBlank())errors.add("problem/context 至少填写一个");
  if(draft.path("lesson").asText().isBlank()&&draft.path("decision").asText().isBlank()&&draft.path("reusablePrinciple").asText().isBlank())errors.add("lesson/decision/reusablePrinciple 至少填写一个");
  for(String f:ARRAY_FIELDS)if(!draft.path(f).isArray())errors.add(f+" 必须是数组");
  JsonNode overall=draft.path("confidence").path("overall");
  if(!overall.isNumber()||overall.asDouble()<0||overall.asDouble()>1)errors.add("confidence.overall 必须在 0..1");
  return errors;
 }

 public ArrayNode diff(JsonNode before,JsonNode after){
  ArrayNode result=json.createArrayNode();
  LinkedHashSet<String> fields=new LinkedHashSet<>();fields.addAll(before.propertyNames());fields.addAll(after.propertyNames());
  for(String field:fields)if(!Objects.equals(before.get(field),after.get(field))){ObjectNode d=result.addObject();d.put("field",field);d.set("before",before.has(field)?before.get(field):json.nullNode());d.set("after",after.has(field)?after.get(field):json.nullNode());}
  return result;
 }

 public String hash(Object value){try{byte[] bytes=json.writeValueAsBytes(value);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 public String compact(JsonNode d){return truncate(join(d.path("title").asText(),d.path("reusablePrinciple").asText(),first(d.path("boundaryConditions"))),700);}
 public String summary(JsonNode d){return truncate("问题："+firstNonBlank(d.path("problem").asText(),d.path("context").asText())+"\n判断："+firstNonBlank(d.path("decision").asText(),d.path("reusablePrinciple").asText(),d.path("lesson").asText())+"\n结果："+d.path("outcome").asText()+"\n边界："+String.join("；",strings(d.path("boundaryConditions"))),1800);}

 private ArrayNode claims(JsonNode n){ArrayNode a=json.createArrayNode();if(n!=null&&n.isArray())for(JsonNode x:n){String content=text(x.get("content"));if(content.isBlank())continue;ObjectNode c=a.addObject();String kind=upper(text(x.get("kind")));if(!Set.of("OBSERVED","DERIVED").contains(kind))kind="DERIVED";c.put("kind",kind);String type=upper(firstNonBlank(text(x.get("claimType")),kind.equals("OBSERVED")?"OBSERVATION":"RECOMMENDATION"));if(!Set.of("OBSERVATION","RULE","LESSON","RECOMMENDATION","CONSTRAINT","CAUSAL_HYPOTHESIS","WARNING").contains(type))type=kind.equals("OBSERVED")?"OBSERVATION":"RECOMMENDATION";c.put("claimType",type);c.put("content",truncate(content,10000));c.put("originType","AGENT_DERIVED");c.put("derivationMethod",firstNonBlank(text(x.get("derivationMethod")),"LLM extraction from immutable candidate input"));c.put("confidence",bounded(x.path("confidence").asDouble(.5)));}return a;}
 private ArrayNode evidenceMappings(JsonNode n,Set<String> allowed,int claims){ArrayNode a=json.createArrayNode();if(n!=null&&n.isArray())for(JsonNode x:n){int i=x.path("claimIndex").asInt(-1);String ref=text(x.get("evidenceRef"));String relation=upper(text(x.get("relation")));if(i<0||i>=claims||!allowed.contains(ref))continue;if(!Set.of("SUPPORTS","CONTRADICTS","CONTEXT").contains(relation))relation="CONTEXT";ObjectNode m=a.addObject();m.put("claimIndex",i);m.put("evidenceRef",ref);m.put("relation",relation);}return a;}
 private ArrayNode missing(JsonNode n){ArrayNode a=json.createArrayNode();if(n!=null&&n.isArray())for(JsonNode x:n){if(x.isTextual()){a.addObject().put("field","unknown").put("message",truncate(x.asText(),1000));}else if(x.isObject()){String message=text(x.get("message"));if(!message.isBlank())a.addObject().put("field",firstNonBlank(text(x.get("field")),"unknown")).put("message",truncate(message,1000));}}return a;}
 private ObjectNode confidence(JsonNode n){ObjectNode o=json.createObjectNode();for(String f:List.of("overall","problem","rootCause","decision","outcome","principle"))o.put(f,bounded(n==null?.5:n.path(f).asDouble(.5)));return o;}
 private ArrayNode stringArray(JsonNode n){ArrayNode a=json.createArrayNode();if(n!=null&&n.isArray())for(JsonNode x:n){String v=x.isTextual()?x.asText():x.isValueNode()?x.asText():x.toString();if(!v.isBlank())a.add(truncate(v,2000));}return a;}
 private List<String> strings(JsonNode n){List<String> r=new ArrayList<>();if(n!=null&&n.isArray())n.forEach(x->r.add(x.asText()));return r;}
 private String first(JsonNode n){return n!=null&&n.isArray()&&n.size()>0?n.get(0).asText():"";}
 private String text(JsonNode n){return n==null||n.isNull()?"":n.asText("").trim();}
 private String upper(String s){return s.toUpperCase(Locale.ROOT).replace('-','_');}
 private double bounded(double n){return Math.max(0,Math.min(1,n));}
 private String firstLine(String s){for(String line:s.split("\\R"))if(!line.isBlank())return truncate(line.trim(),160);return "待审核经验";}
 private String join(String... values){return String.join(" · ",Arrays.stream(values).filter(x->x!=null&&!x.isBlank()).toList());}
 private String firstNonBlank(String... values){return Arrays.stream(values).filter(x->x!=null&&!x.isBlank()).findFirst().orElse("");}
 private String truncate(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max);}
}
