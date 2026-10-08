package com.allam.journal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
@Service
public class DiscussionService {
 final Store db;final Access access;
 public DiscussionService(Store db,Access access) { this.db=db;this.access=access; }
 @Transactional public String post(Actor actor,String id,String visibility,String body) {
  Manuscript s=db.submission(id,true);if(!access.read(actor,s)) throw ApiException.forbidden();
  if(!Set.of("AUTHOR_STAFF","EDITOR_ONLY").contains(visibility) || body==null || body.isBlank() || body.length()>20000) throw ApiException.bad("Invalid discussion message");
  if(visibility.equals("EDITOR_ONLY") && !access.editor(actor,s)) throw ApiException.forbidden();
  if(Set.of(State.PUBLISHED,State.REJECTED_ARCHIVED).contains(s.state())) throw ApiException.conflict("Discussion is closed");
  String message=db.id();db.jdbc().update("insert into discussion values(?,?,?,?,?,?)",message,id,actor.id(),visibility,body,Store.ts(Instant.now()));
  db.event("DISCUSSION_CREATED",id,Map.of("submissionId",id,"visibility",visibility),"discussion:"+message);return message;
 }
 public List<Map<String,Object>> list(Actor actor,String id) {
  Manuscript s=db.submission(id,false);if(!access.read(actor,s)) throw ApiException.forbidden();
  return db.jdbc().queryForList("select d.id,d.visibility,d.body,d.created_at,u.display_name from discussion d join app_user u on u.id=d.author_id where d.submission_id=?"+(access.editor(actor,s)?"":" and d.visibility='AUTHOR_STAFF'")+" order by d.created_at",id);
 }
}
