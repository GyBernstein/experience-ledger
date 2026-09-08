package com.example.ledger.provider;
import com.fasterxml.jackson.databind.JsonNode;
public class NoopEnrichmentProvider implements ExperienceEnrichmentProvider {
 @Override public JsonNode enrich(Candidate candidate,CaptureContext context){return candidate.extracted().deepCopy();}
}
