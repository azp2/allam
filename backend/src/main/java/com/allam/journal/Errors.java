package com.allam.journal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
@RestControllerAdvice
public class Errors {
 @ExceptionHandler(ApiException.class) ResponseEntity<ProblemDetail> api(ApiException e) { return problem(e.status,e.getMessage()); }
 @ExceptionHandler({MethodArgumentNotValidException.class,IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
 ResponseEntity<ProblemDetail> bad(Exception e) { return problem(HttpStatus.BAD_REQUEST,"Invalid request; check required fields and enum values"); }
 @ExceptionHandler(org.springframework.security.core.AuthenticationException.class) ResponseEntity<ProblemDetail> authentication(Exception e) { return problem(HttpStatus.UNAUTHORIZED,"Invalid credentials"); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<ProblemDetail> duplicate(Exception e) { return problem(HttpStatus.CONFLICT,"Conflicting or invalid resource reference"); }
 @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<ProblemDetail> large(Exception e) { return problem(HttpStatus.PAYLOAD_TOO_LARGE,"File exceeds upload limit"); }
 private ResponseEntity<ProblemDetail> problem(HttpStatus status,String message) { return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,message)); }
}
