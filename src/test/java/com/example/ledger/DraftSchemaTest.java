package com.example.ledger;

import com.example.ledger.authoring.DraftSchema;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DraftSchemaTest {
 private final ObjectMapper json=new ObjectMapper();
 private final DraftSchema schema=new DraftSchema(json);
 @Test void normalizesConservativelyAndDropsInventedEvidence()throws Exception{
  var input=json.readTree("""
   {"title":"Maven boundary","summary":"check BOM","context":"upgrade","lesson":"check the boundary","reusablePrinciple":"verify scope first",
    "claims":[{"kind":"OBSERVED","content":"build passed","confidence":1}],
    "evidenceMappings":[{"claimIndex":0,"evidenceRef":"made-up","relation":"SUPPORTS"}],
    "confidence":{"overall":0.8}}
   """);
  var draft=schema.normalize(input,"raw immutable input",Set.of());
  assertEquals("AGENT_DERIVED",draft.path("claims").path(0).path("originType").asText());
  assertTrue(draft.path("evidenceMappings").isEmpty());
  assertTrue(schema.validate(draft).isEmpty());
 }
 @Test void diffIsFieldLevel()throws Exception{
  var before=json.readTree("{\"title\":\"old\",\"summary\":\"same\"}");var after=json.readTree("{\"title\":\"new\",\"summary\":\"same\"}");
  assertEquals(1,schema.diff(before,after).size());assertEquals("title",schema.diff(before,after).path(0).path("field").asText());
 }
}
