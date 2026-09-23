package com.example.ledger.authoring;

import com.example.ledger.domain.ActorContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v2/problem-groups")
public class ProblemGroupController {
 private final ProblemGroupService service;
 public ProblemGroupController(ProblemGroupService service){this.service=service;}
 public record Link(@NotNull UUID familyId,@NotBlank String relation,@NotBlank @Size(max=2000) String reason){}
 public record Attach(@NotNull UUID familyId,@NotNull UUID referenceFamilyId,@NotBlank String relation,@NotBlank @Size(max=2000) String reason){}
 public record Unlink(@NotBlank @Size(max=2000) String reason){}
 @GetMapping("/by-family/{familyId}") Object byFamily(@RequestAttribute("actor") ActorContext a,@PathVariable UUID familyId){a.requireGovernance();return service.byFamily(a,familyId);}
 @GetMapping("/suggestions/{familyId}") Object suggestions(@RequestAttribute("actor") ActorContext a,@PathVariable UUID familyId){return service.suggestForFamily(a,familyId);}
 @PostMapping("/attach") Object attach(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Attach r){return service.attachFamily(a,r.familyId(),r.referenceFamilyId(),r.relation(),r.reason());}
 @GetMapping("/{id}") Object detail(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){a.requireGovernance();return service.detail(a,id);}
 @PostMapping("/{id}/members") Object link(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Link r){return service.linkExisting(a,id,r.familyId(),r.relation(),r.reason());}
 @PostMapping("/{id}/members/{familyId}/unlink") Object unlink(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@PathVariable UUID familyId,@Valid @RequestBody Unlink r){return service.unlink(a,id,familyId,r.reason());}
}
