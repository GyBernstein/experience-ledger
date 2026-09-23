package com.example.ledger.authoring;

import com.example.ledger.domain.ActorContext;
import tools.jackson.databind.JsonNode;
import java.util.*;

public interface DraftAssistProvider {
 Result generate(GenerateRequest request);
 Result revise(RevisionRequest request);

 record EvidenceRef(String id,String type,String sourceSystem,String sourceRef,double reliability,boolean rawInput) {}
 record Prompt(String code,int version,String systemPrompt,String userTemplate,JsonNode outputSchema) {}
 record GenerateRequest(ActorContext actor,String sourceType,String rawContent,JsonNode taskContext,List<EvidenceRef> availableEvidence,String language,Prompt prompt) {}
 record RevisionRequest(ActorContext actor,String rawContent,JsonNode taskContext,List<EvidenceRef> availableEvidence,String language,JsonNode currentDraft,String instruction,Prompt prompt) {}
 record Result(JsonNode content,String provider,String model,Long inputTokens,Long outputTokens,Double estimatedCost,String currency) {}
}
