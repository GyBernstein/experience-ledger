package com.example.ledger.provider;
import tools.jackson.databind.JsonNode;
public class NoopEnrichmentProvider implements ExperienceEnrichmentProvider {
 @Override public JsonNode enrich(Candidate candidate,CaptureContext context){return candidate.extracted().deepCopy();}
}
