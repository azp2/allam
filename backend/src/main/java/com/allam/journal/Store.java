package com.allam.journal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
@Repository
public class Store {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 public Store(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
 public JdbcTemplate jdbc() { return jdbc; }
 public String id() { return UUID.randomUUID().toString(); }
 public String encode(Object value) { try { String result=json.writeValueAsString(value);if(result.length()>1024*1024) throw ApiException.bad("JSON payload exceeds 1MB");return result; } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON",e); } }
 public Map<String,Object> decode(String value) { try { return value==null?Map.of():json.readValue(value,new TypeReference<Map<String,Object>>(){}); } catch(Exception e) { throw new IllegalStateException("Invalid stored JSON",e); } }
 public static Instant instant(ResultSet r,String column) throws SQLException { Timestamp t=r.getTimestamp(column);return t==null?null:t.toInstant(); }
 public static Timestamp ts(Instant i) { return i==null?null:Timestamp.from(i); }
 public boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
 public Actor actor(String email) {
   return jdbc.query("select id,email,display_name from app_user where email=? and enabled=true",(r,n)->new Actor(r.getString(1),r.getString(2),r.getString(3),grants(r.getString(1))),email).stream().findFirst().orElseThrow(ApiException::forbidden);
 }
 public List<Grant> grants(String user) { return jdbc.query("select role,section_id from user_role where user_id=?",(r,n)->new Grant(Role.valueOf(r.getString(1)),r.getString(2)),user); }
 public boolean role(String user,Role role) { return exists("select count(*)>0 from user_role r join app_user u on u.id=r.user_id where r.user_id=? and r.role=? and u.enabled=true",user,role.name()); }
 public Manuscript submission(String id,boolean lock) {
  return jdbc.query("select * from submission where id=?"+(lock?" for update":""),this::manuscript,id).stream().findFirst().orElseThrow(ApiException::missing);
 }
 public Manuscript manuscript(ResultSet r,int n) throws SQLException {
   return new Manuscript(r.getString("id"),r.getString("owner_id"),r.getString("section_id"),r.getString("assigned_editor_id"),r.getString("language"),r.getBoolean("checklist"),State.valueOf(r.getString("state")),ReviewMode.valueOf(r.getString("review_mode")),decode(r.getString("metadata")),r.getInt("round_no"),r.getInt("revision_no"),r.getLong("version"),r.getString("doi"),instant(r,"created_at"),instant(r,"updated_at"),instant(r,"published_at"));
 }
 public Review review(String id) { return jdbc.query("select * from review_assignment where id=?",this::reviewRow,id).stream().findFirst().orElseThrow(ApiException::missing); }
 public Review reviewRow(ResultSet r,int n) throws SQLException {
   return new Review(r.getString("id"),r.getString("submission_id"),r.getString("reviewer_id"),r.getInt("round_no"),r.getInt("revision_no"),r.getString("status"),instant(r,"due_at"),decode(r.getString("form_schema")),r.getString("recommendation"),decode(r.getString("answers")),r.getString("author_comments"),r.getString("editor_comments"));
 }
 public List<Review> reviews(String id) { return jdbc.query("select * from review_assignment where submission_id=? order by round_no,id",this::reviewRow,id); }
 public void event(String type,String aggregate,Map<String,Object> payload,String key) {
  if(exists("select count(*)>0 from outbox where dedupe_key=?",key)) return;
  Instant now=Instant.now();
  jdbc.update("insert into outbox(id,event_type,aggregate_id,payload,dedupe_key,status,attempts,available_at,created_at) values(?,?,?,?,?,'PENDING',0,?,?)",id(),type,aggregate,encode(payload),key,ts(now),ts(now));
 }
}
