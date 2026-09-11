package com.example.ledger.infrastructure;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import java.sql.SQLException;
/** Explain database bootstrap failures without printing connection secrets. */
public class DatabaseConnectionFailureAnalyzer extends AbstractFailureAnalyzer<SQLException> {
 @Override protected FailureAnalysis analyze(Throwable rootFailure,SQLException cause){
  String state=cause.getSQLState();
  if(state==null||!state.startsWith("08"))return null;
  return new FailureAnalysis(
   "PostgreSQL connection could not be established (SQLSTATE "+state+"). Flyway must connect before the application can start.",
   "For Windows/IDE development: run scripts/start-local.ps1 -DatabaseOnly, activate profile 'local', and use the project root as working directory. "
   +"The local database is published on 127.0.0.1:15432 by default. For an existing database, configure DB_URL, DB_USER, DB_PASSWORD, DB_MIGRATION_USER and DB_MIGRATION_PASSWORD. "
   +"Check database availability and port mapping; do not disable Flyway. See docs/spring-boot-4-upgrade.md.",cause);
 }
}
