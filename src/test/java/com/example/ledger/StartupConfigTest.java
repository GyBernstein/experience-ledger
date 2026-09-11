package com.example.ledger;
import com.example.ledger.infrastructure.DatabaseConnectionFailureAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
class StartupConfigTest {
 @Configuration(proxyBeanMethods=false) static class Probe {}
 @Test void localProfileUsesPublishedPortForRuntimeAndFlyway(){
  try(var context=new SpringApplicationBuilder(Probe.class).web(WebApplicationType.NONE).profiles("local")
    .properties("spring.main.banner-mode=off").run("--LEDGER_LOCAL_CONFIG=classpath:/absent-local.properties")){
   var env=context.getEnvironment();
   assertEquals("jdbc:postgresql://127.0.0.1:15432/ledger",env.getProperty("spring.datasource.url"));
   assertEquals(env.getProperty("spring.datasource.url"),env.getProperty("spring.flyway.url"));
   assertEquals("ledger_app",env.getProperty("spring.datasource.username"));
   assertEquals("ledger_owner",env.getProperty("spring.flyway.user"));
  }
 }
 @Test void generatedLocalFileOverridesPortAndPreservesEscapedCredentials(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception {
  var file=dir.resolve("local.properties");var props=new java.util.Properties();
  props.setProperty("spring.datasource.url","jdbc:postgresql://127.0.0.1:25432/ledger");
  props.setProperty("spring.flyway.url","jdbc:postgresql://127.0.0.1:25432/ledger");
  props.setProperty("spring.datasource.password","test:=\\password 中文");
  try(var out=java.nio.file.Files.newOutputStream(file)){props.store(out,"isolated local config test");}
  try(var context=new SpringApplicationBuilder(Probe.class).web(WebApplicationType.NONE).profiles("local")
    .properties("spring.main.banner-mode=off").run("--LEDGER_LOCAL_CONFIG="+file.toUri())){
   assertEquals(props.getProperty("spring.datasource.url"),context.getEnvironment().getProperty("spring.datasource.url"));
   assertEquals(props.getProperty("spring.flyway.url"),context.getEnvironment().getProperty("spring.flyway.url"));
   assertEquals(props.getProperty("spring.datasource.password"),context.getEnvironment().getProperty("spring.datasource.password"));
  }
 }
 @Test void connectionFailureHasActionableAdviceWithoutCredentials(){
  var failure=new DatabaseConnectionFailureAnalyzer().analyze(new IllegalStateException(new SQLException("do-not-print-secret","08001")));
  assertNotNull(failure);assertTrue(failure.getAction().contains("start-local.ps1"));assertFalse(failure.getDescription().contains("do-not-print-secret"));
 }
 @Test void nonConnectionErrorsKeepOriginalDiagnosis(){assertNull(new DatabaseConnectionFailureAnalyzer().analyze(new SQLException("bad password","28P01")));}
}
