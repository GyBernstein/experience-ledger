package com.example.ledger.domain;
import java.util.Map;
public class ConfidenceCalculator {
 // Smoothed outcome summary, never an assertion of truth. Outcome events are not independent trials.
 public double calculate(Map<String,Object> stats) {
  double s=((Number)stats.getOrDefault("success_count",0)).doubleValue();
  double p=((Number)stats.getOrDefault("partial_success_count",0)).doubleValue();
  double f=((Number)stats.getOrDefault("failure_count",0)).doubleValue();
  return (1+s+0.5*p)/(2+s+p+f);
 }
}
