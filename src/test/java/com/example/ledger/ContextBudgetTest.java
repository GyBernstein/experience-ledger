package com.example.ledger;
import com.example.ledger.context.ContextBudget;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContextBudgetTest {
 ContextBudget.Item item(String id,String text,boolean negative,String evidence){return new ContextBudget.Item(id,text,negative,Set.of(evidence),Set.of(id),1);}
 @Test void unicodeBudget(){assertEquals(6,ContextBudget.units("水泵"));assertTrue(ContextBudget.select(List.of(item("1","水泵",false,"e")),5,2,2,false).items().isEmpty());}
 @Test void jointPositiveAndNegative(){var r=ContextBudget.select(List.of(item("1","positive",false,"a"),item("2","negative",true,"b")),18,2,2,true);assertEquals(18,r.units());assertEquals(2,r.items().size());assertTrue(r.gaps().isEmpty());}
 @Test void evidenceBudgetCannotBeRelaxed(){var r=ContextBudget.select(List.of(item("1","positive",false,"a"),item("2","negative",true,"b")),18,2,1,true);assertTrue(r.items().isEmpty());assertFalse(r.gaps().isEmpty());}
 @Test void deduplicateCompactSources(){var a=item("1","a",false,"e");var b=new ContextBudget.Item("compact","b",false,Set.of("e"),Set.of("1"),2);assertEquals(1,ContextBudget.select(List.of(a,b),100,3,5,false).items().size());}
}
