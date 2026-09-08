package com.example.ledger.infrastructure;
import com.example.ledger.domain.*;
import com.fasterxml.jackson.databind.*;
import org.postgresql.util.PGobject;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
@Component
public class Db {
 public final NamedParameterJdbcTemplate jdbc; public final ObjectMapper json; private final TransactionTemplate tx;
 public Db(NamedParameterJdbcTemplate jdbc,ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager) {this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);}
 public <T>T with(ActorContext actor,String reason,Supplier<T> body) {
  return tx.execute(status->{
   jdbc.getJdbcTemplate().queryForObject("select set_config('ledger.space_id',?,true)||set_config('ledger.actor_type',?,true)||set_config('ledger.actor_id',?,true)||set_config('ledger.reason',?,true)||set_config('ledger.trace_id',?,true)",String.class,
    actor.spaceId().toString(),actor.actorType().name(),actor.actorId(),reason==null?"":reason,Objects.toString(MDC.get("traceId"),"worker"));
   return body.get();
  });
 }
 public Map<String,Object> params(Object... pairs) {
  var p=new HashMap<String,Object>(); for(int i=0;i<pairs.length;i+=2) {
   Object v=pairs[i+1];if(v instanceof Instant t)v=OffsetDateTime.ofInstant(t,ZoneOffset.UTC);
   p.put((String)pairs[i],v);
  } return p;
 }
 public int update(String sql,Map<String,?> p) { return jdbc.update(sql,p); }
 public List<Map<String,Object>> list(String sql,Map<String,?> p) { return jdbc.query(sql,p,(rs,row)->{
  Map<String,Object> m=new LinkedHashMap<>(); for(int i=1;i<=rs.getMetaData().getColumnCount();i++){
   Object v=rs.getObject(i);
   if(v instanceof PGobject pg && (pg.getType().equals("jsonb")||pg.getType().equals("json"))) v=parse(pg.getValue());
   else if(v instanceof Timestamp t)v=t.toInstant().toString();
   else if(v instanceof java.sql.Array a)v=Arrays.asList((Object[])a.getArray());
   m.put(rs.getMetaData().getColumnLabel(i),v);
  } return m;
 }); }
 public Map<String,Object> one(String sql,Map<String,?> p) { var rows=list(sql,p);if(rows.isEmpty())throw new LedgerException("EXPERIENCE_NOT_FOUND","Object not found in this space",404);return rows.getFirst(); }
 public String stringify(Object v) { try{return json.writeValueAsString(v==null?Map.of():v);}catch(Exception e){throw LedgerException.invalid("Invalid JSON");} }
 public JsonNode parse(String s){try{return json.readTree(s);}catch(Exception e){throw LedgerException.invalid("Invalid JSON");}}
 public JsonNode tree(Object o){return json.valueToTree(o);}
 public UUID uuid(Map<String,Object> row,String key){return (UUID)row.get(key);}
 public Map<String,Object> scoped(ActorContext a,Object... pairs){var p=params(pairs);p.put("space",a.spaceId());p.put("actorType",a.actorType().name());p.put("actor",a.actorId());return p;}
}
