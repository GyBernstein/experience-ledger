package com.example.ledger.domain;
public class LedgerException extends RuntimeException {
 public final String code; public final int status;
 public LedgerException(String code,String message,int status) { super(message);this.code=code;this.status=status; }
 public static LedgerException conflict(String code) { return new LedgerException(code,code,409); }
 public static LedgerException invalid(String message) { return new LedgerException("INVALID_REQUEST",message,400); }
}
