package com.example.ledger.context;
import tools.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.math.BigDecimal;
public final class ContextRequests {
 public record Rules(@NotEmpty Set<@NotBlank String> domains,@NotEmpty Set<@NotBlank String> taskTypes,@NotEmpty Set<@NotBlank String> allowedOrigins,
 @Min(256) @Max(20000) int maxContextTokens,@Min(0) @Max(50) int maxEvidence,@Min(1) @Max(20) int maxItems,
 @DecimalMin("0") @DecimalMax("1") double minConfidence,@DecimalMin("0") @DecimalMax("1") double minEvidenceReliability,
 @Min(1) @Max(36500) int maxAgeDays,boolean requireEvidence,boolean requireNegativeCases,boolean allowUnknownScope,boolean allowDeepSearch,
 @Min(10) @Max(200) int candidateLimit,@Min(10) @Max(500) int deepCandidateLimit){}
 public record Policy(@NotBlank @Size(max=200) String name,boolean enabled,@NotNull @Valid Rules rules,Integer expectedRevision,@NotBlank String reason){}
 public record Binding(@NotBlank String actorType,@NotBlank String actorId,@NotNull UUID policyId,boolean enabled,Integer expectedRevision,@NotBlank String reason){}
 public record Validation(@NotBlank String status,@DecimalMin("0") @DecimalMax("1") double assessedConfidence,@NotBlank String reason){}
 public record Compact(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=6000) String summary,@NotBlank String domain,@NotBlank String taskType,@NotNull UUID representativeId,@NotEmpty @Size(max=20) List<@NotNull UUID> versionIds,@NotBlank String reason){}
 public record Context(@NotBlank String domain,@NotBlank String taskType,String assetType,@Size(max=4000) String query,JsonNode context,
 @Min(256) @Max(20000) Integer maxContextTokens,@Min(0) @Max(50) Integer maxEvidence,@DecimalMin("0") @DecimalMax("1") Double minConfidence,Boolean needNegativeCases,Boolean needEvidence,Boolean deepSearch,UUID policyId){}
 public record Feedback(@NotNull UUID runId,@NotNull UUID versionId,@NotBlank @Size(max=200) String eventKey,boolean adopted,String outcomeType,@NotBlank @Size(max=10000) String evaluation,
 @Min(0) Long actualInputTokens,@Min(0) Long actualOutputTokens,@DecimalMin("0") BigDecimal reportedCost,@Size(max=10) String currency){}
 public record Review(@NotBlank String verdict,@NotBlank String reason){}
}
