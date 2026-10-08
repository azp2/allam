package com.allam.journal;
import org.springframework.http.HttpStatus;
public class ApiException extends RuntimeException {
 final HttpStatus status;
 public ApiException(HttpStatus status,String message) { super(message); this.status=status; }
 public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,message); }
 public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN,"Access denied"); }
 public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"Resource not found"); }
 public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,message); }
}
