package com.allam.journal;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
@Configuration
public class HttpClients {
 @Bean RestClient integrationClient() {
  HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
  JdkClientHttpRequestFactory factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(Duration.ofSeconds(30));
  return RestClient.builder().requestFactory(factory).build();
 }
}
