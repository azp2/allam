package com.allam.journal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
@RestController
@RequestMapping("/api")
public class ApiController {
 final Store db;final Access access;final SubmissionService submissions;final ReviewService reviews;final FileService files;final IssueService issues;final AdminService admin;final DiscussionService discussions;final OrcidService orcid;final Analytics analytics;
 public ApiController(Store db,Access access,SubmissionService submissions,ReviewService reviews,FileService files,IssueService issues,AdminService admin,DiscussionService discussions,OrcidService orcid,Analytics analytics) { this.db=db;this.access=access;this.submissions=submissions;this.reviews=reviews;this.files=files;this.issues=issues;this.admin=admin;this.discussions=discussions;this.orcid=orcid;this.analytics=analytics; }
 Actor actor(Principal principal) { if(principal==null) throw ApiException.forbidden();return db.actor(principal.getName().toLowerCase(Locale.ROOT)); }
 record UserInput(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(min=16,max=72) String password,@NotBlank @Size(max=200) String name) {}
 record DraftInput(@NotBlank String sectionId,@NotBlank @Size(max=16) String language,boolean checklist,@NotNull Map<String,Object> metadata) {}
 record MetadataInput(@PositiveOrZero long version,@NotNull Map<String,Object> metadata,Boolean checklist) {}
 record TransitionInput(@PositiveOrZero long version,@NotNull Action action,@Size(max=20000) String reason) {}
 record UserAssignment(@NotBlank String userId) {}
 record StaffInput(@NotBlank String userId,@NotNull Role role) {}
 record ModeInput(@NotNull ReviewMode mode) {}
 record InviteInput(@NotBlank String reviewerId,@NotNull Instant dueAt,@NotBlank String formId) {}
 record ResponseInput(@NotNull Boolean accept) {}
 record ReviewInput(@NotNull Recommendation recommendation,@NotNull Map<String,Object> answers,@Size(max=20000) String authorComments,@Size(max=20000) String editorComments) {}
 record DiscussionInput(@NotBlank String visibility,@NotBlank @Size(max=20000) String body) {}
 record IssueInput(@Min(1) int volume,@Min(1) int number,@Min(1900) @Max(2200) int year,@NotEmpty Map<String,String> titles) {}
 record ScheduleInput(@NotBlank String submissionId,@PositiveOrZero int position) {}
 record VersionInput(@PositiveOrZero long version) {}
 record GrantInput(@NotNull Role role,String sectionId) {}
 record NamesInput(@NotEmpty Map<String,String> names) {}
 record FormInput(@NotBlank @Size(max=200) String name,@NotNull Map<String,Object> schema) {}
 record TemplateInput(@NotBlank String subject,@NotBlank String body) {}
 @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("headerName",token.getHeaderName(),"token",token.getToken()); }
 @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> register(@Valid @RequestBody UserInput input) { return Map.of("id",admin.register(input.email(),input.password(),input.name())); }
 @GetMapping("/me") public Map<String,Object> me(Principal p) { Actor a=actor(p);Map<String,Object> result=new LinkedHashMap<>();result.put("id",a.id());result.put("email",a.email());result.put("name",a.name());result.put("grants",a.grants());result.put("identity",db.jdbc().queryForMap("select orcid,orcid_verified_at from app_user where id=?",a.id()));return result; }
 @PostMapping("/me/orcid/authorize") public Map<String,String> orcid(Principal p) { return Map.of("authorizationUrl",orcid.start(actor(p))); }
 @GetMapping("/me/orcid/callback") public Map<String,String> orcidCallback(Principal p,@RequestParam String state,@RequestParam String code) { return Map.of("orcid",orcid.callback(actor(p),state,code)); }
 @PostMapping("/submissions") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> draft(Principal p,@Valid @RequestBody DraftInput input) { return Map.of("id",submissions.create(actor(p),input.sectionId(),input.language(),input.checklist(),input.metadata())); }
 @GetMapping("/submissions") public List<Map<String,Object>> submissions(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) { return submissions.list(actor(p),page,size); }
 @GetMapping("/submissions/{id}") public Map<String,Object> submission(Principal p,@PathVariable String id) { return submissions.view(actor(p),id); }
 @PutMapping("/submissions/{id}/metadata") @ResponseStatus(HttpStatus.NO_CONTENT) public void metadata(Principal p,@PathVariable String id,@Valid @RequestBody MetadataInput input) { submissions.metadata(actor(p),id,input.version(),input.metadata(),input.checklist()); }
 @PostMapping("/submissions/{id}/transitions") @ResponseStatus(HttpStatus.NO_CONTENT) public void transition(Principal p,@PathVariable String id,@Valid @RequestBody TransitionInput input) { submissions.transition(actor(p),id,input.version(),input.action(),input.reason()); }
 @PutMapping("/submissions/{id}/section-editor") @ResponseStatus(HttpStatus.NO_CONTENT) public void sectionEditor(Principal p,@PathVariable String id,@Valid @RequestBody UserAssignment input) { submissions.assignEditor(actor(p),id,input.userId()); }
 @PostMapping("/submissions/{id}/staff") @ResponseStatus(HttpStatus.NO_CONTENT) public void staff(Principal p,@PathVariable String id,@Valid @RequestBody StaffInput input) { submissions.assignStaff(actor(p),id,input.userId(),input.role()); }
 @PutMapping("/submissions/{id}/review-mode") @ResponseStatus(HttpStatus.NO_CONTENT) public void mode(Principal p,@PathVariable String id,@Valid @RequestBody ModeInput input) { submissions.mode(actor(p),id,input.mode()); }
 @GetMapping("/submissions/{id}/audit") public List<Map<String,Object>> audit(Principal p,@PathVariable String id) { access.editorial(actor(p),db.submission(id,false));return db.jdbc().queryForList("select * from workflow_audit where submission_id=? order by created_at",id); }
 @PostMapping(value="/submissions/{id}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
 public Map<String,String> upload(Principal p,@PathVariable String id,@RequestParam FileKind kind,@RequestParam(required=false) Format format,@RequestParam(required=false) String assignmentId,@RequestPart("file") MultipartFile file) throws Exception { return Map.of("id",files.upload(actor(p),id,kind,format,assignmentId,file)); }
 @GetMapping("/submissions/{id}/files") public List<Map<String,Object>> fileList(Principal p,@PathVariable String id) { return files.list(actor(p),id); }
 @PostMapping("/files/{id}/approve-blind") @ResponseStatus(HttpStatus.NO_CONTENT) public void blind(Principal p,@PathVariable String id) { files.approveBlind(actor(p),id); }
 @GetMapping("/files/{id}/download") public ResponseEntity<Resource> download(Principal p,@PathVariable String id) { Resource resource=files.download(actor(p),id,false);return attachment(id,resource); }
 ResponseEntity<Resource> attachment(String id,Resource resource) {
  String type=files.row(id).get("media_type").toString();String extension=switch(type) {case "application/pdf"->".pdf";case "text/html"->".html";case "application/xml"->".xml";case "application/vnd.openxmlformats-officedocument.wordprocessingml.document"->".docx";default->".bin";};
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("document-"+id+extension).build().toString()).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").cacheControl(CacheControl.noStore()).body(resource);
 }
 @PostMapping("/submissions/{id}/reviews") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> invite(Principal p,@PathVariable String id,@Valid @RequestBody InviteInput input) { return Map.of("id",reviews.invite(actor(p),id,input.reviewerId(),input.dueAt(),input.formId())); }
 @GetMapping("/reviews") public List<Map<String,Object>> reviewList(Principal p) { return reviews.list(actor(p)); }
 @GetMapping("/reviews/{id}") public Map<String,Object> review(Principal p,@PathVariable String id) { return reviews.view(actor(p),id); }
 @PostMapping("/reviews/{id}/response") @ResponseStatus(HttpStatus.NO_CONTENT) public void respond(Principal p,@PathVariable String id,@Valid @RequestBody ResponseInput input) { reviews.respond(actor(p),id,input.accept()); }
 @PostMapping("/reviews/{id}/evaluation") @ResponseStatus(HttpStatus.NO_CONTENT) public void evaluate(Principal p,@PathVariable String id,@Valid @RequestBody ReviewInput input) { reviews.submit(actor(p),id,input.recommendation(),input.answers(),input.authorComments(),input.editorComments()); }
 @GetMapping("/submissions/{id}/discussions") public List<Map<String,Object>> discussions(Principal p,@PathVariable String id) { return discussions.list(actor(p),id); }
 @PostMapping("/submissions/{id}/discussions") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> discussion(Principal p,@PathVariable String id,@Valid @RequestBody DiscussionInput input) { return Map.of("id",discussions.post(actor(p),id,input.visibility(),input.body())); }
 @PostMapping("/issues") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> issue(Principal p,@Valid @RequestBody IssueInput input) { return Map.of("id",issues.create(actor(p),input.volume(),input.number(),input.year(),input.titles())); }
 @GetMapping("/issues") public List<Map<String,Object>> issueList(Principal p) { access.require(actor(p),Role.EDITOR,Role.JOURNAL_MANAGER);return db.jdbc().queryForList("select * from issue order by issue_year desc,volume desc,issue_number desc").stream().map(issues::localized).toList(); }
 @PostMapping("/issues/{id}/articles") @ResponseStatus(HttpStatus.NO_CONTENT) public void schedule(Principal p,@PathVariable String id,@Valid @RequestBody ScheduleInput input) { issues.schedule(actor(p),id,input.submissionId(),input.position()); }
 @PostMapping("/issues/{id}/publish") @ResponseStatus(HttpStatus.NO_CONTENT) public void publish(Principal p,@PathVariable String id) { issues.publish(actor(p),id); }
 @PostMapping("/submissions/{id}/publish") @ResponseStatus(HttpStatus.NO_CONTENT) public void continuous(Principal p,@PathVariable String id,@Valid @RequestBody VersionInput input) { issues.continuous(actor(p),id,input.version()); }
 @GetMapping("/public/sections") public List<Map<String,Object>> sections() { return db.jdbc().queryForList("select id,names from section where active=true order by id").stream().map(row->{row.put("names",db.decode(row.get("names").toString()));return row;}).toList(); }
 @GetMapping("/public/issues") public List<Map<String,Object>> publicIssues() { return issues.publicIssues(); }
 @GetMapping("/public/issues/{id}") public Map<String,Object> publicIssue(@PathVariable String id) { return issues.publicIssue(id); }
 @GetMapping("/public/articles") public List<Map<String,Object>> articles(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {
  pagination(page,size);return db.jdbc().query("select * from submission where state='PUBLISHED' order by published_at desc,id limit ? offset ?",db::manuscript,size,page*size).stream().map(issues::article).toList();
 }
 @GetMapping("/public/articles/{id}") public Map<String,Object> article(@PathVariable String id,HttpServletRequest request) { Map<String,Object> result=issues.publicArticle(id);analytics.track(id,null,"ABSTRACT_VIEW",request);return result; }
 @GetMapping("/public/galleys/{id}/download") public ResponseEntity<Resource> galley(@PathVariable String id,HttpServletRequest request) { Resource resource=files.download(null,id,true);Map<String,Object> f=files.row(id);analytics.track(f.get("submission_id").toString(),id,"GALLEY_DOWNLOAD",request);return attachment(id,resource); }
 @PostMapping("/admin/users") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> user(Principal p,@Valid @RequestBody UserInput input) { return Map.of("id",admin.createUser(actor(p),input.email(),input.password(),input.name())); }
 @GetMapping("/admin/users") public List<Map<String,Object>> users(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) { access.manager(actor(p));pagination(page,size);return db.jdbc().queryForList("select id,email,display_name,enabled,orcid,orcid_verified_at from app_user order by id limit ? offset ?",size,page*size); }
 @GetMapping("/admin/users/{id}/roles") public List<Map<String,Object>> roles(Principal p,@PathVariable String id) { access.manager(actor(p));return db.jdbc().queryForList("select id,role,section_id from user_role where user_id=?",id); }
 @PostMapping("/admin/users/{id}/roles") @ResponseStatus(HttpStatus.NO_CONTENT) public void grant(Principal p,@PathVariable String id,@Valid @RequestBody GrantInput input) { admin.grant(actor(p),id,input.role(),input.sectionId()); }
 @DeleteMapping("/admin/roles/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void revoke(Principal p,@PathVariable String id) { admin.revoke(actor(p),id); }
 @PostMapping("/admin/sections") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> section(Principal p,@Valid @RequestBody NamesInput input) { return Map.of("id",admin.section(actor(p),input.names())); }
 @PostMapping("/admin/review-forms") @ResponseStatus(HttpStatus.CREATED) public Map<String,String> form(Principal p,@Valid @RequestBody FormInput input) { return Map.of("id",admin.form(actor(p),input.name(),input.schema())); }
 @GetMapping("/admin/review-forms") public List<Map<String,Object>> forms(Principal p) { access.require(actor(p),Role.EDITOR,Role.JOURNAL_MANAGER,Role.SECTION_EDITOR);return db.jdbc().queryForList("select * from review_form").stream().map(row->{row.put("schema_json",db.decode(row.get("schema_json").toString()));return row;}).toList(); }
 @GetMapping("/editor/reviewers") public List<Map<String,Object>> reviewers(Principal p,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) {
  access.require(actor(p),Role.EDITOR,Role.JOURNAL_MANAGER,Role.SECTION_EDITOR);pagination(page,size);
  if(query.length()>100) throw ApiException.bad("Query too long");
  return db.jdbc().queryForList("select distinct u.id,u.display_name,u.orcid from app_user u join user_role r on r.user_id=u.id where r.role='REVIEWER' and u.enabled=true and lower(u.display_name) like ? order by u.id limit ? offset ?","%"+query.toLowerCase(Locale.ROOT)+"%",size,page*size);
 }
 @PutMapping("/admin/email-templates/{type}") @ResponseStatus(HttpStatus.NO_CONTENT) public void template(Principal p,@PathVariable String type,@Valid @RequestBody TemplateInput input) { admin.template(actor(p),type,input.subject(),input.body()); }
 @GetMapping("/admin/email-templates") public List<Map<String,Object>> templates(Principal p) { access.manager(actor(p));return db.jdbc().queryForList("select * from email_template"); }
 @PutMapping("/admin/settings/{key}") @ResponseStatus(HttpStatus.NO_CONTENT) public void setting(Principal p,@PathVariable String key,@RequestBody Map<String,Object> value) { admin.setting(actor(p),key,value); }
 @GetMapping("/admin/settings") public List<Map<String,Object>> settings(Principal p) { access.manager(actor(p));return db.jdbc().queryForList("select * from journal_setting").stream().map(row->{row.put("value_json",db.decode(row.get("value_json").toString()));return row;}).toList(); }
 @GetMapping("/public/settings/ui") public Map<String,Object> ui() { return db.jdbc().query("select value_json from journal_setting where setting_key='ui'",(r,n)->db.decode(r.getString(1))).stream().findFirst().orElse(Map.of()); }
 @GetMapping("/admin/outbox") public List<Map<String,Object>> outbox(Principal p,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size) { access.manager(actor(p));pagination(page,size);return db.jdbc().queryForList("select id,event_type,aggregate_id,status,attempts,available_at,last_error from outbox order by created_at desc,id limit ? offset ?",size,page*size); }
 @PostMapping("/admin/outbox/{id}/retry") @ResponseStatus(HttpStatus.NO_CONTENT) public void retry(Principal p,@PathVariable String id) { access.manager(actor(p));if(db.jdbc().update("update outbox set status='PENDING',attempts=0,available_at=?,last_error=null where id=? and status='DEAD'",Store.ts(Instant.now()),id)!=1) throw ApiException.conflict("Only dead events can be replayed"); }
 @GetMapping("/admin/usage") public List<Map<String,Object>> usage(Principal p,@RequestParam Instant from,@RequestParam Instant until) {
  access.manager(actor(p));if(!from.isBefore(until) || java.time.Duration.between(from,until).toDays()>366) throw ApiException.bad("Choose an interval of at most 366 days");
  return db.jdbc().queryForList("select submission_id,metric,count(*) as total,count(distinct visitor_hash) as unique_daily_visitors from usage_event where occurred_at>=? and occurred_at<? group by submission_id,metric",Store.ts(from),Store.ts(until));
 }
 static void pagination(int page,int size) { if(page<0 || page>100000 || size<1 || size>100) throw ApiException.bad("Invalid pagination"); }
}
