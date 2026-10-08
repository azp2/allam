package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.time.ZoneOffset;
import java.util.*;
import static com.allam.journal.Domain.*;
@Component
public class DoiRegistry {
 final Store db;final RestClient http;final String provider,prefix,user,password,endpoint,base,publisher;
 public DoiRegistry(Store db,RestClient http,@Value("${journal.doi.provider}") String provider,@Value("${journal.doi.prefix}") String prefix,@Value("${journal.doi.username}") String user,@Value("${journal.doi.password}") String password,@Value("${journal.doi.datacite-url}") String endpoint,@Value("${journal.base-url}") String base,@Value("${journal.name}") String publisher) {
  this.db=db;this.http=http;this.provider=provider;this.prefix=prefix;this.user=user;this.password=password;this.endpoint=endpoint;this.base=base;this.publisher=publisher;
 }
 public void register(String id) {
  Manuscript s=db.submission(id,true);if(s.state()!=State.PUBLISHED) throw ApiException.conflict("DOI requires a published article");
  if(s.doi()!=null) return;
  if(!provider.equals("datacite") || prefix.isBlank() || user.isBlank() || password.isBlank()) throw new IllegalStateException("Configure a DataCite repository and DOI prefix");
  String doi=prefix+"/allam."+id;
  List<Map<String,Object>> creators=new ArrayList<>();
  if(s.metadata().get("authors") instanceof List<?> authors) for(Object raw:authors) if(raw instanceof Map<?,?> a) creators.add(Map.of("name",a.get("name"),"nameType","Personal"));
  Map<String,Object> attributes=new LinkedHashMap<>();attributes.put("doi",doi);attributes.put("event","publish");attributes.put("url",base+"/articles/"+id);
  attributes.put("creators",creators);attributes.put("titles",List.of(Map.of("title",Metadata.text(s.metadata(),"title",s.language()))));attributes.put("publisher",publisher);attributes.put("publicationYear",s.publishedAt().atZone(ZoneOffset.UTC).getYear());attributes.put("types",Map.of("resourceTypeGeneral","Text","resourceType","JournalArticle"));
  // PUT is deterministic: retrying after a lost response updates the same identifier.
  Map<?,?> response=http.put().uri(endpoint+"/dois/"+doi).headers(h->h.setBasicAuth(user,password)).contentType(MediaType.valueOf("application/vnd.api+json")).body(Map.of("data",Map.of("type","dois","id",doi,"attributes",attributes))).retrieve().body(Map.class);
  if(response==null || !(response.get("data") instanceof Map<?,?> data) || !(data.get("attributes") instanceof Map<?,?> returned) || !"findable".equals(returned.get("state")) || !doi.equalsIgnoreCase(Objects.toString(returned.get("doi"),""))) throw new IllegalStateException("DOI registration was not confirmed findable");
  db.jdbc().update("update submission set doi=?,updated_at=? where id=?",doi,Store.ts(java.time.Instant.now()),id);
 }
}
