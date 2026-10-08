package com.allam.journal;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
@Configuration
public class Security {
 @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
 @Bean UserDetailsService users(JdbcTemplate jdbc) {
  return email -> jdbc.query("select email,password_hash,enabled from app_user where email=?",(r,n)->User.withUsername(r.getString(1)).password(r.getString(2)).disabled(!r.getBoolean(3)).authorities("USER").build(),email.toLowerCase(java.util.Locale.ROOT)).stream().findFirst().orElseThrow(()->new UsernameNotFoundException("Unknown user"));
 }
 @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception { return configuration.getAuthenticationManager(); }
 @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
  return http.authorizeHttpRequests(a->a.requestMatchers("/swagger-ui.html","/swagger-ui/**","/v3/api-docs","/v3/api-docs/**","/openapi.json","/api/public/**","/articles/**","/oai","/api/preservation/**","/actuator/health","/api/auth/csrf","/api/auth/login","/api/auth/register").permitAll().anyRequest().authenticated())
   .csrf(c->c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).ignoringRequestMatchers("/oai"))
   .exceptionHandling(e->e.authenticationEntryPoint((request,response,error)->response.sendError(401)))
   .httpBasic(b->{}).formLogin(f->f.disable()).logout(l->l.logoutUrl("/api/auth/logout").logoutSuccessHandler((request,response,authentication)->response.setStatus(204))).build();
 }
}
