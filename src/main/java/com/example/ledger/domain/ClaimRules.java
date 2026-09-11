package com.example.ledger.domain;
import tools.jackson.databind.JsonNode;
public final class ClaimRules {
 private ClaimRules() {}
 public static void validate(String type,String origin,String method) {
  if(origin==null || type==null) throw LedgerException.invalid("Claim type and origin required");
  if(origin.endsWith("_DERIVED") && (method==null || method.isBlank())) throw LedgerException.invalid("Derived claim needs derivationMethod");
  if(origin.equals("OBSERVED") && !type.equals("OBSERVATION")) throw LedgerException.invalid("OBSERVED must describe an OBSERVATION");
 }
 public static void preserveOrigin(JsonNode before,JsonNode after) {
  for(JsonNode old:before.path("claims")) for(JsonNode current:after.path("claims")) {
   if(old.path("content").asText().equals(current.path("content").asText()) && old.path("originType").asText().equals("AGENT_DERIVED")) {
    if(!current.path("originType").asText().equals("AGENT_DERIVED") || (old.path("claimType").asText().equals("CAUSAL_HYPOTHESIS") && current.path("claimType").asText().equals("RULE")))
     throw LedgerException.invalid("Agent derivation provenance cannot be relabelled; record a separate evidence-backed observation");
   }
  }
 }
}
