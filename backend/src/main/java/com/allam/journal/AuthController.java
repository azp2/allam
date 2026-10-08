package com.allam.journal;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
 final AuthenticationManager authentication;
 public AuthController(AuthenticationManager authentication) {this.authentication=authentication;}
 record LoginInput(@NotBlank @Email String email,@NotBlank @Size(max=72) String password) {}
 @PostMapping("/login") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void login(@Valid @RequestBody LoginInput input,HttpServletRequest request,HttpServletResponse response) {
  Authentication result=authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.email().toLowerCase(java.util.Locale.ROOT),input.password()));
  if(request.getSession(false)!=null) request.changeSessionId();
  SecurityContext context=SecurityContextHolder.createEmptyContext();context.setAuthentication(result);SecurityContextHolder.setContext(context);
  new HttpSessionSecurityContextRepository().saveContext(context,request,response);
  new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
 }
}
