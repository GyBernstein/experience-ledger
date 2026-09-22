package com.example.ledger;

import com.example.ledger.api.Requests.*;
import com.example.ledger.application.*;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.provider.*;
import com.example.ledger.retrieval.RetrievalService;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LedgerIT.Providers.class)
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
class LedgerIT {
 static PostgreSQLContainer container;
 static final UUID SPACE=UUID.fromString("11111111-1111-1111-1111-111111111111");
 static final String HUMAN_TOKEN="test-human-token-0123456789",AGENT_TOKEN="test-agent-token-0123456789";
 static final ActorContext HUMAN=new ActorContext(ActorContext.ActorType.HUMAN,"reviewer",SPACE);
 static final boolean WASM="true".equals(System.getenv("LEDGER_IT_WASM"));
 @DynamicPropertySource static void configure(DynamicPropertyRegistry r)throws Exception{
  String url=System.getenv("LEDGER_IT_URL"),admin=System.getenv().getOrDefault("LEDGER_IT_ADMIN_USER","postgres"),password=System.getenv().getOrDefault("LEDGER_IT_ADMIN_PASSWORD","test-only");
  if(url==null){container=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16").asCompatibleSubstituteFor("postgres")).withDatabaseName("ledger_test").withUsername(admin).withPassword(password);container.start();url=container.getJdbcUrl();}
  try(var c=DriverManager.getConnection(url,admin,password);var s=c.createStatement()){
   s.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='ledger_it_app') THEN CREATE ROLE ledger_it_app LOGIN PASSWORD 'integration-only' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
   s.execute("GRANT USAGE ON SCHEMA public TO ledger_it_app");
   s.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE,TRUNCATE ON TABLES TO ledger_it_app");
  }
  final String jdbcUrl=url,adminUser=admin,adminPassword=password;
  r.add("spring.datasource.url",()->jdbcUrl);r.add("spring.datasource.username",()->WASM?adminUser:"ledger_it_app");r.add("spring.datasource.password",()->WASM?adminPassword:"integration-only");
  r.add("spring.flyway.url",()->jdbcUrl);r.add("spring.flyway.user",()->adminUser);r.add("spring.flyway.password",()->adminPassword);
  r.add("spring.datasource.hikari.maximum-pool-size",()->WASM?1:8);
  if(WASM){r.add("spring.datasource.hikari.connection-init-sql",()->"SET ROLE ledger_it_app");r.add("spring.flyway.postgresql.transactional-lock",()->false);}
  r.add("ledger.worker.enabled",()->false);
  r.add("ledger.security.principals",()->"[{\"token\":\""+HUMAN_TOKEN+"\",\"actorType\":\"HUMAN\",\"actorId\":\"reviewer\",\"spaceId\":\""+SPACE+"\"},{\"token\":\""+AGENT_TOKEN+"\",\"actorType\":\"AGENT\",\"actorId\":\"coding-agent\",\"spaceId\":\""+SPACE+"\"}]");
 }
 @TestConfiguration static class Providers {
  @Bean @Primary ToggleEmbedding embedding(){return new ToggleEmbedding();}
  @Bean @Primary ToggleEnrichment enrichment(){return new ToggleEnrichment();}
 }
 static class ToggleEmbedding implements EmbeddingProvider {
  volatile String mode="disabled";
  public Result embed(String text){if(mode.equals("failed"))throw new IllegalStateException("offline");if(mode.equals("disabled"))return null;float[] v=new float[384];v[Math.floorMod(text.toLowerCase(Locale.ROOT).contains("prun")?1:text.hashCode(),384)]=1;return new Result(v,"test-only-v1");}
 }
 static class ToggleEnrichment implements ExperienceEnrichmentProvider {
  volatile boolean fail;
  public JsonNode enrich(Candidate c,CaptureContext context){if(fail)throw new IllegalStateException("offline");return c.extracted();}
 }
 @Autowired Db db;@Autowired LedgerService ledger;@Autowired RetrievalService retrieval;@Autowired ProcessingWorker worker;
 @Autowired ToggleEmbedding embedding;@Autowired ToggleEnrichment enrichment;@Autowired TestRestTemplate http;
 @BeforeEach void seedSpace(){embedding.mode="disabled";enrichment.fail=false;db.with(HUMAN,"test setup",()->{db.update("insert into exp_space(id,name) values(:space,'test') on conflict do nothing",db.scoped(HUMAN));return null;});}
 Capture captureRequest(){return new Capture("pruning candidates reduced utilization 剪枝",null,"TEST","test",UUID.randomUUID().toString(),"COMPLETED",null,null,null,null);}
 Map<String,Object> candidate(){return ledger.capture(HUMAN,captureRequest());}
 Draft draft(){return new Draft("pruning optimization 剪枝优化","Candidate generation matters","low yield","keep alternatives","remove premature pruning","improved","Generate candidates before global optimization",Instant.parse("2026-01-01T00:00:00Z"),null,db.tree(Map.of("lengthMin",700)),db.tree(Map.of("gpuAvailable",false)),List.of(new ClaimInput("LESSON","pruning can lose good combinations","HUMAN_ASSERTED",null,List.of())),List.of(new ContextInput("PROJECT","rod_matching",null)),List.of());}
 Map<String,Object> reviewed(Draft draft){var c=candidate();return ledger.review(HUMAN,(UUID)c.get("id"),new Review(revision(c),draft,"test review"));}
 Map<String,Object> publish(){var c=reviewed(draft());return ledger.verify(HUMAN,(UUID)c.get("id"),new Verify("CREATE_NEW_FAMILY",UUID.randomUUID().toString(),"optimization","OPTIMIZATION",null,null,revision(c),"test publish"));}
 int revision(Map<String,Object> c){return ((Number)c.get("revision")).intValue();}
 @SuppressWarnings("unchecked") List<Map<String,Object>> results(Map<String,Object> response){return (List<Map<String,Object>>)response.get("results");}
 Search query(String q){return new Search(q,null,null,null,null,db.tree(Map.of("length",800)),null,100);}
 @Test void migrationVectorAndRuntimeRole(){db.with(HUMAN,null,()->{
  assertFalse((Boolean)db.one("select rolsuper from pg_roles where rolname=current_user",Map.of()).get("rolsuper"));
  assertEquals(0.0,((Number)db.one("select '[1,0,0]'::vector <=> '[1,0,0]'::vector as distance",Map.of()).get("distance")).doubleValue());
  assertTrue((Boolean)db.one("select to_tsvector('simple',ledger_fts_text('候选剪枝优化')) @@ plainto_tsquery('simple','剪枝') as ok",Map.of()).get("ok"));
  assertEquals(4L,db.one("select count(*) as n from flyway_schema_history where success and type='SQL'",Map.of()).get("n"));return null;
 });}
 @Test void httpEndToEnd(){
  var headers=new HttpHeaders();headers.setBearerAuth(HUMAN_TOKEN);headers.setContentType(MediaType.APPLICATION_JSON);
  var response=http.postForEntity("/api/v1/candidates/capture",new HttpEntity<>(captureRequest(),headers),JsonNode.class);assertEquals(HttpStatus.OK,response.getStatusCode(),Objects.toString(response.getBody()));
  UUID cid=UUID.fromString(response.getBody().path("id").asText());
  var review=http.postForEntity("/api/v1/candidates/"+cid+"/review",new HttpEntity<>(new Review(response.getBody().path("revision").asInt(),draft(),"review"),headers),JsonNode.class);assertEquals(200,review.getStatusCode().value());
  var verify=http.postForEntity("/api/v1/candidates/"+cid+"/verify",new HttpEntity<>(new Verify("CREATE_NEW_FAMILY",UUID.randomUUID().toString(),"optimization","OPTIMIZATION",null,null,review.getBody().path("revision").asInt(),"accept"),headers),JsonNode.class);assertEquals(200,verify.getStatusCode().value());
  UUID v1=UUID.fromString(verify.getBody().path("id").asText()),family=UUID.fromString(verify.getBody().path("family_id").asText());
  var searched=http.postForEntity("/api/v1/experiences/search",new HttpEntity<>(query("pruning"),headers),JsonNode.class);assertEquals(200,searched.getStatusCode().value());assertTrue(searched.getBody().path("results").size()>0);
  var u=ledger.usage(HUMAN,new UsageInput(v1,null,0.8,1.0,true,true,null,new OutcomeInput("SUCCESS",db.tree(Map.of("selected",79)),"initial success",Instant.now(),null)));
  ledger.outcome(HUMAN,(UUID)u.get("id"),new OutcomeInput("FAILURE",null,"later issue",Instant.now(),null));
  assertEquals(2,ledger.outcomes(HUMAN,(UUID)u.get("id")).size());
  var got=retrieval.get(HUMAN,family,null,null);assertEquals(1L,((Map<?,?>)got.get("usageStats")).get("usage_count"));assertEquals(1L,((Map<?,?>)got.get("outcomeStats")).get("success"));
  var c2=reviewed(draft());var v2=ledger.supersede(HUMAN,family,new Supersede((UUID)c2.get("id"),v1,revision(c2),"evolution"));
  Instant recorded=Instant.parse((String)v2.get("recorded_at"));
  assertEquals(v1,retrieval.get(HUMAN,family,null,recorded.minusNanos(1000)).get("versionId"));
  assertEquals(v2.get("id"),retrieval.get(HUMAN,family,null,recorded).get("versionId"));
  assertEquals(2,retrieval.history(HUMAN,family).size());
  assertTrue(ledger.audit(HUMAN,v1,100).stream().anyMatch(x->x.get("action").equals("SUPERSEDE")));
  assertTrue(ledger.audit(HUMAN,cid,100).stream().anyMatch(x->x.get("action").equals("VERIFY")));
 }
 @Test void candidateIdempotencyAndMinimalCapture(){var r=captureRequest();assertEquals(ledger.capture(HUMAN,r).get("id"),ledger.capture(HUMAN,r).get("id"));var minimal=new Capture("一句话",null,null,null,null,null,null,null,null,null);assertNotEquals(ledger.capture(HUMAN,minimal).get("id"),ledger.capture(HUMAN,minimal).get("id"));}
 @Test void agentCannotVerifyOrSpoofActor(){
  var c=reviewed(draft());var headers=new HttpHeaders();headers.setBearerAuth(AGENT_TOKEN);headers.set("X-Actor-Type","HUMAN");
  var response=http.postForEntity("/api/v1/candidates/"+c.get("id")+"/verify",new HttpEntity<>(new Verify("CREATE_NEW_FAMILY","forbidden","test","FAILURE",null,null,revision(c),"attempt"),headers),JsonNode.class);
  assertEquals(403,response.getStatusCode().value());assertEquals("AGENT_GATEWAY_REQUIRED",response.getBody().path("code").asText());
 }
 @Test void temporalValidAndRecordedBoundaries(){
  var d=draft();var end=Instant.parse("2026-07-01T00:00:00Z");
  var limited=new Draft(d.title(),d.summary(),d.problem(),d.decision(),d.action(),d.outcomeSummary(),d.lesson(),d.validFrom(),end,d.applicability(),d.constraints(),d.claims(),d.contextRefs(),d.episodes());
  var c=reviewed(limited);var v=ledger.verify(HUMAN,(UUID)c.get("id"),new Verify("CREATE_NEW_FAMILY",UUID.randomUUID().toString(),"test","WARNING",null,null,revision(c),"accept"));UUID family=(UUID)v.get("family_id");Instant recorded=Instant.parse((String)v.get("recorded_at"));
  assertEquals(v.get("id"),retrieval.get(HUMAN,family,d.validFrom(),recorded).get("versionId"));
  assertThrows(LedgerException.class,()->retrieval.get(HUMAN,family,d.validFrom().minusNanos(1000),recorded));
  assertThrows(LedgerException.class,()->retrieval.get(HUMAN,family,end,recorded));
  assertThrows(LedgerException.class,()->retrieval.get(HUMAN,family,d.validFrom(),recorded.minusNanos(1000)));
 }
 @Test void immutableVersionAndAssociations(){var v=publish();UUID id=(UUID)v.get("id");
  for(String assignment:List.of("title='overwritten'","valid_to=clock_timestamp()","valid_from=clock_timestamp()","supersedes_id=id"))assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("update exp_experience_version set "+assignment+" where space_id=:space and id=:id",db.scoped(HUMAN,"id",id))));
  assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("insert into exp_context_ref(id,space_id,experience_version_id,ref_type,ref_value) values(gen_random_uuid(),:space,:id,'PROJECT','late')",db.scoped(HUMAN,"id",id))));
  assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("insert into exp_experience_claim(id,space_id,experience_version_id,claim_type,content,origin_type,sequence_no) values(gen_random_uuid(),:space,:id,'LESSON','late','HUMAN_ASSERTED',99)",db.scoped(HUMAN,"id",id))));
  assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("delete from exp_experience_version where space_id=:space and id=:id",db.scoped(HUMAN,"id",id))));
 }
 @Test void evidenceCorrectionAndClaimLink(){
  var e=ledger.createEvidence(HUMAN,new EvidenceInput("TEST_RESULT","test","run-1",db.tree(Map.of("selected",57)),null,null,Instant.now(),0.8,null));
  var d=draft();var observed=new ClaimInput("OBSERVATION","selected 57","OBSERVED",null,List.of(new EvidenceLink((UUID)e.get("id"),"SUPPORTS")));
  var cd=new Draft(d.title(),d.summary(),d.problem(),d.decision(),d.action(),d.outcomeSummary(),d.lesson(),d.validFrom(),null,d.applicability(),d.constraints(),List.of(observed,new ClaimInput("CAUSAL_HYPOTHESIS","pruning caused loss","AGENT_DERIVED","comparison of test runs",List.of(new EvidenceLink((UUID)e.get("id"),"CONTEXT")))),null,null);
  var c=reviewed(cd);var v=ledger.verify(HUMAN,(UUID)c.get("id"),new Verify("CREATE_NEW_FAMILY",UUID.randomUUID().toString(),"test","OPTIMIZATION",null,null,revision(c),"accept"));
  var replacement=ledger.correctEvidence(HUMAN,(UUID)e.get("id"),new EvidenceInput("TEST_RESULT","test","run-1-corrected",db.tree(Map.of("selected",79)),null,null,Instant.now(),0.8,null),"correct count");
  var old=ledger.evidence(HUMAN,(UUID)e.get("id"));assertEquals("SUPERSEDED",old.get("status"));assertEquals(replacement.get("id"),old.get("corrected_by_evidence_id"));assertEquals(57,((JsonNode)old.get("snapshot_json")).path("selected").asInt());
  assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("update exp_evidence set source_ref='changed' where space_id=:space and id=:id",db.scoped(HUMAN,"id",e.get("id")))));
  assertEquals(1,((List<?>)retrieval.get(HUMAN,(UUID)v.get("family_id"),null,null).get("keyEvidence")).size());
 }
 @Test void auditAppendOnly(){var c=candidate();UUID id=(UUID)c.get("id");assertFalse(ledger.audit(HUMAN,id,100).isEmpty());
  for(String sql:List.of("update exp_audit_event set reason='rewrite' where space_id=:space and target_id=:id","delete from exp_audit_event where space_id=:space and target_id=:id","truncate exp_audit_event"))assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update(sql,db.scoped(HUMAN,"id",id))));
 }
 @Test void spaceIsolationAndCrossSpaceFk(){
  var v=publish();var other=new ActorContext(ActorContext.ActorType.HUMAN,"other",UUID.randomUUID());db.with(other,null,()->{db.update("insert into exp_space(id,name) values(:space,'other')",db.scoped(other));return null;});
  assertThrows(LedgerException.class,()->retrieval.get(other,(UUID)v.get("family_id"),null,null));
  db.with(other,null,()->{assertTrue(db.list("select * from exp_experience_version",Map.of()).isEmpty());return null;});
  assertThrows(RuntimeException.class,()->db.with(other,null,()->db.update("insert into exp_usage(id,space_id,experience_version_id,recommended,actually_used,actor_type,actor_id) values(gen_random_uuid(),:space,:id,true,true,'HUMAN','other')",db.scoped(other,"id",v.get("id")))));
 }
 @Test void promotionAndImmediateOutcomeRollback(){
  var d=draft();var broken=new Draft(d.title(),d.summary(),d.problem(),d.decision(),d.action(),d.outcomeSummary(),d.lesson(),d.validFrom(),null,null,null,List.of(new ClaimInput("LESSON","lesson","HUMAN_ASSERTED",null,List.of(new EvidenceLink(UUID.randomUUID(),"SUPPORTS")))),null,null);
  var c=reviewed(broken);String key=UUID.randomUUID().toString();assertThrows(RuntimeException.class,()->ledger.verify(HUMAN,(UUID)c.get("id"),new Verify("CREATE_NEW_FAMILY",key,"test","FAILURE",null,null,revision(c),"fail")));
  assertEquals("PENDING_REVIEW",ledger.candidate(HUMAN,(UUID)c.get("id")).get("status"));db.with(HUMAN,null,()->{assertTrue(db.list("select * from exp_experience_family where space_id=:space and experience_key=:key",db.scoped(HUMAN,"key",key)).isEmpty());return null;});
  var v=publish();assertThrows(RuntimeException.class,()->ledger.usage(HUMAN,new UsageInput((UUID)v.get("id"),null,null,null,true,true,null,new OutcomeInput("NOT_A_TYPE",null,null,Instant.now(),null))));
  assertEquals(0L,((Map<?,?>)retrieval.get(HUMAN,(UUID)v.get("family_id"),null,null).get("usageStats")).get("usage_count"));
 }
 @Test void crossFamilyAndStaleSupersessionRejected(){var v1=publish();var other=publish();var c=reviewed(draft());
  assertThrows(LedgerException.class,()->ledger.supersede(HUMAN,(UUID)other.get("family_id"),new Supersede((UUID)c.get("id"),(UUID)v1.get("id"),revision(c),"bad family")));
  ledger.supersede(HUMAN,(UUID)v1.get("family_id"),new Supersede((UUID)c.get("id"),(UUID)v1.get("id"),revision(c),"valid"));
  var c2=reviewed(draft());assertThrows(LedgerException.class,()->ledger.supersede(HUMAN,(UUID)v1.get("family_id"),new Supersede((UUID)c2.get("id"),(UUID)v1.get("id"),revision(c2),"stale")));
 }
 @Test void concurrentSupersessionHasOneWinner()throws Exception{
  Assumptions.assumeFalse(WASM,"PGlite serializes connections; native PostgreSQL concurrency gate required");
  var v=publish();var first=reviewed(draft());var second=reviewed(draft());var gate=new CountDownLatch(1);
  try(var executor=Executors.newFixedThreadPool(2)){
   List<Future<Boolean>> futures=new ArrayList<>();for(var c:List.of(first,second))futures.add(executor.submit(()->{gate.await();try{ledger.supersede(HUMAN,(UUID)v.get("family_id"),new Supersede((UUID)c.get("id"),(UUID)v.get("id"),revision(c),"race"));return true;}catch(RuntimeException e){return false;}}));
   gate.countDown();int winners=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))winners++;assertEquals(1,winners);assertEquals(2,retrieval.history(HUMAN,(UUID)v.get("family_id")).size());
  }
 }
 @Test void providerFailureAndFtsFallback(){var c=candidate();embedding.mode="failed";enrichment.fail=true;drainJobs();
  var after=ledger.candidate(HUMAN,(UUID)c.get("id"));assertEquals("FAILED",after.get("embedding_status"));assertEquals("PENDING_REVIEW",after.get("status"));
  var v=publish();var response=retrieval.search(HUMAN,query("pruning"));assertEquals("FTS_METADATA_FALLBACK",response.get("mode"));assertTrue(results(response).stream().anyMatch(x->x.get("versionId").equals(v.get("id"))));
 }
 @Test void hybridAndApplicabilityAndContradictions(){
  embedding.mode="ready";var v=publish();var other=publish();drainJobs();ledger.relation(HUMAN,new Relation((UUID)v.get("id"),(UUID)other.get("id"),"CONTRADICTS","different conditions"));
  var response=retrieval.search(HUMAN,query("pruning"));assertEquals("HYBRID",response.get("mode"));var found=results(response).stream().filter(x->x.get("versionId").equals(v.get("id"))).findFirst().orElseThrow();
  assertFalse(((List<?>)found.get("contradictionWarnings")).isEmpty());assertTrue(((Number)((Map<?,?>)found.get("whyMatched")).get("semantic")).doubleValue()>0);
  var mismatch=retrieval.search(HUMAN,new Search("pruning",null,null,null,null,db.tree(Map.of("length",600)),null,100));var bad=results(mismatch).stream().filter(x->x.get("versionId").equals(v.get("id"))).findFirst().orElseThrow();assertTrue(((Number)found.get("score")).doubleValue()>((Number)bad.get("score")).doubleValue());
 }
 @Test void mergeRetainsCandidateWithoutMutatingVersion(){var v=publish();var c=reviewed(draft());ledger.disposition(HUMAN,(UUID)c.get("id"),new Disposition(revision(c),"MERGED",(UUID)v.get("id"),null,"same lesson"));assertEquals("MERGED",ledger.candidate(HUMAN,(UUID)c.get("id")).get("status"));assertEquals(1,retrieval.history(HUMAN,(UUID)v.get("family_id")).size());}
 @Test void invalidationRetainsHistoryAndFeedbackCreatesCandidate(){var v=publish();var feedback=ledger.feedback(HUMAN,(UUID)v.get("id"),new Feedback("new failure condition",null));assertEquals("EVOLUTION",feedback.get("candidate_type"));ledger.invalidate(HUMAN,(UUID)v.get("id"),"no longer applicable");assertThrows(LedgerException.class,()->retrieval.get(HUMAN,(UUID)v.get("family_id"),null,null));assertEquals(1,retrieval.history(HUMAN,(UUID)v.get("family_id")).size());}
 void drainJobs(){for(int i=0;i<150 && worker.processOne(SPACE);i++){} }
 @Autowired com.example.ledger.judgment.JudgmentService judgments;
 @Autowired com.example.ledger.context.ContextGovernance governance;
 @Autowired com.example.ledger.context.ContextService contexts;
 com.example.ledger.judgment.JudgmentRequests.Save judgmentRequest(String domain,UUID evidence){
  var rule=new com.example.ledger.judgment.JudgmentRequests.Rule("先核对工况","决定是否拆检","振动上升",List.of("负载改变了吗？"),"先排除工况变化，再判断轴承故障",List.of("不可超安全负载"),"误停机成本与漏检风险","比较同工况趋势","传感器失效时不能直接套用","新反例出现时重审","盲目换轴承无效",0.99,false);
  return new com.example.ledger.judgment.JudgmentRequests.Save(rule,new com.example.ledger.judgment.JudgmentRequests.Gate(true,true,false,"先问工况再拆检"),domain,UUID.randomUUID().toString(),Instant.now().minusSeconds(1),null,db.tree(Map.of("assetType","pump")),db.tree(Map.of("maintenanceWindow",true)),evidence==null?List.of():List.of(evidence),UUID.randomUUID().toString(),null,null,null);
 }
 @SuppressWarnings("unchecked") Map<String,Object> judgmentCandidate(com.example.ledger.judgment.JudgmentRequests.Save r){return (Map<String,Object>)((Map<?,?>)judgments.save(HUMAN,null,r)).get("candidate");}
 @SuppressWarnings("unchecked") Map<String,Object> publishJudgment(com.example.ledger.judgment.JudgmentRequests.Save r){var c=judgmentCandidate(r);return (Map<String,Object>)judgments.publish(HUMAN,(UUID)c.get("id"),new com.example.ledger.judgment.JudgmentRequests.Publish(revision(c),"human reviewed boundaries",null,null));}
 UUID evidence(){return (UUID)ledger.createEvidence(HUMAN,new EvidenceInput("TEST_RESULT",null,null,db.tree(Map.of("result","load-normalized test")),null,null,Instant.now(),0.9,null)).get("id");}
 @SuppressWarnings("unchecked") UUID policyFor(String domain){var rules=new com.example.ledger.context.ContextRequests.Rules(Set.of(domain),Set.of("diagnosis"),Set.of("HUMAN_ASSERTED","AGENT_DERIVED","OBSERVED"),12000,5,4,0.4,0.5,3650,false,false,false,false,50,100);return (UUID)((Map<String,Object>)governance.policy(HUMAN,null,new com.example.ledger.context.ContextRequests.Policy("judgment test",true,rules,null,"test"))).get("id");}
 com.example.ledger.context.ContextRequests.Context contextRequest(String domain,UUID policy,int budget){return new com.example.ledger.context.ContextRequests.Context(domain,"diagnosis","pump","",db.tree(Map.of("maintenanceWindow",true)),budget,5,null,false,false,false,policy);}
 JsonNode packet(String domain,UUID policy){return db.tree(contexts.context(HUMAN,contextRequest(domain,policy,12000)));}
 UUID grant(UUID version,String mode,UUID prior,List<UUID> evidence){var result=db.tree(judgments.reuse(HUMAN,version,new com.example.ledger.judgment.JudgmentRequests.Reuse("AGENT",mode,mode.equals("CROSS_REUSABLE")?"TEST":"HUMAN_REVIEW","reviewed for pump diagnosis",evidence,prior)));return UUID.fromString(result.path("grant").path("id").asText());}
 @Test void judgmentGateDoesNotPersistAndAgentCannotForgeHumanCard(){
  long count=db.with(HUMAN,null,()->(Long)db.one("select count(*) n from exp_candidate where space_id=:space",db.scoped(HUMAN)).get("n"));
  var good=judgmentRequest("gate",null);var rejected=new com.example.ledger.judgment.JudgmentRequests.Save(good.rule(),new com.example.ledger.judgment.JudgmentRequests.Gate(false,false,true,""),good.domain(),good.experienceKey(),good.validFrom(),null,good.applicability(),good.constraints(),List.of(),good.eventKey(),null,null,null);
  assertFalse(db.tree(judgments.save(HUMAN,null,rejected)).path("saved").asBoolean());
  assertEquals(count,db.with(HUMAN,null,()->(Long)db.one("select count(*) n from exp_candidate where space_id=:space",db.scoped(HUMAN)).get("n")));
  var agent=new ActorContext(ActorContext.ActorType.AGENT,"coding-agent",SPACE);assertThrows(LedgerException.class,()->judgments.save(agent,null,good));
  var headers=new HttpHeaders();headers.setBearerAuth(AGENT_TOKEN);headers.setContentType(MediaType.APPLICATION_JSON);
  assertEquals(403,http.postForEntity("/api/v2/judgments/candidates",new HttpEntity<>(good,headers),JsonNode.class).getStatusCode().value());
 }
 @Test void humanJudgmentLifecycleSharingBudgetAndRevocation(){
  String domain="judgment-"+UUID.randomUUID();UUID e=evidence();var request=judgmentRequest(domain,e);var c=judgmentCandidate(request);
  assertEquals(c.get("id"),judgmentCandidate(request).get("id"));
  UUID policy=policyFor(domain);assertTrue(packet(domain,policy).path("selected").isEmpty());
  var v=(Map<?,?>)judgments.publish(HUMAN,(UUID)c.get("id"),new com.example.ledger.judgment.JudgmentRequests.Publish(revision(c),"confirm judgment",null,null));UUID id=(UUID)v.get("id");
  assertTrue(packet(domain,policy).path("selected").isEmpty());
  var detail=db.tree(judgments.detail(HUMAN,id));assertEquals("HUMAN",detail.path("sharing").path("native_track").asText());assertEquals(0.99,detail.path("judgmentRule").path("authorConfidence").asDouble());
  var bindings=db.tree(governance.bindings(HUMAN));Integer bindingRevision=0;for(var b:bindings)if(b.path("actor_type").asText().equals("HUMAN")&&b.path("actor_id").asText().equals(HUMAN.actorId()))bindingRevision=b.path("revision").asInt();
  governance.bind(HUMAN,new com.example.ledger.context.ContextRequests.Binding("HUMAN",HUMAN.actorId(),policy,true,bindingRevision,"test evidence permission"));
  UUID first=grant(id,"CROSS_REFERENCE",null,List.of());var supplied=packet(domain,policy);assertEquals(1,supplied.path("selected").size());
  assertEquals(0.5,supplied.path("selected").get(0).path("assessedConfidence").asDouble());
  for(String expected:List.of("负载改变了吗","不可超安全负载","误停机成本","传感器失效","新反例","CROSS_REFERENCE","HUMAN_ASSERTED"))assertTrue(supplied.path("contextText").asText().contains(expected),expected);
  assertEquals(supplied.path("contextText").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,supplied.path("budget").path("contextUnits").asInt());
  assertTrue(db.tree(contexts.context(HUMAN,contextRequest(domain,policy,256))).path("selected").isEmpty());
  var compact=(Map<?,?>)governance.createCompact(HUMAN,new com.example.ledger.context.ContextRequests.Compact("pump","short summary",domain,"diagnosis",id,List.of(id),"test"));governance.compactState(HUMAN,(UUID)compact.get("id"),true,"confirm");packet(domain,policy);
  contexts.evidence(HUMAN,e,UUID.fromString(supplied.path("runId").asText()));
  UUID revoked=grant(id,"NATIVE_ONLY",first,List.of());assertNotNull(revoked);var stopped=packet(domain,policy);assertTrue(stopped.path("selected").isEmpty());assertTrue(stopped.path("diagnostics").path("staleCompacts").asInt()>0);
  assertThrows(LedgerException.class,()->grant(id,"CROSS_REFERENCE",first,List.of()));
  assertThrows(LedgerException.class,()->contexts.evidence(HUMAN,e,UUID.fromString(supplied.path("runId").asText())));
 }
 @Test void crossReuseEvidenceCorrectionAndDatabaseAppendOnly(){
  String domain="reuse-"+UUID.randomUUID();UUID id=(UUID)publishJudgment(judgmentRequest(domain,null)).get("id"),policy=policyFor(domain),e=evidence();
  assertThrows(LedgerException.class,()->grant(id,"CROSS_REUSABLE",null,List.of()));UUID event=grant(id,"CROSS_REUSABLE",null,List.of(e));var supplied=packet(domain,policy);assertFalse(supplied.path("selected").isEmpty());assertEquals(e.toString(),supplied.path("selected").get(0).path("evidence").get(0).path("evidenceId").asText());
  var noEvidence=new com.example.ledger.context.ContextRequests.Context(domain,"diagnosis","pump","",db.tree(Map.of("maintenanceWindow",true)),12000,0,null,false,false,false,policy);
  assertTrue(db.tree(contexts.context(HUMAN,noEvidence)).path("selected").isEmpty());
  ledger.correctEvidence(HUMAN,e,new EvidenceInput("TEST_RESULT",null,null,db.tree(Map.of("result","retracted")),null,null,Instant.now(),0.9,null),"test correction");assertTrue(packet(domain,policy).path("selected").isEmpty());
  for(String table:List.of("exp_judgment_rule","exp_experience_track","exp_reuse_event","exp_reuse_evidence")){
   assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("update "+table+" set id=id where space_id=:space",db.scoped(HUMAN))));
   assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("delete from "+table+" where space_id=:space",db.scoped(HUMAN))));
  }
  assertThrows(RuntimeException.class,()->db.with(HUMAN,null,()->db.update("insert into exp_judgment_rule(id,space_id,experience_version_id,rule_json) values(:id,:space,:version,'{}')",db.scoped(HUMAN,"id",UUID.randomUUID(),"version",id))));
  assertTrue(ledger.audit(HUMAN,event,20).size()>0);
  var other=new ActorContext(ActorContext.ActorType.HUMAN,"other",UUID.randomUUID());assertThrows(LedgerException.class,()->judgments.detail(other,id));
 }
 @Test void supersessionRequiresNewSharingAndFamilyTrackCannotChange(){
  String domain="evolution-"+UUID.randomUUID();var request=judgmentRequest(domain,null);var v=publishJudgment(request);UUID id=(UUID)v.get("id"),family=(UUID)v.get("family_id"),policy=policyFor(domain);grant(id,"CROSS_REFERENCE",null,List.of());
  var next=judgmentCandidate(judgmentRequest(domain,null));var successor=(Map<?,?>)judgments.publish(HUMAN,(UUID)next.get("id"),new com.example.ledger.judgment.JudgmentRequests.Publish(revision(next),"new boundary",family,id));
  assertTrue(packet(domain,policy).path("selected").isEmpty());assertThrows(LedgerException.class,()->grant(id,"CROSS_REFERENCE",null,List.of()));
  var agent=new ActorContext(ActorContext.ActorType.AGENT,"coding-agent",SPACE);var c=ledger.capture(agent,captureRequest());c=ledger.review(HUMAN,(UUID)c.get("id"),new Review(revision(c),draft(),"review"));var reviewed=c;
  assertThrows(LedgerException.class,()->ledger.verify(HUMAN,(UUID)reviewed.get("id"),new Verify("CREATE_NEW_VERSION",null,null,null,family,(UUID)successor.get("id"),revision(reviewed),"wrong track")));
  assertEquals("VERIFIED",ledger.candidate(HUMAN,(UUID)next.get("id")).get("status"));
 }
 @Test void judgmentEditorCasAndDraftDivergenceAreRejected(){
  var r=judgmentRequest("cas-"+UUID.randomUUID(),null);var c=judgmentCandidate(r);UUID id=(UUID)c.get("id");
  assertThrows(LedgerException.class,()->judgments.save(HUMAN,id,r));
  assertThrows(LedgerException.class,()->ledger.review(HUMAN,id,new Review(revision(c),draft(),"diverge")));
  var edited=new com.example.ledger.judgment.JudgmentRequests.Save(r.rule(),r.gate(),r.domain(),r.experienceKey(),r.validFrom(),null,r.applicability(),r.constraints(),r.evidenceIds(),r.eventKey(),revision(c),null,null);
  assertTrue(db.tree(judgments.save(HUMAN,id,edited)).path("saved").asBoolean());
  assertThrows(LedgerException.class,()->judgments.publish(HUMAN,id,new com.example.ledger.judgment.JudgmentRequests.Publish(revision(c),"stale",null,null)));
 }

 @Test void agentNativeCaptureStaysAgentDespiteHumanPublication(){
  var agent=new ActorContext(ActorContext.ActorType.AGENT,"coding-agent",SPACE);var request=captureRequest();
  var c=ledger.capture(agent,new Capture(request.content(),null,"HUMAN",null,null,null,null,null,null,null));
  c=ledger.review(HUMAN,(UUID)c.get("id"),new Review(revision(c),draft(),"human reviewed agent experience"));
  String domain="agent-native-"+UUID.randomUUID();var v=ledger.verify(HUMAN,(UUID)c.get("id"),new Verify("CREATE_NEW_FAMILY",UUID.randomUUID().toString(),domain,"DECISION",null,null,revision(c),"accept"));
  var detail=db.tree(judgments.detail(HUMAN,(UUID)v.get("id")));assertEquals("AGENT",detail.path("sharing").path("native_track").asText());
  UUID policy=policyFor(domain);var ctx=new com.example.ledger.context.ContextRequests.Context(domain,"diagnosis",null,"",db.tree(Map.of("length",800,"gpuAvailable",false)),12000,5,null,false,false,false,policy);
  assertEquals(1,db.tree(contexts.context(HUMAN,ctx)).path("selected").size());
 }
}
