package com.allam.journal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
import static com.allam.journal.Store.ts;
@Service
public class ReviewService {
 final Store db;final Access access;
 public ReviewService(Store db,Access access) { this.db=db;this.access=access; }
 @Transactional public String invite(Actor actor,String submission,String reviewer,Instant due,String form) {
  Manuscript s=db.submission(submission,true);access.editorial(actor,s);
  if(!Workflow.reviewing(s.state())) throw ApiException.conflict("Submission is not in peer review");
  if(!db.role(reviewer,Role.REVIEWER) || reviewer.equals(s.ownerId()) || reviewer.equals(actor.id())) throw ApiException.bad("Reviewer must be an independent reviewer user");
  Map<String,Object> identity=db.jdbc().queryForMap("select email,orcid from app_user where id=?",reviewer);
  if(s.metadata().get("authors") instanceof List<?> authors) for(Object raw:authors) if(raw instanceof Map<?,?> author && (Objects.toString(author.get("email"),"").equalsIgnoreCase(identity.get("email").toString()) || identity.get("orcid")!=null && identity.get("orcid").equals(author.get("orcid")))) throw ApiException.bad("A manuscript author cannot review their manuscript");
  if(due==null || !due.isAfter(Instant.now())) throw ApiException.bad("Future deadline required");
  String schema=db.jdbc().query("select schema_json from review_form where id=?",(r,n)->r.getString(1),form).stream().findFirst().orElseThrow(ApiException::missing);
  String id=db.id();db.jdbc().update("insert into review_assignment(id,submission_id,reviewer_id,round_no,revision_no,status,due_at,form_schema) values(?,?,?,?,?,'INVITED',?,?)",id,submission,reviewer,s.round(),s.revision(),ts(due),schema);
  db.event("REVIEW_INVITED",submission,Map.of("recipientId",reviewer,"submissionId",submission,"assignmentId",id),"invitation:"+id);return id;
 }
 @Transactional public void respond(Actor actor,String id,boolean accept) {
  Review initial=db.review(id);Manuscript s=db.submission(initial.submissionId(),true);Review r=db.review(id);own(actor,r);
  if(!Workflow.reviewing(s.state()) || r.round()!=s.round() || !r.status().equals("INVITED")) throw ApiException.conflict("Invitation no longer active");
  db.jdbc().update("update review_assignment set status=? where id=?",accept?"ACCEPTED":"DECLINED",id);
  db.event("REVIEW_RESPONSE",s.id(),Map.of("submissionId",s.id(),"assignmentId",id,"accepted",accept),"response:"+id);
 }
 @Transactional public void submit(Actor actor,String id,Recommendation recommendation,Map<String,Object> answers,String authorComments,String editorComments) {
  Review initial=db.review(id);Manuscript s=db.submission(initial.submissionId(),true);Review r=db.review(id);own(actor,r);
  if(!Workflow.reviewing(s.state()) || r.round()!=s.round() || !r.status().equals("ACCEPTED")) throw ApiException.conflict("Review is not active");
  FormValidator.answers(r.schema(),answers);
  db.jdbc().update("update review_assignment set status='SUBMITTED',recommendation=?,answers=?,author_comments=?,editor_comments=?,submitted_at=? where id=?",recommendation.name(),db.encode(answers),authorComments,editorComments,ts(Instant.now()),id);
  db.event("REVIEW_SUBMITTED",s.id(),Map.of("submissionId",s.id(),"assignmentId",id),"review:"+id);
 }
 public void own(Actor actor,Review r) { access.require(actor,Role.REVIEWER);if(!r.reviewerId().equals(actor.id())) throw ApiException.forbidden(); }
 public Map<String,Object> view(Actor actor,String id) {
  Review r=db.review(id);own(actor,r);Manuscript s=db.submission(r.submissionId(),false);
  Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("submissionId",s.id());result.put("status",r.status());result.put("round",r.round());result.put("dueAt",r.dueAt());result.put("title",s.metadata().getOrDefault("title",Map.of()));result.put("abstract",s.metadata().getOrDefault("abstract",Map.of()));
  if(Set.of("ACCEPTED","SUBMITTED").contains(r.status())) {
   result.put("formSchema",r.schema());result.put("answers",r.answers());result.put("recommendation",r.recommendation());
   result.put("files",db.jdbc().queryForList("select id,kind,media_type,size_bytes from submission_file where submission_id=? and kind='BLIND_MANUSCRIPT' and blind_approved=true and revision_no=?",s.id(),r.revision()));
   if(s.mode()!=ReviewMode.DOUBLE_BLIND) result.put("authors",s.metadata().getOrDefault("authors",List.of()));
  }
  return result;
 }
 public List<Map<String,Object>> list(Actor actor) { access.require(actor,Role.REVIEWER);return db.jdbc().query("select id from review_assignment where reviewer_id=? order by due_at",(r,n)->r.getString(1),actor.id()).stream().map(id->view(actor,id)).toList(); }
}
