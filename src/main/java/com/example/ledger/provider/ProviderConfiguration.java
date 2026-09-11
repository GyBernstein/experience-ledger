package com.example.ledger.provider;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
@Configuration
public class ProviderConfiguration {
 @Bean @ConditionalOnMissingBean ExperienceEnrichmentProvider enrichmentProvider(){return new NoopEnrichmentProvider();}
 @Bean @ConditionalOnMissingBean EmbeddingProvider embeddingProvider(ObjectMapper json,
  @Value("${ledger.embedding.provider}") String type,@Value("${ledger.embedding.url}") String url,
  @Value("${ledger.embedding.token}") String token,@Value("${ledger.embedding.model}") String model,@Value("${ledger.embedding.timeout-seconds}") int timeout){
  if(type.equals("disabled"))return text->null;
  if(!type.equals("http") || url.isBlank() || model.isBlank())throw new IllegalArgumentException("HTTP embedding requires URL and model");
  HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout)).followRedirects(HttpClient.Redirect.NEVER).build();
  return text->{
   var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json");
   if(!token.isBlank())builder.header("Authorization","Bearer "+token);
   var request=builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text",text,"model",model)))).build();
   var response=client.send(request,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200)throw new IllegalStateException("Embedding HTTP status "+response.statusCode());
   var body=json.readTree(response.body());String returnedModel=body.path("model").asText();
   if(!model.equals(returnedModel))throw new IllegalStateException("Embedding model mismatch");
   float[] values=json.convertValue(body.get("vector"),float[].class);return new EmbeddingProvider.Result(values,returnedModel);
  };
 }
}
