package com.example.ledger.api;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class Requests {
 private Requests() {}
 private static JsonNode nullable(JsonNode n){return n==null||n.isNull()?null:n;}
 public record EpisodeInput(@NotBlank String title,String summary,@NotNull Instant occurredAt,Instant completedAt,
  String goal,List<String> importantObservations,List<String> toolsUsed,List<String> importantToolResults,List<String> changes,List<String> validation,String result) {}
 public record Capture(@NotBlank @Size(max=100000) String content,String candidateType,String sourceType,String sourceSystem,String sourceRef,String eventType,@Size(max=256) String dedupKey,
  JsonNode extracted,@Valid EpisodeInput episode,@Valid List<EvidenceInput> evidence) { public Capture { extracted=nullable(extracted); } }
 public record EvidenceInput(@NotBlank String evidenceType,String sourceSystem,String sourceRef,JsonNode snapshot,String snapshotUri,String contentHash,
  @NotNull Instant observedAt,@DecimalMin("0") @DecimalMax("1") Double reliability,JsonNode metadata) { public EvidenceInput {snapshot=nullable(snapshot);metadata=nullable(metadata);} }
 public record EvidenceLink(@NotNull UUID evidenceId,@NotBlank String supportType) {}
 public record ClaimInput(@NotBlank String claimType,@NotBlank String content,@NotBlank String originType,String derivationMethod,@Valid List<EvidenceLink> evidence) {}
 public record ContextInput(@NotBlank String refType,@NotBlank String refValue,String sourceSystem) {}
 public record EpisodeLink(@NotNull UUID episodeId,@NotBlank String relationType) {}
 public record Draft(@NotBlank String title,@NotBlank String summary,String problem,String decision,String action,String outcomeSummary,@NotBlank String lesson,
  @NotNull Instant validFrom,Instant validTo,JsonNode applicability,JsonNode constraints,@NotEmpty @Valid List<ClaimInput> claims,@Valid List<ContextInput> contextRefs,@Valid List<EpisodeLink> episodes) { public Draft {applicability=nullable(applicability);constraints=nullable(constraints);} }
 public record Review(@NotNull Integer expectedRevision,@NotNull @Valid Draft draft,String reason) {}
 public record Verify(@NotBlank String mode,String experienceKey,String domain,String experienceType,UUID familyId,UUID expectedSupersedesId,@NotNull Integer expectedRevision,String reason) {}
 public record Disposition(@NotNull Integer expectedRevision,String status,UUID targetVersionId,UUID targetEpisodeId,@NotBlank String reason) {}
 public record OutcomeInput(@NotBlank String outcomeType,JsonNode metrics,String notes,@NotNull Instant observedAt,UUID evidenceId) { public OutcomeInput {metrics=nullable(metrics);} }
 public record UsageInput(@NotNull UUID versionId,JsonNode queryContext,Double retrievalScore,Double applicabilityScore,boolean recommended,boolean actuallyUsed,JsonNode metadata,@Valid OutcomeInput immediateOutcome) { public UsageInput {queryContext=nullable(queryContext);metadata=nullable(metadata);} }
 public record Search(@Size(max=4000) String query,String domain,String experienceType,Instant validAt,Instant knownAt,JsonNode context,JsonNode applicabilityFilter,@Min(1) @Max(100) Integer limit) { public Search {context=nullable(context);applicabilityFilter=nullable(applicabilityFilter);} }
 public record Relation(@NotNull UUID fromVersionId,@NotNull UUID toVersionId,@NotBlank String relationType,String reason) {}
 public record Reason(@NotBlank String reason) {}
 public record Feedback(@NotBlank String content,String dedupKey) {}
 public record Supersede(@NotNull UUID candidateId,@NotNull UUID expectedSupersedesId,@NotNull Integer expectedRevision,String reason) {}
}
