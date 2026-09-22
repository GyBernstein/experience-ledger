package com.example.ledger.authoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary @Component
public class ConfiguredDraftAssistProvider implements DraftAssistProvider {
 private final boolean enabled;private final String provider;private final LocalDraftAssistProvider local;private final OpenAiCompatibleDraftAssistProvider remote;
 public ConfiguredDraftAssistProvider(@Value("${ledger.ai.enabled:false}") boolean enabled,@Value("${ledger.ai.provider:local}") String provider,LocalDraftAssistProvider local,OpenAiCompatibleDraftAssistProvider remote){this.enabled=enabled;this.provider=provider;this.local=local;this.remote=remote;}
 private DraftAssistProvider selected(){return enabled&&!provider.equalsIgnoreCase("local")?remote:local;}
 @Override public Result generate(GenerateRequest request){return selected().generate(request);}
 @Override public Result revise(RevisionRequest request){return selected().revise(request);}
}
