package com.example.ledger.api;
import com.example.ledger.domain.LedgerException;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestControllerAdvice
public class Errors {
 @ExceptionHandler(LedgerException.class) ResponseEntity<?> domain(LedgerException e){return error(e.status,e.code,e.getMessage());}
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> integrity(DataIntegrityViolationException e){
  String raw=Objects.toString(e.getMostSpecificCause().getMessage(),"");
  String code=List.of("VERSION_IMMUTABLE","EVIDENCE_IMMUTABLE","INVALID_SUPERSESSION","VERSION_CONFLICT","CANDIDATE_STATE_CONFLICT","CANDIDATE_TERMINAL","GOVERNANCE_FORBIDDEN").stream().filter(raw::contains).findFirst().orElse("CONSTRAINT_VIOLATION");
  return error(409,code,"Operation conflicts with ledger invariants");
 }
 @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception e){LoggerFactory.getLogger(Errors.class).warn("Invalid request processing, traceId={}",MDC.get("traceId"),e);return error(400,"INVALID_REQUEST","Invalid request fields or values");}
 @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e){LoggerFactory.getLogger(Errors.class).error("Unhandled request failure",e);return error(500,"INTERNAL_ERROR","Request failed; use traceId to locate the server log");}
 private ResponseEntity<?> error(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"traceId",Objects.toString(MDC.get("traceId"),""),"details",Map.of()));}
}
