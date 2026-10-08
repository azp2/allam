package com.allam.journal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityIntegrationTest {
 @Autowired MockMvc mvc;@Autowired AdminService admin;@Autowired Store db;
 @Test void publicRoutesWorkAndEditorialRoutesRequireAuthentication() throws Exception {
  mvc.perform(get("/api/public/issues")).andExpect(status().isOk());mvc.perform(get("/api/submissions")).andExpect(status().isUnauthorized());
 }
 @Test void mutationsRequireCsrfEvenWithBasicAuthentication() throws Exception {
  String email=db.id()+"@example.org";admin.register(email,"test-password-123456789","Author");
  mvc.perform(post("/api/submissions").with(httpBasic(email,"test-password-123456789")).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
 }
 @Test void authorsCannotGrantRoles() throws Exception {
  String email=db.id()+"@example.org",id=admin.register(email,"test-password-123456789","Author");
  mvc.perform(post("/api/admin/users/"+id+"/roles").with(httpBasic(email,"test-password-123456789")).with(csrf()).contentType("application/json").content("{\"role\":\"EDITOR\"}")) .andExpect(status().isForbidden());
 }
 @Test void registrationNeverAcceptsElevatedRoles() throws Exception {
  String email=db.id()+"@example.org";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType("application/json").content("{\"email\":\""+email+"\",\"password\":\"test-password-123456789\",\"name\":\"Author\",\"role\":\"EDITOR\"}")) .andExpect(status().isCreated());
  org.assertj.core.api.Assertions.assertThat(db.actor(email).has(Domain.Role.EDITOR)).isFalse();
 }
 @Test void jsonLoginPersistsSessionAndLogoutClearsIt() throws Exception {
  String email=db.id()+"@example.org";admin.register(email,"test-password-123456789","Author");
  org.springframework.mock.web.MockHttpSession session=new org.springframework.mock.web.MockHttpSession();
  var result=mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json").content("{\"email\":\""+email+"\",\"password\":\"test-password-123456789\"}")) .andExpect(status().isNoContent()).andReturn();
  session=(org.springframework.mock.web.MockHttpSession)result.getRequest().getSession(false);
  mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
  mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
 }
 @Test void swaggerDocumentationIsPublicAndIncludesLogout() throws Exception {
  mvc.perform(get("/openapi.json")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.0.3")).andExpect(jsonPath("$.paths['/api/auth/logout'].post").exists());
  mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/submissions']").exists());
  mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
  mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
 }
}
