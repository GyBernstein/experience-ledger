package com.example.ledger;
import com.example.ledger.domain.*;
import com.example.ledger.retrieval.Applicability;
import com.example.ledger.provider.EmbeddingProvider;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class DomainTest {
 @Test void stateMachine(){
  Map<CandidateState,Set<CandidateState>> allowed=Map.of(
   CandidateState.NEW,Set.of(CandidateState.ENRICHED,CandidateState.DUPLICATE,CandidateState.REJECTED,CandidateState.EXPIRED),
   CandidateState.ENRICHED,Set.of(CandidateState.PENDING_REVIEW,CandidateState.DUPLICATE,CandidateState.REJECTED,CandidateState.EXPIRED),
   CandidateState.PENDING_REVIEW,Set.of(CandidateState.VERIFIED,CandidateState.MERGED,CandidateState.REJECTED));
  for(var from:CandidateState.values())for(var to:CandidateState.values()){
   if(allowed.getOrDefault(from,Set.of()).contains(to))assertDoesNotThrow(()->from.transitionTo(to));
   else assertThrows(LedgerException.class,()->from.transitionTo(to),from+" -> "+to);
  }
 }
 @Test void observedAndDerived(){
  assertDoesNotThrow(()->ClaimRules.validate("OBSERVATION","OBSERVED",null));
  assertThrows(LedgerException.class,()->ClaimRules.validate("RULE","OBSERVED",null));
  assertThrows(LedgerException.class,()->ClaimRules.validate("LESSON","AGENT_DERIVED",null));
  assertDoesNotThrow(()->ClaimRules.validate("CAUSAL_HYPOTHESIS","AGENT_DERIVED","tool-result synthesis"));
 }
 @Test void originCannotBeRelabelled()throws Exception{
  var json=tools.jackson.databind.json.JsonMapper.builder().build();var before=json.readTree("""
   {"claims":[{"content":"pruning causes loss","originType":"AGENT_DERIVED","claimType":"CAUSAL_HYPOTHESIS"}]}
   """);
  assertThrows(LedgerException.class,()->ClaimRules.preserveOrigin(before,json.readTree("""
   {"claims":[{"content":"pruning causes loss","originType":"OBSERVED","claimType":"OBSERVATION"}]}
   """)));
  assertThrows(LedgerException.class,()->ClaimRules.preserveOrigin(before,json.readTree("""
   {"claims":[{"content":"pruning causes loss","originType":"AGENT_DERIVED","claimType":"RULE"}]}
   """)));
 }
 @Test void applicabilityRangesAndUnknowns()throws Exception{
  var json=tools.jackson.databind.json.JsonMapper.builder().build();var rules=json.readTree("{\"lengthMin\":700,\"lengthMax\":900,\"material\":[\"A\",\"B\"]}");
  assertEquals(1,Applicability.evaluate(rules,json.readTree("{\"length\":700,\"material\":\"A\"}")).score());
  assertEquals(-1,Applicability.evaluate(json.readTree("{\"lengthMin\":700}"),json.readTree("{\"length\":699}")).score());
  assertEquals(0,Applicability.evaluate(rules,json.readTree("{}")).score());
 }
 @Test void agentCannotGovern(){assertThrows(LedgerException.class,()->new ActorContext(ActorContext.ActorType.AGENT,"coding-agent",UUID.randomUUID()).requireGovernance());}
 @Test void embeddingRejectsDimensionsNanZero(){assertThrows(IllegalArgumentException.class,()->new EmbeddingProvider.Result(new float[3],"test"));assertThrows(IllegalArgumentException.class,()->new EmbeddingProvider.Result(new float[384],"test"));float[] v=new float[384];v[0]=Float.NaN;assertThrows(IllegalArgumentException.class,()->new EmbeddingProvider.Result(v,"test"));}
}
