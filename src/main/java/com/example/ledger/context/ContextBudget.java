package com.example.ledger.context;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Conservative byte-BPE upper bound, not a provider's measured token count. */
public final class ContextBudget {
 public static final String COUNTER="UTF8_BYTES_UPPER_BOUND_V1";
 public static int units(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
 public record Item(String key,String text,boolean negative,Set<String> evidenceIds,Set<String> versionIds,double relevance){}
 public record Selection(String text,List<Item> items,int units,List<String> gaps){}
 public static Selection select(List<Item> input,int budget,int maxItems,int maxEvidence,boolean negativeRequired){
  var sorted=new ArrayList<>(input);sorted.sort(Comparator.<Item>comparingDouble(i->-i.relevance()/Math.max(1,units(i.text()))).thenComparing(Item::key));
  List<Item> chosen=new ArrayList<>();Set<String> evidence=new HashSet<>(),versions=new HashSet<>();String text="";
  if(negativeRequired){outer:for(Item n:sorted)if(n.negative())for(Item p:sorted)if(!p.negative()&&Collections.disjoint(n.versionIds(),p.versionIds())){
   Set<String> union=new HashSet<>(n.evidenceIds());union.addAll(p.evidenceIds());String both=p.text()+"\n\n"+n.text();
   if(maxItems>=2&&union.size()<=maxEvidence&&units(both)<=budget){chosen.add(p);chosen.add(n);evidence.addAll(union);versions.addAll(p.versionIds());versions.addAll(n.versionIds());text=both;break outer;}
  }}
  if(!negativeRequired||!chosen.isEmpty())for(Item i:sorted){if(chosen.size()>=maxItems)break;if(!Collections.disjoint(versions,i.versionIds()))continue;
   Set<String> union=new HashSet<>(evidence);union.addAll(i.evidenceIds());String next=text.isEmpty()?i.text():text+"\n\n"+i.text();
   if(union.size()>maxEvidence||units(next)>budget)continue;chosen.add(i);evidence=union;versions.addAll(i.versionIds());text=next;
  }
  List<String> gaps=new ArrayList<>();if(chosen.stream().noneMatch(i->!i.negative()))gaps.add("NO_POSITIVE_CONTEXT_WITHIN_POLICY_AND_BUDGET");if(negativeRequired&&chosen.stream().noneMatch(Item::negative))gaps.add("NEGATIVE_CASE_UNAVAILABLE_OR_OVER_BUDGET");
  return new Selection(text,List.copyOf(chosen),units(text),gaps);
 }
}
