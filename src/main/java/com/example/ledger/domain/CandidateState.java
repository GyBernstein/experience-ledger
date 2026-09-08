package com.example.ledger.domain;
import java.util.Set;
public enum CandidateState {
 NEW,ENRICHED,PENDING_REVIEW,VERIFIED,DUPLICATE,MERGED,REJECTED,EXPIRED;
 public boolean terminal() { return Set.of(VERIFIED,DUPLICATE,MERGED,REJECTED,EXPIRED).contains(this); }
 public void transitionTo(CandidateState next) {
  boolean ok=switch(this) {
   case NEW -> Set.of(ENRICHED,DUPLICATE,REJECTED,EXPIRED).contains(next);
   case ENRICHED -> Set.of(PENDING_REVIEW,DUPLICATE,REJECTED,EXPIRED).contains(next);
   case PENDING_REVIEW -> Set.of(VERIFIED,MERGED,REJECTED).contains(next);
   default -> false;
  };
  if(!ok) throw LedgerException.conflict("CANDIDATE_STATE_CONFLICT");
 }
}
