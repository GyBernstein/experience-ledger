package com.example.ledger.api;
import com.example.ledger.api.Requests.*;
import com.example.ledger.domain.ActorContext;
import com.example.ledger.application.LedgerService;
import com.example.ledger.retrieval.RetrievalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;
@RestController @RequestMapping("/api/v1")
public class GatewayController {
 private final LedgerService ledger;private final RetrievalService search;
 public GatewayController(LedgerService ledger,RetrievalService search){this.ledger=ledger;this.search=search;}
 @PostMapping("/candidates/capture") Object capture(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Capture r){return ledger.capture(a,r);}
 @GetMapping("/candidates") Object candidates(@RequestAttribute("actor") ActorContext a,@RequestParam(required=false) String status,@RequestParam(defaultValue="50") int limit){return ledger.candidates(a,status,limit);}
 @GetMapping("/candidates/{id}") Object candidate(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return ledger.candidate(a,id);}
 @PostMapping("/candidates/{id}/review") Object review(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Review r){return ledger.review(a,id,r);}
 @PostMapping("/candidates/{id}/verify") Object verify(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Verify r){return ledger.verify(a,id,r);}
 @PostMapping("/candidates/{id}/reject") Object reject(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Disposition r){return ledger.disposition(a,id,new Disposition(r.expectedRevision(),"REJECTED",null,null,r.reason()));}
 @PostMapping("/candidates/{id}/merge") Object merge(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Disposition r){return ledger.disposition(a,id,new Disposition(r.expectedRevision(),"MERGED",r.targetVersionId(),r.targetEpisodeId(),r.reason()));}
 @PostMapping("/candidates/{id}/disposition") Object disposition(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Disposition r){return ledger.disposition(a,id,r);}
 @PostMapping("/evidence") Object evidence(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody EvidenceInput r){return ledger.createEvidence(a,r);}
 @GetMapping("/evidence/{id}") Object evidence(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return ledger.evidence(a,id);}
 @PostMapping("/evidence/{id}/correct") Object correct(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody EvidenceInput r,@RequestParam String reason){return ledger.correctEvidence(a,id,r,reason);}
 @PostMapping("/experiences/search") Object search(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Search r){return search.search(a,r);}
 @GetMapping("/experiences/{id}") Object experience(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@RequestParam(required=false) Instant validAt,@RequestParam(required=false) Instant knownAt){return search.get(a,id,validAt,knownAt);}
 @GetMapping("/experiences/{id}/history") Object history(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return search.history(a,id);}
 @PostMapping("/experiences/{id}/similar") Object similar(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return search.similar(a,id);}
 @PostMapping("/experiences/{id}/supersede") Object supersede(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Supersede r){return ledger.supersede(a,id,r);}
 @PostMapping("/versions/{id}/invalidate") Object invalidate(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Reason r){return ledger.invalidate(a,id,r.reason());}
 @PostMapping("/experiences/{id}/feedback") Object feedback(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Feedback r){return ledger.feedback(a,id,r);}
 @PostMapping("/relations") Object relation(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Relation r){return ledger.relation(a,r);}
 @PostMapping("/usages") Object usage(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody UsageInput r){return ledger.usage(a,r);}
 @PostMapping("/usages/{id}/outcomes") Object outcome(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody OutcomeInput r){return ledger.outcome(a,id,r);}
 @GetMapping("/usages/{id}/outcomes") Object outcomes(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return ledger.outcomes(a,id);}
 @GetMapping("/audit") Object audit(@RequestAttribute("actor") ActorContext a,@RequestParam(required=false) UUID targetId,@RequestParam(defaultValue="100") int limit){return ledger.audit(a,targetId,limit);}
 @PostMapping("/candidates/{id}/retry-processing") Object retryCandidate(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return ledger.retryProcessing(a,id,true);}
 @PostMapping("/versions/{id}/retry-embedding") Object retryVersion(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return ledger.retryProcessing(a,id,false);}
}
