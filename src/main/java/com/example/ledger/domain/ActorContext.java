package com.example.ledger.domain;
import java.util.UUID;
public record ActorContext(ActorType actorType,String actorId,UUID spaceId) {
 public enum ActorType { HUMAN, AGENT, SYSTEM, TRUSTED_WORKFLOW }
 public ActorContext { if(actorType==null || actorId==null || actorId.isBlank() || spaceId==null) throw new IllegalArgumentException("Actor identity required"); }
 public void requireGovernance() { if(actorType!=ActorType.HUMAN && actorType!=ActorType.TRUSTED_WORKFLOW) throw new LedgerException("GOVERNANCE_FORBIDDEN","Human or trusted workflow required",403); }
}
