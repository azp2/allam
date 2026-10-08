package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
@Service
public class AdminService implements ApplicationRunner {
 final Store db;final Access access;final PasswordEncoder passwords;final String bootstrapEmail,bootstrapPassword;
 public AdminService(Store db,Access access,PasswordEncoder passwords,@Value("${journal.bootstrap-email}") String email,@Value("${journal.bootstrap-password}") String password) { this.db=db;this.access=access;this.passwords=passwords;this.bootstrapEmail=email;this.bootstrapPassword=password; }
 @Override @Transactional public void run(ApplicationArguments args) {
  if(bootstrapEmail.isBlank()) return;
  if(db.exists("select count(*)>0 from app_user",new Object[0])) return;
  String id=user(bootstrapEmail,bootstrapPassword,"Journal Manager");
  for(Role role:List.of(Role.JOURNAL_MANAGER,Role.SITE_MANAGER,Role.EDITOR)) db.jdbc().update("insert into user_role values(?,?,?,null)",db.id(),id,role.name());
 }
 String user(String email,String password,String name) {
  if(email==null || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length()>254 || password==null || password.length()<16 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || name==null || name.isBlank() || name.length()>200) throw ApiException.bad("Valid email, name and a 16–72 byte password required");
  String id=db.id();db.jdbc().update("insert into app_user(id,email,password_hash,display_name,created_at) values(?,?,?,?,?)",id,email.toLowerCase(Locale.ROOT),passwords.encode(password),name,Store.ts(Instant.now()));return id;
 }
 @Transactional public String register(String email,String password,String name) {
  String id=user(email,password,name);for(Role role:List.of(Role.READER,Role.AUTHOR)) db.jdbc().update("insert into user_role values(?,?,?,null)",db.id(),id,role.name());return id;
 }
 @Transactional public String createUser(Actor actor,String email,String password,String name) { access.manager(actor);return user(email,password,name); }
 @Transactional public void grant(Actor actor,String user,Role role,String section) {
  access.manager(actor);
  if(role==Role.SECTION_EDITOR && section==null || role!=Role.SECTION_EDITOR && section!=null) throw ApiException.bad("Only section-editor grants require a section");
  if(!db.exists("select count(*)>0 from user_role where user_id=? and role=? and (section_id=? or (section_id is null and ? is null))",user,role.name(),section,section)) db.jdbc().update("insert into user_role values(?,?,?,?)",db.id(),user,role.name(),section);
 }
 @Transactional public void revoke(Actor actor,String grant) {
  access.manager(actor);
  Map<String,Object> row=db.jdbc().queryForList("select * from user_role where id=?",grant).stream().findFirst().orElseThrow(ApiException::missing);
  if(row.get("user_id").equals(actor.id()) && Set.of("SITE_MANAGER","JOURNAL_MANAGER").contains(row.get("role"))) throw ApiException.conflict("Cannot revoke your own manager grant");
  db.jdbc().update("delete from user_role where id=?",grant);
 }
 @Transactional public String section(Actor actor,Map<String,String> names) {
  access.manager(actor);if(names==null || names.isEmpty() || names.values().stream().anyMatch(s->s==null || s.isBlank())) throw ApiException.bad("Localized section names required");
  String id=db.id();db.jdbc().update("insert into section(id,names) values(?,?)",id,db.encode(names));return id;
 }
 @Transactional public String form(Actor actor,String name,Map<String,Object> schema) {
  access.require(actor,Role.EDITOR,Role.JOURNAL_MANAGER);FormValidator.schema(schema);
  if(name==null || name.isBlank() || name.length()>200) throw ApiException.bad("Form name required");
  String id=db.id();db.jdbc().update("insert into review_form values(?,?,?,?)",id,name,db.encode(schema),Store.ts(Instant.now()));return id;
 }
 @Transactional public void template(Actor actor,String type,String subject,String body) {
  access.manager(actor);if(type==null || !type.matches("[A-Z_]{1,60}") || subject==null || subject.isBlank() || subject.length()>255 || subject.contains("\n") || subject.contains("\r") || body==null || body.isBlank() || body.length()>20000) throw ApiException.bad("Invalid email template");
  db.jdbc().update("delete from email_template where event_type=?",type);db.jdbc().update("insert into email_template values(?,?,?)",type,subject,body);
 }
 @Transactional public void setting(Actor actor,String key,Map<String,Object> value) {
  access.manager(actor);if(!key.matches("[a-zA-Z0-9_.-]{1,100}")) throw ApiException.bad("Invalid setting key");
  db.jdbc().update("delete from journal_setting where setting_key=?",key);db.jdbc().update("insert into journal_setting values(?,?)",key,db.encode(value));
 }
}
