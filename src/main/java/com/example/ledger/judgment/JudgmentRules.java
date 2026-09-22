package com.example.ledger.judgment;

import com.example.ledger.api.Requests.*;
import com.example.ledger.domain.LedgerException;
import com.example.ledger.judgment.JudgmentRequests.*;
import java.util.*;

public final class JudgmentRules {
 private JudgmentRules() {}
 public static Map<String,Object> gate(Gate g) {
  var reasons=new ArrayList<String>();
  if(!g.changesFutureDecision())reasons.add("NO_FUTURE_DECISION_CHANGE");
  if(!g.reusable())reasons.add("ONE_OFF_INFORMATION");
  if(g.readilyRecoverable())reasons.add("EASILY_RECOVERED_ANSWER");
  if(g.decisionImpact()==null||g.decisionImpact().isBlank())reasons.add("DECISION_IMPACT_REQUIRED");
  return Map.of("recommendation",reasons.isEmpty()?"KEEP_JUDGMENT":"DO_NOT_SAVE","reasons",reasons,"persisted",false,"externalModelCalls",0);
 }
 public static Draft draft(Save s) {
  Rule r=s.rule();var links=(s.evidenceIds()==null?List.<UUID>of():s.evidenceIds()).stream().distinct().map(id->new EvidenceLink(id,"SUPPORTS")).toList();
  return new Draft(r.title(),r.futureDecision(),r.situation(),r.tradeoff(),r.verification(),"",r.judgment(),s.validFrom(),s.validTo(),s.applicability(),s.constraints(),
   List.of(new ClaimInput(r.negative()?"WARNING":"RULE",r.judgment(),"HUMAN_ASSERTED",null,links),
    new ClaimInput("CONSTRAINT",String.join("; ",r.hardConstraints()),"HUMAN_ASSERTED",null,List.of())),List.of(),List.of());
 }
 public static void requireMatching(Draft d,Rule r) {
  if(!d.title().equals(r.title())||!d.summary().equals(r.futureDecision())||!Objects.equals(d.problem(),r.situation())||
    !Objects.equals(d.decision(),r.tradeoff())||!Objects.equals(d.action(),r.verification())||!d.lesson().equals(r.judgment())||
    d.claims().stream().noneMatch(c->c.content().equals(String.join("; ",r.hardConstraints()))&&c.originType().equals("HUMAN_ASSERTED")&&c.claimType().equals("CONSTRAINT"))||
    d.claims().stream().noneMatch(c->c.content().equals(r.judgment())&&c.originType().equals("HUMAN_ASSERTED")&&c.claimType().equals(r.negative()?"WARNING":"RULE")))
   throw LedgerException.invalid("Judgment card and draft differ; edit through the judgment editor");
 }
 public static String text(Rule r) {
  return "Future decision: "+r.futureDecision()+"\nWhen: "+r.situation()+"\nAsk: "+String.join("; ",r.questions())+
   "\nJudgment rule: "+r.judgment()+"\nMust preserve: "+String.join("; ",r.hardConstraints())+"\nTrade-off: "+r.tradeoff()+
   "\nCheck proposed solution: "+r.verification()+"\nDo not apply when: "+r.boundaries()+"\nRevisit when: "+r.reviseWhen()+
   (r.counterexample()==null||r.counterexample().isBlank()?"":"\nCounterexample: "+r.counterexample())+
   "\nAuthor confidence (self-report, not validation): "+r.authorConfidence();
 }
}
