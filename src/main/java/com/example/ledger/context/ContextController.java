package com.example.ledger.context;
import com.example.ledger.context.ContextRequests.*;
import com.example.ledger.api.Requests.Reason;
import com.example.ledger.domain.ActorContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v2")
public class ContextController {
 private final ContextGovernance governance;private final ContextService service;private final ContextFeedback feedback;
 public ContextController(ContextGovernance g,ContextService s,ContextFeedback f){governance=g;service=s;feedback=f;}
 @GetMapping("/me") Object me(@RequestAttribute("actor") ActorContext a){return governance.me(a);}
 @GetMapping("/policies") Object policies(@RequestAttribute("actor") ActorContext a){return governance.policies(a);}
 @PostMapping("/policies") Object policy(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Policy r){return governance.policy(a,null,r);}
 @PostMapping("/policies/{id}") Object policy(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Policy r){return governance.policy(a,id,r);}
 @GetMapping("/bindings") Object bindings(@RequestAttribute("actor") ActorContext a){return governance.bindings(a);}
 @PostMapping("/bindings") Object binding(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Binding r){return governance.bind(a,r);}
 @GetMapping("/versions/{id}/validations") Object validations(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return governance.validations(a,id);}
 @PostMapping("/versions/{id}/validations") Object validation(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Validation r){return governance.validate(a,id,r);}
 @GetMapping("/compacts") Object compacts(@RequestAttribute("actor") ActorContext a){return governance.compacts(a);}
 @PostMapping("/compacts") Object compact(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Compact r){return governance.createCompact(a,r);}
 @PostMapping("/compacts/{id}/approve") Object approve(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Reason r){return governance.compactState(a,id,true,r.reason());}
 @PostMapping("/compacts/{id}/retire") Object retire(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Reason r){return governance.compactState(a,id,false,r.reason());}
 @PostMapping("/context") Object context(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Context r){return service.context(a,r);}
 @GetMapping("/evidence/{id}") Object evidence(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@RequestParam UUID runId){return service.evidence(a,id,runId);}
 @PostMapping("/feedback") Object feedback(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Feedback r){return feedback.feedback(a,r);}
 @PostMapping("/feedback/{id}/review") Object review(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Review r){return feedback.review(a,id,r);}
 @GetMapping("/operations") Object operations(@RequestAttribute("actor") ActorContext a){return feedback.operations(a);}
}
