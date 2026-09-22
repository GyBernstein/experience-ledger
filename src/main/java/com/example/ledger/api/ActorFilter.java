package com.example.ledger.api;
import com.example.ledger.domain.ActorContext;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
@Component
public class ActorFilter extends OncePerRequestFilter {
 private record Principal(String token,ActorContext.ActorType actorType,String actorId,UUID spaceId){}
 private final List<Principal> principals;private final ObjectMapper json;
 private static final Logger log=LoggerFactory.getLogger(ActorFilter.class);
 public ActorFilter(ObjectMapper json,@Value("${ledger.security.principals}") String config) throws IOException {
  this.json=json;principals=json.readValue(config,json.getTypeFactory().constructCollectionType(List.class,Principal.class));
  Set<String> tokens=new HashSet<>();for(var p:principals){if(p.token()==null||p.token().length()<16||!tokens.add(p.token()))throw new IllegalArgumentException("Principal tokens must be unique and at least 16 characters");new ActorContext(p.actorType(),p.actorId(),p.spaceId());}
 }
 @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  String trace=UUID.randomUUID().toString();MDC.put("traceId",trace);res.setHeader("X-Trace-Id",trace);long start=System.nanoTime();
  try{
   if(req.getRequestURI().equals("/actuator/health")){chain.doFilter(req,res);return;}
   String auth=req.getHeader("Authorization");String token=auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):"";
   Principal found=null;for(var p:principals)if(MessageDigest.isEqual(p.token().getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))found=p;
   if(found==null){res.setStatus(401);res.setContentType("application/json");json.writeValue(res.getWriter(),Map.of("code","UNAUTHORIZED","message","Valid bearer token required","traceId",trace,"details",Map.of()));return;}
   var actor=new ActorContext(found.actorType(),found.actorId(),found.spaceId());
   if(req.getRequestURI().startsWith("/actuator/") && actor.actorType()!=ActorContext.ActorType.HUMAN && actor.actorType()!=ActorContext.ActorType.TRUSTED_WORKFLOW){res.setStatus(403);return;}
   if(actor.actorType()==ActorContext.ActorType.AGENT||actor.actorType()==ActorContext.ActorType.SYSTEM){
    String path=req.getRequestURI();boolean allowed=(req.getMethod().equals("POST")&&Set.of("/api/v1/candidates/capture","/api/v2/capture","/api/v2/capture/agent","/api/v2/context","/api/v2/feedback").contains(path))||(req.getMethod().equals("GET")&&(path.equals("/api/v2/me")||path.matches("/api/v2/evidence/[0-9a-fA-F-]{36}")));
    if(!allowed){res.setStatus(403);res.setContentType("application/json");json.writeValue(res.getWriter(),Map.of("code","AGENT_GATEWAY_REQUIRED","message","Use policy-controlled V2 endpoints","traceId",trace));return;}
   }
   req.setAttribute("actor",actor);
   if(req.getContentLengthLong()>1_048_576){res.setStatus(413);return;}
   chain.doFilter(req,res);
  }finally{log.info("request method={} path={} status={} durationMs={}",req.getMethod(),req.getRequestURI(),res.getStatus(),(System.nanoTime()-start)/1_000_000);MDC.clear();}
 }
}
