package com.example.ledger.authoring;

import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LocalDraftAssistProvider implements DraftAssistProvider {
 private final ObjectMapper json;
 public LocalDraftAssistProvider(ObjectMapper json){this.json=json;}
 @Override public Result generate(GenerateRequest r){
  ObjectNode d=json.createObjectNode();String content=r.rawContent().trim();String title=Arrays.stream(content.split("\\R")).filter(x->!x.isBlank()).findFirst().orElse("待审核经验");
  d.put("title",cut(title,160));d.put("summary",cut(content,600));d.put("problem","");d.put("context",cut(content,4000));d.put("rootCause","");d.put("decision","");d.set("actions",json.createArrayNode());d.put("outcome","");d.put("lesson","原始记录已保留，关键判断规则需要人工确认。仍建议配置 LLM 生成完整草稿。");d.put("reusablePrinciple","复用前确认当前情境与原始约束一致。");
  for(String f:List.of("applicability","boundaryConditions","constraints","alternatives","tradeoffs","claims","evidenceMappings","possibleCounterExamples","tags"))d.set(f,json.createArrayNode());
  ArrayNode missing=d.putArray("missingInformation");missing.addObject().put("field","draft").put("message","当前使用 local-safe provider，仅生成保守草稿；请配置 OpenAI-compatible 或 Ollama Provider 完成语义提炼。");
  d.put("domain",r.taskContext().path("domain").asText("general"));d.put("taskType",r.taskContext().path("taskType").asText("experience_curation"));
  ObjectNode confidence=d.putObject("confidence");for(String f:List.of("overall","problem","rootCause","decision","outcome","principle"))confidence.put(f,.2);
  return new Result(d,"local-safe","deterministic-v1",null,null,0d,"USD");
 }
 @Override public Result revise(RevisionRequest r){
  ObjectNode d=(ObjectNode)r.currentDraft().deepCopy();ArrayNode missing=d.withArray("missingInformation");missing.addObject().put("field","revision").put("message","local-safe provider 无法解释自然语言修订："+cut(r.instruction(),400));
  return new Result(d,"local-safe","deterministic-v1",null,null,0d,"USD");
 }
 private String cut(String s,int max){return s.length()<=max?s:s.substring(0,max);}
}
