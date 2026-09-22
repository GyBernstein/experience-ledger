package com.example.ledger;

import com.example.ledger.api.Requests.*;
import com.example.ledger.application.*;
import com.example.ledger.authoring.*;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.provider.*;
import com.example.ledger.retrieval.RetrievalService;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LedgerIT.Providers.class)
class LedgerIT {
 static PostgreSQLContainer<?> container;
 static final UUID SPACE=UUID.fromString("11111111-1111-1111-1111-111111111111");
 static final String HUMAN_TOKEN="test-human-token-0123456789",AGENT_TOKEN="test-agent-token-0123456789";
 static final ActorContext HUMAN=new ActorContext(ActorContext.ActorType.HUMAN,"reviewer",SPACE);
 static final boolean WASM="true".equals(System.getenv("LEDGER_IT_WASM"));
 @DynamicPropertySource static void configure(DynamicPropertyRegistry r)throws Exception{
  String url=System.getenv("LEDGER_IT_URL"),admin=System.getenv().getOrDefault("LEDGER_IT_ADMIN_USER","postgres"),password=System.getenv().getOrDefault("LEDGER_IT_ADMIN_PASSWORD","test-only");
  if(url==null){container=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:0.8.2-pg16").asCompatibleSubstituteFor("postgres")).withDatabaseName("ledger_test").withUsername(admin).withPassword(password);container.start();url=container.getJdbcUrl();}
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
 @Autowired Db db;@Autowired LedgerService ledger;@Autowired RetrievalService retrieval;@Autowired ProcessingWorker worker;@Autowired AuthoringService authoring;
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
  assertTrue(((Number)db.one("select count(*) as n from flyway_schema_history where success and type='SQL'",Map.of()).get("n")).longValue()>=4L);
  assertEquals(1L,db.one("select count(*) as n from flyway_schema_history where success and version='5'",Map.of()).get("n"));return null;
 });}
 @Test void aiAssistedDraftKeepsRawInputAndPublishesOnlyAfterHumanAccept(){
  var request=new AuthoringRequests.Capture("构建缺少依赖版本，检查 effective-pom 后发现 BOM 未覆盖子模块。", "MANUAL_TEXT","test://authoring",db.tree(Map.of("domain","engineering","taskType","maven_diagnosis")),List.of(),"zh-CN",true,"authoring-"+UUID.randomUUID());var captured=authoring.captureHuman(HUMAN,request);
  UUID draftId=UUID.fromString(captured.get("id").toString());assertEquals("CREATED",captured.get("status"));assertTrue(((JsonNode)captured.get("structuredContent")).path("missingInformation").size()>0);
  assertEquals(draftId,authoring.captureHuman(HUMAN,request).get("id"));
  var result=authoring.accept(HUMAN,draftId,new AuthoringRequests.Publication(((Number)captured.get("draftVersion")).intValue(),"CREATE_NEW_FAMILY",null,"engineering","DECISION",null,null,"human confirms conservative draft"));
  assertNotNull(((Map<?,?>)result.get("experience")).get("id"));assertEquals("ACCEPTED",authoring.draft(HUMAN,draftId).get("status"));
  assertEquals(1L,db.with(HUMAN,null,()->db.one("select count(*) n from exp_draft_publication where space_id=:space and draft_id=:id",db.scoped(HUMAN,"id",draftId)).get("n")));
 }
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
}
