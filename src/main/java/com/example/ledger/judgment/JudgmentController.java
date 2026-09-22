package com.example.ledger.judgment;
import com.example.ledger.judgment.JudgmentRequests.*;
import com.example.ledger.domain.ActorContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v2")
public class JudgmentController {
 private final JudgmentService service;
 public JudgmentController(JudgmentService service){this.service=service;}
 @PostMapping("/judgments/gate") Object gate(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Gate r){return service.gate(a,r);}
 @PostMapping("/judgments/candidates") Object save(@RequestAttribute("actor") ActorContext a,@Valid @RequestBody Save r){return service.save(a,null,r);}
 @PostMapping("/judgments/candidates/{id}") Object edit(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Save r){return service.save(a,id,r);}
 @PostMapping("/judgments/candidates/{id}/publish") Object publish(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Publish r){return service.publish(a,id,r);}
 @GetMapping("/judgments") Object list(@RequestAttribute("actor") ActorContext a,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String domain,@RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int offset){return service.list(a,query,domain,limit,offset);}
 @GetMapping("/judgments/{id}") Object detail(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return service.detail(a,id);}
 @GetMapping("/versions/{id}/reuse") Object history(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id){return service.history(a,id);}
 @PostMapping("/versions/{id}/reuse") Object reuse(@RequestAttribute("actor") ActorContext a,@PathVariable UUID id,@Valid @RequestBody Reuse r){return service.reuse(a,id,r);}
}
