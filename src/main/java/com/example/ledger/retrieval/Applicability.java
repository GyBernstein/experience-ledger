package com.example.ledger.retrieval;
import tools.jackson.databind.JsonNode;
import java.util.*;
public final class Applicability {
 private Applicability(){}
 public record Match(double score,List<String> matched,List<String> mismatched,List<String> unknown){}
 public static Match evaluate(JsonNode rules,JsonNode context){
  List<String> yes=new ArrayList<>(),no=new ArrayList<>(),unknown=new ArrayList<>();
  if(rules!=null && rules.isObject())rules.properties().forEach(e->{
   String key=e.getKey();JsonNode expected=e.getValue();String lookup=key;
   boolean min=key.endsWith("Min") && expected.isNumber(),max=key.endsWith("Max") && expected.isNumber();
   if(min||max)lookup=key.substring(0,key.length()-3);
   JsonNode actual=context==null?null:context.get(lookup);
   if(actual==null || actual.isNull()){unknown.add(key);return;}
   boolean matches;
   if(min||max) matches=actual.isNumber()&&(min?actual.asDouble()>=expected.asDouble():actual.asDouble()<=expected.asDouble());
   else if(expected.isArray()) {matches=false;for(JsonNode allowed:expected)if(allowed.equals(actual))matches=true;}
   else matches=expected.equals(actual);
   (matches?yes:no).add(key);
  });
  int total=yes.size()+no.size()+unknown.size();return new Match(total==0?0:(yes.size()-no.size())/(double)total,yes,no,unknown);
 }
}
