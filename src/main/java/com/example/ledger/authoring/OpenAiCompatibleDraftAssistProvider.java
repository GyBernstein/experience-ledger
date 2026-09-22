package com.example.ledger.authoring;

import com.example.ledger.domain.LedgerException;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class OpenAiCompatibleDraftAssistProvider implements DraftAssistProvider {
 private final ObjectMapper json;private final HttpClient http;private final String url,key,model,provider;private final double temperature;private final int attempts,readSeconds;
 public OpenAiCompatibleDraftAssistProvider(ObjectMapper json,
  @Value("${ledger.ai.url:}") String url,@Value("${ledger.ai.api-key:}") String key,
  @Value("${ledger.ai.model:}") String model,@Value("${ledger.ai.provider:openai-compatible}") String provider,
  @Value("${ledger.ai.temperature:0.2}") double temperature,@Value("${ledger.ai.retry.max-attempts:2}") int attempts,
  @Value("${ledger.ai.timeout.connect-seconds:5}") int connectSeconds,@Value("${ledger.ai.timeout.read-seconds:120}") int readSeconds){this.json=json;this.url=url;this.key=key;this.model=model;this.provider=provider;this.temperature=temperature;this.attempts=Math.clamp(attempts,1,3);this.readSeconds=Math.clamp(readSeconds,10,600);this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectSeconds)).build();}

 @Override public Result generate(GenerateRequest r){return invoke(r.prompt(),payload(r),"generate");}
 @Override public Result revise(RevisionRequest r){ObjectNode input=payload(r);input.set("currentDraft",r.currentDraft());input.put("revisionInstruction",r.instruction());return invoke(r.prompt(),input,"revise");}
 private ObjectNode payload(GenerateRequest r){ObjectNode input=json.createObjectNode();input.put("sourceType",r.sourceType());input.put("rawContent",r.rawContent());input.set("taskContext",r.taskContext());input.set("availableEvidence",json.valueToTree(r.availableEvidence()));input.put("language",r.language());return input;}
 private ObjectNode payload(RevisionRequest r){ObjectNode input=json.createObjectNode();input.put("rawContent",r.rawContent());input.set("taskContext",r.taskContext());input.set("availableEvidence",json.valueToTree(r.availableEvidence()));input.put("language",r.language());return input;}
 private Result invoke(Prompt prompt,ObjectNode input,String operation){
  if(url.isBlank()||model.isBlank())throw new LedgerException("LLM_NOT_CONFIGURED","ledger.ai.url and ledger.ai.model are required",503);
  Exception last=null;String repair="";
  for(int i=0;i<attempts;i++)try{
   ObjectNode body=json.createObjectNode();body.put("model",model);body.put("temperature",temperature);body.putObject("response_format").put("type","json_object");ArrayNode messages=body.putArray("messages");messages.addObject().put("role","system").put("content",prompt.systemPrompt());
   String request=prompt.userTemplate()+"\nJSON Schema:\n"+json.writeValueAsString(prompt.outputSchema())+"\nInput:\n"+json.writeValueAsString(input)+repair;
   messages.addObject().put("role","user").put("content",request);
   var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(readSeconds)).header("Content-Type","application/json");if(!key.isBlank())builder.header("Authorization","Bearer "+key);
   var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()<200||response.statusCode()>=300)throw new IllegalStateException("LLM HTTP "+response.statusCode()+": "+cut(response.body(),500));
   JsonNode envelope=json.readTree(response.body());String content=extract(envelope);JsonNode parsed=json.readTree(stripFences(content));if(!parsed.isObject())throw new IllegalStateException("LLM response is not a JSON object");
   JsonNode usage=envelope.path("usage");return new Result(parsed,provider,model,number(usage,"prompt_tokens","input_tokens"),number(usage,"completion_tokens","output_tokens"),null,"USD");
  }catch(Exception e){last=e;repair="\nThe prior response was invalid. Return exactly one valid JSON object and no markdown.";}
  throw new LedgerException("LLM_INVOCATION_FAILED",operation+" failed: "+cut(Objects.toString(last==null?"unknown":last.getMessage()),700),503);
 }
 private String extract(JsonNode n){JsonNode c=n.path("choices").path(0).path("message").path("content");if(c.isTextual())return c.asText();if(c.isArray()){StringBuilder s=new StringBuilder();c.forEach(x->s.append(x.path("text").asText()));return s.toString();}if(n.path("output_text").isTextual())return n.path("output_text").asText();throw new IllegalStateException("No assistant content in LLM response");}
 private Long number(JsonNode n,String... names){for(String name:names)if(n.path(name).isNumber())return n.path(name).asLong();return null;}
 private String stripFences(String s){String v=s.trim();if(v.startsWith("```")){int first=v.indexOf('\n'),last=v.lastIndexOf("```");if(first>=0&&last>first)v=v.substring(first+1,last).trim();}return v;}
 private String cut(String s,int n){return s.length()<=n?s:s.substring(0,n);}
}
