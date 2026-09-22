package com.example.ledger.judgment;

import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class JudgmentRequests {
 private JudgmentRequests() {}
 /** Final decision aids only: no hidden reasoning trace and no answer transcript. */
 public record Rule(
  @NotBlank @Size(max=200) String title,
  @NotBlank @Size(max=1200) String futureDecision,
  @NotBlank @Size(max=1200) String situation,
  @NotEmpty @Size(max=10) List<@NotBlank @Size(max=400) String> questions,
  @NotBlank @Size(max=1800) String judgment,
  @NotEmpty @Size(max=10) List<@NotBlank @Size(max=400) String> hardConstraints,
  @NotBlank @Size(max=1200) String tradeoff,
  @NotBlank @Size(max=1200) String verification,
  @NotBlank @Size(max=1200) String boundaries,
  @NotBlank @Size(max=1000) String reviseWhen,
  @Size(max=1200) String counterexample,
  @DecimalMin("0") @DecimalMax("1") double authorConfidence,
  boolean negative) {}
 public record Gate(boolean changesFutureDecision,boolean reusable,boolean readilyRecoverable,
  @Size(max=1200) String decisionImpact) {}
 public record Save(@NotNull @Valid Rule rule,@NotNull @Valid Gate gate,
  @NotBlank @Size(max=100) String domain,@NotBlank @Size(max=200) String experienceKey,
  @NotNull Instant validFrom,Instant validTo,JsonNode applicability,JsonNode constraints,
  @Size(max=20) List<@NotNull UUID> evidenceIds,@Size(max=200) String eventKey,Integer expectedRevision,UUID sourceFamilyId,UUID sourceVersionId) {}
 public record Publish(@NotNull Integer expectedRevision,@NotBlank @Size(max=2000) String reason,
  UUID familyId,UUID expectedSupersedesId) {}
 public record Reuse(@NotBlank String targetTrack,@NotBlank String mode,@NotBlank String validationMethod,
  @NotBlank @Size(max=2000) String reason,@Size(max=20) List<@NotNull UUID> evidenceIds,UUID expectedPreviousId) {}
}
