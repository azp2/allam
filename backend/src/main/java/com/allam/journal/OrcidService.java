package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
@Service
public class OrcidService {
 final Store db;final RestClient http;final Access access;final String client,secret,base,redirect;
 public OrcidService(Store db,RestClient http,Access access,@Value("${journal.orcid.client-id}") String client,@Value("${journal.orcid.client-secret}") String secret,@Value("${journal.orcid.base-url}") String base,@Value("${journal.orcid.redirect-uri}") String redirect) { this.db=db;this.http=http;this.access=access;this.client=client;this.secret=secret;this.base=base;this.redirect=redirect; }
 static String hash(String input) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
 @Transactional public String start(Actor actor) {
  access.require(actor,Role.AUTHOR,Role.REVIEWER);if(client.isBlank() || secret.isBlank()) throw ApiException.conflict("ORCID credentials are not configured");
  byte[] random=new byte[32];new SecureRandom().nextBytes(random);String state=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  db.jdbc().update("insert into orcid_state values(?,?,?)",hash(state),actor.id(),Store.ts(Instant.now().plusSeconds(600)));
  return UriComponentsBuilder.fromUriString(base+"/oauth/authorize").queryParam("client_id",client).queryParam("response_type","code").queryParam("scope","/authenticate").queryParam("redirect_uri",redirect).queryParam("state",state).build().encode().toUriString();
 }
 @Transactional public String callback(Actor actor,String state,String code) {
  if(state==null || code==null || state.length()>200 || code.length()>1000) throw ApiException.bad("Invalid ORCID callback");
  List<Map<String,Object>> rows=db.jdbc().queryForList("select * from orcid_state where state_hash=? and user_id=? and expires_at>? for update",hash(state),actor.id(),Store.ts(Instant.now()));
  if(rows.isEmpty()) throw ApiException.bad("Invalid, expired or reused OAuth state");
  LinkedMultiValueMap<String,String> form=new LinkedMultiValueMap<>();form.add("client_id",client);form.add("client_secret",secret);form.add("grant_type","authorization_code");form.add("code",code);form.add("redirect_uri",redirect);
  Map<?,?> response=http.post().uri(base+"/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form).retrieve().body(Map.class);
  if(response==null || !(response.get("orcid") instanceof String orcid) || !orcid.matches("[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]")) throw ApiException.bad("ORCID verification failed");
  db.jdbc().update("update app_user set orcid=?,orcid_verified_at=? where id=?",orcid,Store.ts(Instant.now()),actor.id());db.jdbc().update("delete from orcid_state where state_hash=?",hash(state));return orcid;
 }
}
