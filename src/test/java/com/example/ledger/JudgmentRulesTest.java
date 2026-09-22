package com.example.ledger;
import com.example.ledger.judgment.JudgmentRules;
import com.example.ledger.judgment.JudgmentRequests.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JudgmentRulesTest {
 @Test void readilyRecoveredAnswersAreNotRetained(){assertEquals("DO_NOT_SAVE",JudgmentRules.gate(new Gate(true,true,true,"can look up" )).get("recommendation"));}
 @Test void aFutureDecisionAndReusableRuleAreBothRequired(){
  assertEquals("DO_NOT_SAVE",JudgmentRules.gate(new Gate(false,true,false,"choice" )).get("recommendation"));
  assertEquals("DO_NOT_SAVE",JudgmentRules.gate(new Gate(true,false,false,"choice" )).get("recommendation"));
  assertEquals("DO_NOT_SAVE",JudgmentRules.gate(new Gate(true,true,false," " )).get("recommendation"));
  assertEquals("KEEP_JUDGMENT",JudgmentRules.gate(new Gate(true,true,false,"ask about load before repair" )).get("recommendation"));
 }
}
