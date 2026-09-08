package com.example.ledger.application;
import com.example.ledger.domain.*;
import com.example.ledger.infrastructure.Db;
import com.example.ledger.provider.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
@Component
public class ProcessingWorker {
 private final Db db;private final EmbeddingProvider embedding;private final ExperienceEnrichmentProvider enrichment;private final MeterRegistry metrics;
 private final boolean enabled;private final List<UUID> spaces;private final int maxAttempts,lease;
 public ProcessingWorker(Db db,EmbeddingProvider embedding,ExperienceEnrichmentProvider enrichment,MeterRegistry metrics,
  @Value("${ledger.worker.enabled}") boolean enabled,@Value("${ledger.worker.spaces}") String spaces,
  @Value("${ledger.worker.max-attempts}") int maxAttempts,@Value("${ledger.worker.lease-seconds}") int lease){
  this.db=db;this.embedding=embedding;this.enrichment=enrichment;this.metrics=metrics;this.enabled=enabled;
  this.spaces=Arrays.stream(spaces.split(",")).map(String::strip).filter(s->!s.isBlank()).map(UUID::fromString).toList();this.maxAttempts=maxAttempts;this.lease=lease;
 }
 @Scheduled(fixedDelayString="${ledger.worker.delay-ms}") public void poll(){if(enabled)for(UUID space:spaces)try{processOne(space);}catch(Exception e){metrics.counter("ledger.candidate.processing.failures").increment();org.slf4j.LoggerFactory.getLogger(getClass()).error("Worker failure space={}",space,e);}}
 public boolean processOne(UUID space){
  var a=new ActorContext(ActorContext.ActorType.SYSTEM,"ledger-processing-worker",space);
  var job=db.with(a,"claim processing job",()->{
   var jobs=db.list("""
    select * from processing_job where space_id=:space and ((status='PENDING' and available_at<=clock_timestamp()) or (status='RUNNING' and lease_until<clock_timestamp()))
    order by available_at,id for update skip locked limit 1
    """,db.scoped(a));
   if(jobs.isEmpty())return null;var j=jobs.getFirst();int attempt=((Number)j.get("attempts")).intValue()+1;
   db.update("update processing_job set status='RUNNING',attempts=:attempt,lease_until=clock_timestamp()+make_interval(secs=>:lease) where space_id=:space and id=:id",db.scoped(a,"id",j.get("id"),"attempt",attempt,"lease",lease));j.put("attempts",attempt);return j;
  });
  if(job==null)return false;
  UUID id=(UUID)job.get("target_id");boolean candidate=job.get("target_type").equals("CANDIDATE");String table=candidate?"exp_candidate":"exp_experience_version";
  var row=db.with(a,null,()->db.one("select * from "+table+" where space_id=:space and id=:id",db.scoped(a,"id",id)));
  if(candidate && CandidateState.valueOf((String)row.get("status")).terminal()){finish(a,job,null);return true;}
  JsonNode extracted=candidate?(JsonNode)row.get("extracted_json"):null;String failure=null;
  if(candidate && (row.get("status").equals("NEW") || Objects.toString(row.get("processing_error"),"").contains("ENRICHMENT_FAILED")))try{
   JsonNode suggested=enrichment.enrich(new ExperienceEnrichmentProvider.Candidate((String)row.get("raw_content"),extracted),new ExperienceEnrichmentProvider.CaptureContext(a));
   if(suggested==null || !suggested.isObject())throw new IllegalArgumentException("Enrichment must return an object");
   ClaimRules.preserveOrigin(extracted,suggested);
   // Provider output is a suggestion; originals and human draft are retained unchanged.
   var copy=extracted.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy).set("enrichmentSuggestion",suggested);extracted=copy;
  }catch(Exception e){failure="ENRICHMENT_FAILED";metrics.counter("ledger.candidate.processing.failures").increment();}
  EmbeddingProvider.Result result=null;String embeddingStatus;
  try{result=embedding.embed((String)row.get(candidate?"raw_content":"retrieval_text"));embeddingStatus=result==null?"DISABLED":"READY";}
  catch(Exception e){embeddingStatus="FAILED";failure=failure==null?"EMBEDDING_FAILED":failure+";EMBEDDING_FAILED";metrics.counter("ledger.embedding.failures","operation","background").increment();}
  final var extractedFinal=extracted;final var resultFinal=result;final var error=failure;final String state=embeddingStatus;
  db.with(a,"process candidate and embedding",()->{
   var owned=db.list("select id from processing_job where space_id=:space and id=:job and status='RUNNING' and attempts=:attempt for update",db.scoped(a,"job",job.get("id"),"attempt",job.get("attempts")));
   if(owned.isEmpty())return null;
   var current=db.one("select * from "+table+" where space_id=:space and id=:id for update",db.scoped(a,"id",id));
   if(candidate && (CandidateState.valueOf((String)current.get("status")).terminal() || !current.get("revision").equals(row.get("revision")))){finishInternal(a,job,null);return null;}
   var p=db.scoped(a,"id",id,"vector",resultFinal==null?null:EmbeddingProvider.literal(resultFinal),"state",state,"model",resultFinal==null?null:resultFinal.model());
   db.update("update "+table+" set embedding=cast(:vector as vector),embedding_status=:state,embedding_model=:model where space_id=:space and id=:id",p);
   if(candidate){
    p.put("extracted",db.stringify(extractedFinal));p.put("processing",error==null?"READY":"FAILED");p.put("error",error);
    db.update("update exp_candidate set extracted_json=cast(:extracted as jsonb),processing_status=:processing,processing_error=:error where space_id=:space and id=:id",p);
    if(current.get("status").equals("NEW"))db.update("update exp_candidate set status='ENRICHED' where space_id=:space and id=:id",p);
    if(Set.of("NEW","ENRICHED").contains(current.get("status")))db.update("update exp_candidate set status='PENDING_REVIEW' where space_id=:space and id=:id",p);
    // Similarity is a suggestion only, never automatic DUPLICATE or VERIFIED.
    var similar=db.list("""
     select distinct family_id from exp_experience_version where space_id=:space and status='VERIFIED' and invalidated_at is null
      and valid_from<=clock_timestamp() and (valid_to is null or clock_timestamp()<valid_to)
      and search_vector @@ replace(plainto_tsquery('simple',ledger_fts_text(:text))::text,' & ',' | ')::tsquery limit 5
     """,db.scoped(a,"text",row.get("raw_content")));
    String ids="{"+String.join(",",similar.stream().map(s->s.get("family_id").toString()).toList())+"}";
    db.update("update exp_candidate set similar_family_ids=cast(:ids as uuid[]) where space_id=:space and id=:id",db.scoped(a,"id",id,"ids",ids));
   }
   finishInternal(a,job,error);return null;
  });return true;
 }
 private void finish(ActorContext a,Map<String,Object> job,String error){db.with(a,"finish job",()->{finishInternal(a,job,error);return null;});}
 private void finishInternal(ActorContext a,Map<String,Object> job,String error){int attempts=((Number)job.get("attempts")).intValue();
  String status=error==null?"DONE":attempts>=maxAttempts?"FAILED":"PENDING";
  db.update("update processing_job set status=:status,last_error=:error,lease_until=null,available_at=:next where space_id=:space and id=:id and attempts=:attempt and status='RUNNING'",
   db.scoped(a,"id",job.get("id"),"status",status,"error",error,"attempt",attempts,"next",Instant.now().plusSeconds(Math.min(300,10L*attempts))));
 }
}
