package com.example.ledger.retrieval;
import com.example.ledger.api.Requests.Search;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.provider.EmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
@Service
public class RetrievalService {
 private final Db db;private final EmbeddingProvider embedding;private final MeterRegistry metrics;
 private final ConfidenceCalculator confidence=new ConfidenceCalculator();
 private final int pool;private final double lexicalWeight,semanticWeight,contextWeight,applicabilityWeight,confidenceWeight;
 public RetrievalService(Db db,EmbeddingProvider embedding,MeterRegistry metrics,
  @Value("${ledger.retrieval.pool-size}") int pool,@Value("${ledger.retrieval.lexical-weight}") double lexical,
  @Value("${ledger.retrieval.semantic-weight}") double semantic,@Value("${ledger.retrieval.context-weight}") double context,
  @Value("${ledger.retrieval.applicability-weight}") double applicability,@Value("${ledger.retrieval.confidence-weight}") double confidence){
  this.db=db;this.embedding=embedding;this.metrics=metrics;this.pool=pool;lexicalWeight=lexical;semanticWeight=semantic;contextWeight=context;applicabilityWeight=applicability;confidenceWeight=confidence;
 }
 private static final String FILTER="""
  v.space_id=:space and f.space_id=:space
  and (cast(:domain as text) is null or f.domain=:domain)
  and (cast(:type as text) is null or f.experience_type=:type)
  and v.valid_from<=:valid and (v.valid_to is null or :valid<v.valid_to)
  and v.recorded_at<=:known and (v.invalidated_at is null or :known<v.invalidated_at)
  and v.applicability_json @> cast(:filter as jsonb)
  """;
 private static final String FROM=" from exp_experience_version v join exp_experience_family f on f.id=v.family_id and f.space_id=v.space_id ";
 public Map<String,Object> search(ActorContext a,Search r){
  var timer=io.micrometer.core.instrument.Timer.start(metrics);EmbeddingProvider.Result vector=null;String mode="FTS_METADATA";
  String query=r.query()==null?"":r.query().strip();
  if(!query.isBlank())try{vector=embedding.embed(query);if(vector!=null)mode="HYBRID";}catch(Exception e){metrics.counter("ledger.embedding.failures","operation","query").increment();mode="FTS_METADATA_FALLBACK";}
  final var vec=vector;final String retrievalMode=mode;
  try{return db.with(a,null,()->{
   var p=parameters(a,r);p.put("query",query);p.put("pool",pool);p.put("model",vec==null?null:vec.model());
   p.put("vector",vec==null?null:EmbeddingProvider.literal(vec));
   String tq="replace(plainto_tsquery('simple',ledger_fts_text(:query))::text,' & ',' | ')::tsquery";
   Map<UUID,Map<String,Object>> candidates=new LinkedHashMap<>();
   if(!query.isBlank()){
    for(var row:db.list("select v.*,ts_rank_cd(v.search_vector,"+tq+",32) as lexical_score"+FROM+" where "+FILTER+" and v.search_vector @@ "+tq+" order by lexical_score desc,v.id limit :pool",p)) candidates.put((UUID)row.get("id"),row);
    if(vec!=null)for(var row:db.list("select v.*,greatest(0,1-(v.embedding <=> cast(:vector as vector))) as semantic_score"+FROM+" where "+FILTER+" and v.embedding_status='READY' and v.embedding_model=:model order by v.embedding <=> cast(:vector as vector),v.id limit :pool",p)){
     UUID id=(UUID)row.get("id");candidates.computeIfAbsent(id,k->row).put("semantic_score",row.get("semantic_score"));
    }
   }else for(var row:db.list("select v.*"+FROM+" where "+FILTER+" order by v.recorded_at desc,v.id limit :pool",p))candidates.put((UUID)row.get("id"),row);
   // Metadata candidate lane allows a supplied context to find useful entries without lexical overlap.
   if(r.context()!=null && r.context().isObject() && !r.context().isEmpty()){
    p.put("context",db.stringify(r.context()));
    for(var row:db.list("select distinct v.*"+FROM+" join exp_context_ref cr on cr.experience_version_id=v.id and cr.space_id=v.space_id where "+FILTER+" and cast(:context as jsonb)->>cr.ref_type=cr.ref_value order by v.id limit :pool",p))candidates.putIfAbsent((UUID)row.get("id"),row);
   }
   List<Map<String,Object>> results=new ArrayList<>();
   for(var row:candidates.values()){
    var app=Applicability.evaluate((JsonNode)row.get("applicability_json"),r.context());
    var packageRow=pack(a,row);
    double contextScore=contextScore(packageRow,r.context());
    double lex=number(row,"lexical_score"),sem=number(row,"semantic_score");
    @SuppressWarnings("unchecked") var stats=(Map<String,Object>)packageRow.get("usageStats");
    double conf=confidence.calculate(stats);
    double score=lexicalWeight*lex+semanticWeight*sem+contextWeight*contextScore+applicabilityWeight*app.score()+confidenceWeight*conf;
    packageRow.put("score",score);packageRow.put("whyMatched",Map.of("lexical",lex,"semantic",sem,"context",contextScore,"applicability",app,"confidenceAdjustment",confidenceWeight*conf,"weights",Map.of("lexical",lexicalWeight,"semantic",semanticWeight,"context",contextWeight,"applicability",applicabilityWeight,"confidence",confidenceWeight)));
    results.add(packageRow);
   }
   results.sort(Comparator.<Map<String,Object>>comparingDouble(m->-((Number)m.get("score")).doubleValue()).thenComparing(m->m.get("versionId").toString()));
   int limit=r.limit()==null?10:r.limit();return Map.of("mode",retrievalMode,"results",results.stream().limit(limit).toList(),"candidateCount",candidates.size(),"candidatePoolPerLane",pool,"validAt",p.get("valid").toString(),"knownAt",p.get("known").toString());
  });}finally{timer.stop(metrics.timer("ledger.search.latency"));}
 }
 private Map<String,Object> parameters(ActorContext a,Search r){Instant now=Instant.now();return db.scoped(a,"domain",r.domain(),"type",r.experienceType(),"valid",r.validAt()==null?now:r.validAt(),"known",r.knownAt()==null?now:r.knownAt(),"filter",db.stringify(r.applicabilityFilter()));}
 public Map<String,Object> get(ActorContext a,UUID family,Instant validAt,Instant knownAt){return db.with(a,null,()->{
  var p=parameters(a,new Search(null,null,null,validAt,knownAt,null,null,1));p.put("family",family);
  var row=db.one("select v.*"+FROM+" where "+FILTER+" and v.family_id=:family",p);return pack(a,row);
 });}
 public List<Map<String,Object>> history(ActorContext a,UUID family){return db.with(a,null,()->{
  db.one("select id from exp_experience_family where space_id=:space and id=:id",db.scoped(a,"id",family));
  return db.list("select * from exp_experience_version where space_id=:space and family_id=:id order by version_no",db.scoped(a,"id",family)).stream().map(row->pack(a,row)).toList();
 });}
 public Map<String,Object> similar(ActorContext a,UUID family){var current=get(a,family,null,null);
  var r=search(a,new Search((String)current.get("title"),null,null,null,null,null,null,11));
  @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)r.get("results");var out=new LinkedHashMap<>(r);out.put("results",rows.stream().filter(x->!family.equals(x.get("experienceId"))).limit(10).toList());return out;
 }
 private Map<String,Object> pack(ActorContext a,Map<String,Object> row){
  UUID id=(UUID)row.get("id");var p=db.scoped(a,"id",id);
  var claims=db.list("select * from exp_experience_claim where space_id=:space and experience_version_id=:id order by sequence_no",p);
  var evidence=db.list("select distinct e.* from exp_evidence e join exp_claim_evidence ce on ce.evidence_id=e.id and ce.space_id=e.space_id join exp_experience_claim c on c.id=ce.claim_id and c.space_id=ce.space_id where c.space_id=:space and c.experience_version_id=:id",p);
  for(var c:claims)c.put("evidenceLinks",db.list("select evidence_id,support_type from exp_claim_evidence where space_id=:space and claim_id=:claim",db.scoped(a,"claim",c.get("id"))));
  var stats=db.one("select * from exp_version_stats where space_id=:space and version_id=:id",p);
  Map<String,Object> result=new LinkedHashMap<>();result.put("experienceId",row.get("family_id"));result.put("versionId",id);
  result.put("versionNo",row.get("version_no"));result.put("title",row.get("title"));result.put("summary",row.get("summary"));result.put("lesson",row.get("lesson"));
  result.put("problem",row.get("problem"));result.put("decision",row.get("decision"));result.put("action",row.get("action"));result.put("outcomeSummary",row.get("outcome_summary"));
  result.put("status",row.get("status"));result.put("validFrom",row.get("valid_from"));result.put("validTo",row.get("valid_to"));result.put("recordedAt",row.get("recorded_at"));result.put("invalidatedAt",row.get("invalidated_at"));result.put("supersedesId",row.get("supersedes_id"));
  result.put("applicability",row.get("applicability_json"));result.put("constraints",row.get("constraints_json"));result.put("keyClaims",claims);result.put("keyEvidence",evidence);
  result.put("contextRefs",db.list("select ref_type,ref_value,source_system from exp_context_ref where space_id=:space and experience_version_id=:id",p));
  result.put("episodes",db.list("select e.*,link.relation_type from exp_episode e join exp_experience_episode link on link.episode_id=e.id and link.space_id=e.space_id where link.space_id=:space and link.experience_version_id=:id",p));
  result.put("usageStats",stats);result.put("outcomeStats",Map.of("success",stats.get("success_count"),"partialSuccess",stats.get("partial_success_count"),"failure",stats.get("failure_count"),"countingUnit","OUTCOME_EVENT"));
  result.put("confidenceSummary",Map.of("derived",true,"score",confidence.calculate(stats),"algorithm","smoothed-outcome-summary-v1","humanVerified",stats.get("human_verified")));
  result.put("lifecycleStatusAsOf","CURRENT");result.put("statisticsAsOf","CURRENT");result.put("evidenceLifecycleAsOf","CURRENT");
  result.put("contradictionWarnings",db.list("select * from exp_relation where space_id=:space and relation_type='CONTRADICTS' and (from_version_id=:id or to_version_id=:id)",p));
  result.put("mergedCandidates",db.list("select id,status,episode_id from exp_candidate where space_id=:space and target_version_id=:id and status in ('MERGED','DUPLICATE')",p));
  return result;
 }
 private double contextScore(Map<String,Object> pack,JsonNode context){if(context==null)return 0;@SuppressWarnings("unchecked") var refs=(List<Map<String,Object>>)pack.get("contextRefs");if(refs.isEmpty())return 0;long matches=refs.stream().filter(r->context.path((String)r.get("ref_type")).asText().equals(r.get("ref_value"))).count();return matches/(double)refs.size();}
 private double number(Map<String,Object> row,String key){Object n=row.get(key);return n instanceof Number v?v.doubleValue():0;}
}
