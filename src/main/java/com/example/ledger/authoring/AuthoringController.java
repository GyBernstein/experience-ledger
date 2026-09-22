package com.example.ledger.authoring;

import com.example.ledger.domain.ActorContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v2")
public class AuthoringController {
 private final AuthoringService service;
 public AuthoringController(AuthoringService service){this.service=service;}

 @PostMapping("/capture") Object capture(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody AuthoringRequests.Capture r){return service.capture(a,r);}
 @PostMapping("/capture/human") Object human(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody AuthoringRequests.Capture r){return service.captureHuman(a,r);}
 @PostMapping("/capture/agent") Object agent(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody AuthoringRequests.AgentCapture r){return service.captureAgent(a,r);}
 @GetMapping("/candidates/{id}") Object candidate(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return service.candidateView(a,id);}
 @PostMapping("/candidates/{id}/draft") Object generateDraft(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return service.generate(a,id);}
 @PostMapping("/candidates/{id}/regenerate") Object regenerateCandidate(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return service.generate(a,id);}
 @GetMapping("/drafts/{id}") Object draft(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return service.draft(a,id);}
 @PostMapping("/drafts/{id}/revise") Object revise(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody AuthoringRequests.Revision r){return service.revise(a,id,r);}
 @PostMapping("/drafts/{id}/edit") Object edit(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody AuthoringRequests.ManualEdit r){return service.edit(a,id,r);}
 @PostMapping("/drafts/{id}/regenerate") Object regenerate(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody AuthoringRequests.Regenerate r){return service.regenerate(a,id,r);}
 @PostMapping("/drafts/{id}/accept") Object accept(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody AuthoringRequests.Publication r){return service.accept(a,id,r);}
 @PostMapping("/drafts/{id}/reject") Object reject(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody AuthoringRequests.Reject r){return service.reject(a,id,r);}
 @GetMapping("/review/inbox") Object inbox(@RequestAttribute("actor") ActorContext a,@RequestParam(defaultValue="PENDING") String state,@RequestParam(required=false) String channel,@RequestParam(required=false) String domain,@RequestParam(defaultValue="50") int limit){return service.inbox(a,state,channel,domain,limit);}
 @GetMapping("/review/count") Object counts(@RequestAttribute("actor") ActorContext a){return service.counts(a);}
}
