package com.allam.journal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
import static com.allam.journal.Store.ts;
@Service
public class SubmissionService {
 final Store db; final Access access; final Workflow workflow;
 public SubmissionService(Store db,Access access,Workflow workflow) { this.db=db; this.access=access; this.workflow=workflow; }
 @Transactional public String create(Actor actor,String section,String language,boolean checklist,Map<String,Object> metadata) {
  access.require(actor,Role.AUTHOR);
  if(!language.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*")) throw ApiException.bad("Invalid language");
  if(!db.exists("select count(*)>0 from section where id=? and active=true",section)) throw ApiException.bad("Unknown active section");
  Metadata.validate(metadata,language,false);String id=db.id();Instant now=Instant.now();
  db.jdbc().update("insert into submission(id,owner_id,section_id,language,checklist,state,review_mode,metadata,created_at,updated_at) values(?,?,?,?,?,'DRAFT','DOUBLE_BLIND',?,?,?)",id,actor.id(),section,language,checklist,db.encode(metadata),ts(now),ts(now));
  return id;
 }
 @Transactional public void metadata(Actor actor,String id,long version,Map<String,Object> metadata,Boolean checklist) {
  Manuscript s=db.submission(id,true);checkVersion(s,version);
  boolean owner=s.ownerId().equals(actor.id()) && actor.has(Role.AUTHOR) && Set.of(State.DRAFT,State.RETURNED_FOR_CORRECTIONS,State.REVISIONS_REQUIRED).contains(s.state());
  boolean copy=access.staff(actor,s,Role.COPYEDITOR) && s.state()==State.IN_COPYEDITING;
  boolean editor=access.editor(actor,s) && s.state()!=State.PUBLISHED && s.state()!=State.REJECTED_ARCHIVED;
  if(!owner && !copy && !editor) throw ApiException.forbidden();
  Metadata.validate(metadata,s.language(),s.state()!=State.DRAFT);
  db.jdbc().update("update submission set metadata=?,checklist=?,version=version+1,updated_at=? where id=?",db.encode(metadata),checklist==null?s.checklist():checklist,ts(Instant.now()),id);
 }
 @Transactional public void assignEditor(Actor actor,String id,String user) {
  Manuscript s=db.submission(id,true);access.require(actor,Role.EDITOR,Role.JOURNAL_MANAGER);
  if(!db.exists("select count(*)>0 from user_role r join app_user u on r.user_id=u.id where r.user_id=? and r.role='SECTION_EDITOR' and r.section_id=? and u.enabled=true",user,s.sectionId())) throw ApiException.bad("Editor must have this section grant");
  db.jdbc().update("update submission set assigned_editor_id=?,version=version+1,updated_at=? where id=?",user,ts(Instant.now()),id);
  db.event("EDITOR_ASSIGNED",id,Map.of("recipientId",user,"submissionId",id),db.id());
 }
 @Transactional public void assignStaff(Actor actor,String id,String user,Role role) {
  Manuscript s=db.submission(id,true);access.editorial(actor,s);
  if(!Set.of(Role.COPYEDITOR,Role.LAYOUT_EDITOR,Role.PROOFREADER).contains(role) || !db.role(user,role)) throw ApiException.bad("Invalid staff role");
  if(!db.exists("select count(*)>0 from staff_assignment where submission_id=? and user_id=? and role=?",id,user,role.name())) db.jdbc().update("insert into staff_assignment values(?,?,?)",id,user,role.name());
  db.event("STAFF_ASSIGNED",id,Map.of("recipientId",user,"submissionId",id,"role",role.name()),db.id());
 }
 @Transactional public void mode(Actor actor,String id,ReviewMode mode) {
  Manuscript s=db.submission(id,true);access.editorial(actor,s);
  if(s.state()!=State.DRAFT && s.state()!=State.SUBMITTED_UNASSIGNED) throw ApiException.conflict("Anonymity mode is frozen once review starts");
  db.jdbc().update("update submission set review_mode=?,version=version+1,updated_at=? where id=?",mode.name(),ts(Instant.now()),id);
 }
 @Transactional public void transition(Actor actor,String id,long version,Action action,String reason) {
  Manuscript s=db.submission(id,true);checkVersion(s,version);
  switch(action) {
   case SUBMIT,SUBMIT_REVISION -> access.owner(actor,s);
   case COMPLETE_COPYEDITING -> { if(!access.editor(actor,s) && !access.staff(actor,s,Role.COPYEDITOR)) throw ApiException.forbidden(); }
   case APPROVE_PROOFS -> { if(!access.staff(actor,s,Role.PROOFREADER)) throw ApiException.forbidden(); }
   case PUBLISH -> throw ApiException.bad("Use the issue or continuous-publication endpoint");
   default -> access.editorial(actor,s);
  }
  apply(actor,s,action,reason);
 }
 void checkVersion(Manuscript s,long version) { if(s.version()!=version) throw ApiException.conflict("Stale version; reload submission"); }
 void apply(Actor actor,Manuscript s,Action action,String reason) {
  State next=workflow.next(s.state(),action);
  if(Set.of(Action.DESK_REJECT,Action.RETURN_TO_AUTHOR,Action.REQUEST_REVISIONS,Action.ACCEPT,Action.REJECT).contains(action) && (reason==null || reason.isBlank())) throw ApiException.bad("Decision reason required");
  if(action==Action.SUBMIT) {
   Metadata.validate(s.metadata(),s.language(),true);
   if(!s.checklist()) throw ApiException.bad("Submission checklist must be acknowledged");
   requireFile(s,FileKind.MANUSCRIPT,s.revision(),false);
  }
  if(action==Action.SUBMIT_REVISION) requireFile(s,FileKind.REVISION,s.revision()+1,false);
  if(action==Action.SEND_TO_REVIEW || action==Action.START_REVIEW_ROUND) requireFile(s,FileKind.BLIND_MANUSCRIPT,s.revision(),true);
  if(action==Action.COMPLETE_COPYEDITING) Metadata.validate(s.metadata(),s.language(),true);
  if(action==Action.APPROVE_PROOFS || action==Action.PUBLISH) requireFile(s,FileKind.GALLEY,s.revision(),false);
  int round=s.round()+(action==Action.START_REVIEW_ROUND?1:0);
  int revision=s.revision()+(action==Action.SUBMIT_REVISION?1:0);
  Instant now=Instant.now();
  db.jdbc().update("update submission set state=?,round_no=?,revision_no=?,version=version+1,updated_at=?,published_at=? where id=?",next.name(),round,revision,ts(now),ts(action==Action.PUBLISH?now:s.publishedAt()),s.id());
  db.jdbc().update("insert into workflow_audit values(?,?,?,?,?,?,?,?)",db.id(),s.id(),actor.id(),action.name(),s.state().name(),next.name(),reason==null?"":reason,ts(now));
  if(next==State.REVISIONS_REQUIRED || next==State.REJECTED_ARCHIVED || next==State.IN_COPYEDITING) db.jdbc().update("update review_assignment set status='CANCELLED' where submission_id=? and status in ('INVITED','ACCEPTED')",s.id());
  db.event("STATE_CHANGED",s.id(),Map.of("submissionId",s.id(),"from",s.state().name(),"to",next.name(),"action",action.name()),s.id()+":"+(s.version()+1));
  if(action==Action.PUBLISH) db.event("DOI_REGISTER",s.id(),Map.of("submissionId",s.id()),"doi:"+s.id());
 }
 void requireFile(Manuscript s,FileKind kind,int revision,boolean blind) {
  if(!db.exists("select count(*)>0 from submission_file where submission_id=? and kind=? and revision_no=?"+(blind?" and blind_approved=true":""),s.id(),kind.name(),revision)) throw ApiException.conflict("Required file missing: "+kind+" for revision "+revision);
 }
 public Map<String,Object> view(Actor actor,String id) {
  Manuscript s=db.submission(id,false);if(!access.read(actor,s)) throw ApiException.forbidden();
  Map<String,Object> result=base(s);
  // An owner who also holds editorial roles still gets the author projection here.
  if(s.ownerId().equals(actor.id())) {
   result.put("state",authorState(s.state()));
   result.put("workflowStatus",authorState(s.state()));
   List<Map<String,Object>> reviews=new ArrayList<>();
   for(Review r:db.reviews(id)) if("SUBMITTED".equals(r.status())) {
    Map<String,Object> item=new LinkedHashMap<>();item.put("round",r.round());item.put("recommendation",r.recommendation());item.put("comments",Objects.toString(r.authorComments(),""));
    if(s.mode()==ReviewMode.OPEN) item.put("reviewer",db.jdbc().queryForMap("select display_name,orcid from app_user where id=?",r.reviewerId()));
    reviews.add(item);
   }
   result.put("reviews",reviews);
  } else if(access.editor(actor,s)) { result.put("reviews",db.reviews(id));result.put("assignedEditorId",s.editorId()); }
  return result;
 }
 Map<String,Object> base(Manuscript s) {
  Map<String,Object> result=new LinkedHashMap<>(); result.put("id",s.id());result.put("sectionId",s.sectionId());result.put("language",s.language());result.put("metadata",s.metadata());result.put("state",s.state());result.put("version",s.version());result.put("round",s.round());result.put("revision",s.revision());result.put("reviewMode",s.mode());result.put("doi",s.doi());result.put("publishedAt",s.publishedAt());return result;
 }
 public String authorState(State s) { return switch(s) { case IN_REVIEW,REVIEW_ROUND_2,REVISION_SUBMITTED -> "UNDER_REVIEW"; case REVISIONS_REQUIRED,RETURNED_FOR_CORRECTIONS -> "REVISIONS_REQUIRED"; case IN_COPYEDITING,COPYEDITING_COMPLETED,IN_PRODUCTION,READY_FOR_PUBLICATION -> "ACCEPTED";case REJECTED_ARCHIVED -> "REJECTED";default -> s.name(); }; }
 public List<Map<String,Object>> list(Actor actor,int page,int size) {
  ApiController.pagination(page,size);
  String sql="select s.* from submission s where (s.owner_id=? and exists(select 1 from user_role where user_id=? and role='AUTHOR')) or (s.owner_id<>? and exists(select 1 from user_role r where r.user_id=? and (r.role in ('EDITOR','JOURNAL_MANAGER') or (r.role='SECTION_EDITOR' and r.section_id=s.section_id and s.assigned_editor_id=?)))) or exists(select 1 from staff_assignment sa join user_role r on r.user_id=sa.user_id and r.role=sa.role where sa.submission_id=s.id and sa.user_id=?) order by s.created_at desc,s.id limit ? offset ?";
  return db.jdbc().query(sql,db::manuscript,actor.id(),actor.id(),actor.id(),actor.id(),actor.id(),actor.id(),size,page*size).stream().map(s->view(actor,s.id())).toList();
 }
}
