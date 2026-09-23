package com.example.ledger.authoring;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.*;

public final class AuthoringRequests {
 private AuthoringRequests() {}

 public record Capture(
  @NotBlank @Size(max=100000) String rawContent,
  @NotBlank String sourceType,
  @Size(max=500) String sourceRef,
  JsonNode taskContext,
  List<@NotBlank String> evidenceRefs,
  @Size(max=30) String language,
  Boolean generateDraft,
  @Size(max=200) String dedupKey) {}

 public record AgentCapture(
  @NotBlank @Size(max=200) String agentRole,
  @NotBlank String sourceType,
  @Size(max=500) String sourceRef,
  @Size(max=20000) String task,
  @NotBlank @Size(max=100000) String rawContent,
  @Size(max=50000) String result,
  @Size(max=50000) String outcome,
  JsonNode taskContext,
  List<@NotBlank String> evidenceRefs,
  @Size(max=30) String language,
  @Size(max=200) String dedupKey) {}

 public record Revision(
  @NotBlank @Size(max=10000) String instruction,
  @NotNull Integer expectedDraftVersion) {}

 public record ManualEdit(
  @NotNull JsonNode structuredContent,
  @NotNull Integer expectedDraftVersion,
  @NotBlank @Size(max=1000) String reason) {}

 public record Regenerate(@NotNull Integer expectedDraftVersion,@Size(max=1000) String reason) {}

 public record Publication(
  @NotNull Integer expectedDraftVersion,
  String mode,
  @Size(max=300) String experienceKey,
  @Size(max=200) String domain,
  String experienceType,
  UUID familyId,
  UUID expectedSupersedesId,
  @NotBlank @Size(max=2000) String reason,
  UUID relatedFamilyId,
  String problemRelation) {
   public Publication(Integer expectedDraftVersion,String mode,String experienceKey,String domain,String experienceType,UUID familyId,UUID expectedSupersedesId,String reason){this(expectedDraftVersion,mode,experienceKey,domain,experienceType,familyId,expectedSupersedesId,reason,null,null);}
  }

 public record Reject(@NotNull Integer expectedDraftVersion,@NotBlank @Size(max=2000) String reason) {}
}
