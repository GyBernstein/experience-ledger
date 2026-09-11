package com.example.ledger.provider;
import tools.jackson.databind.JsonNode;
import com.example.ledger.domain.ActorContext;
public interface ExperienceEnrichmentProvider {
 record Candidate(String content,JsonNode extracted){}
 record CaptureContext(ActorContext actor){}
 JsonNode enrich(Candidate candidate,CaptureContext context) throws Exception;
}
