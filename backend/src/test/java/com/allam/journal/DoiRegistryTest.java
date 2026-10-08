package com.allam.journal;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class DoiRegistryTest {
 @Test void assignsOnlyAConfirmedFindableDoiAndUsesDeterministicPut() {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);Store db=spy(new Store(jdbc,new ObjectMapper()));String id="00000000-0000-0000-0000-000000000001",doi="10.1234/allam."+id;Instant now=Instant.parse("2026-10-01T10:00:00Z");
  Manuscript s=new Manuscript(id,"owner","section",null,"en",true,State.PUBLISHED,ReviewMode.DOUBLE_BLIND,Map.of("title",Map.of("en","Study"),"authors",List.of(Map.of("name","Author"))),1,1,1,null,now,now,now);doReturn(s).when(db).submission(id,true);
  RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
  server.expect(requestTo("https://registry.example/dois/"+doi)).andExpect(method(HttpMethod.PUT)).andExpect(jsonPath("$.data.attributes.event").value("publish")).andExpect(jsonPath("$.data.attributes.url").value("https://journal.example/articles/"+id)).andRespond(withSuccess("{\"data\":{\"attributes\":{\"doi\":\""+doi+"\",\"state\":\"findable\"}}}",MediaType.APPLICATION_JSON));
  new DoiRegistry(db,builder.build(),"datacite","10.1234","repository","secret","https://registry.example","https://journal.example","Journal").register(id);
  verify(jdbc).update(eq("update submission set doi=?,updated_at=? where id=?"),eq(doi),any(),eq(id));server.verify();
 }
 @Test void unconfiguredRegistryNeverInventsADoi() {
  Store db=mock(Store.class);Instant now=Instant.now();String id="article";
  when(db.submission(id,true)).thenReturn(new Manuscript(id,"owner","section",null,"en",true,State.PUBLISHED,ReviewMode.DOUBLE_BLIND,Map.of(),1,1,1,null,now,now,now));
  assertThatThrownBy(()->new DoiRegistry(db,RestClient.create(),"none","","","","https://registry.example","https://journal.example","Journal").register(id)).isInstanceOf(IllegalStateException.class);
 }
}
