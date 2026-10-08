package com.allam.journal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.allam.journal.Domain.*;
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkflowIntegrationTest {
 @Autowired Store db;@Autowired SubmissionService service;@Autowired ReviewService reviews;@Autowired AdminService admin;@Autowired Access access;@Autowired IssueService issues;@Autowired FileService files;@Autowired Analytics analytics;@Autowired OaiController oai;
 Actor author,editor,reviewer,sectionEditor,copy,layout,proof;String section,otherSection,form;
 @BeforeEach void seed() {
  author=account("author",Role.AUTHOR);editor=account("editor",Role.EDITOR,Role.JOURNAL_MANAGER);reviewer=account("reviewer",Role.REVIEWER);copy=account("copy",Role.COPYEDITOR);layout=account("layout",Role.LAYOUT_EDITOR);proof=account("proof",Role.PROOFREADER);
  section=admin.section(editor,Map.of("en","Medicine","ar","طب"));otherSection=admin.section(editor,Map.of("en","Physics"));
  sectionEditor=account("section-editor",Role.SECTION_EDITOR);
  db.jdbc().update("update user_role set section_id=? where user_id=? and role='SECTION_EDITOR'",section,sectionEditor.id());sectionEditor=db.actor(sectionEditor.email());
  form=admin.form(editor,"Review",Map.of("type","object","properties",Map.of("score",Map.of("type","integer","minimum",1,"maximum",5)),"required",List.of("score"),"additionalProperties",false));
 }
 Actor account(String name,Role... roles) {
  String email=name+"-"+db.id()+"@example.org",id=admin.register(email,"test-password-123456789",name);
  for(Role role:roles) if(!db.role(id,role)) db.jdbc().update("insert into user_role values(?,?,?,null)",db.id(),id,role.name());return db.actor(email);
 }
 Map<String,Object> metadata() {return Map.of("title",Map.of("en","Study","ar","دراسة"),"abstract",Map.of("en","A clinical study","ar","ملخص"),"authors",List.of(Map.of("name","Author","email",author.email())),"keywords",List.of("medicine"),"funding",List.of(),"references",List.of());}
 String draft(String target) {return service.create(author,target,"en",true,metadata());}
 void transition(Actor who,String id,Action action) {service.transition(who,id,db.submission(id,false).version(),action,"Decision reason");}
 String upload(Actor who,String id,FileKind kind,Format format,String assignment) throws Exception {return files.upload(who,id,kind,format,assignment,new MockMultipartFile("file","anonymous.pdf","application/pdf","%PDF-1.7\nTest manuscript".getBytes(StandardCharsets.UTF_8)));}
 String underReview() throws Exception {
  String id=draft(section);upload(author,id,FileKind.MANUSCRIPT,null,null);transition(author,id,Action.SUBMIT);String blind=upload(editor,id,FileKind.BLIND_MANUSCRIPT,null,null);files.approveBlind(editor,blind);transition(editor,id,Action.SEND_TO_REVIEW);return id;
 }
 String ready() throws Exception {
  String id=underReview();transition(editor,id,Action.ACCEPT);service.assignStaff(editor,id,copy.id(),Role.COPYEDITOR);transition(copy,id,Action.COMPLETE_COPYEDITING);transition(editor,id,Action.START_PRODUCTION);service.assignStaff(editor,id,layout.id(),Role.LAYOUT_EDITOR);upload(layout,id,FileKind.GALLEY,Format.PDF,null);service.assignStaff(editor,id,proof.id(),Role.PROOFREADER);transition(proof,id,Action.APPROVE_PROOFS);return id;
 }
 @Test void enforcesPrerequisitesAndOptimisticVersions() throws Exception {
  String id=draft(section);assertThatThrownBy(()->transition(author,id,Action.SUBMIT)).isInstanceOf(ApiException.class);
  upload(author,id,FileKind.MANUSCRIPT,null,null);transition(author,id,Action.SUBMIT);
  assertThatThrownBy(()->service.transition(editor,id,0,Action.DESK_REJECT,"reason")).isInstanceOf(ApiException.class).hasMessageContaining("Stale");
  assertThatThrownBy(()->transition(editor,id,Action.SEND_TO_REVIEW)).isInstanceOf(ApiException.class).hasMessageContaining("Required file");
 }
 @Test void sectionEditorRequiresScopeAndAssignment() throws Exception {
  String id=draft(section),other=draft(otherSection);
  assertThat(access.editor(sectionEditor,db.submission(id,false))).isFalse();service.assignEditor(editor,id,sectionEditor.id());
  assertThat(access.editor(sectionEditor,db.submission(id,false))).isTrue();assertThat(access.editor(sectionEditor,db.submission(other,false))).isFalse();
  assertThatThrownBy(()->service.assignEditor(editor,other,sectionEditor.id())).isInstanceOf(ApiException.class);
 }
 @Test void blindedInvitationAndAuthorProjectionDoNotLeakIdentities() throws Exception {
  String id=underReview();String assignment=reviews.invite(editor,id,reviewer.id(),Instant.now().plusSeconds(86400),form);
  assertThat(reviews.view(reviewer,assignment)).doesNotContainKeys("authors","files","formSchema");
  String blind=db.jdbc().queryForObject("select id from submission_file where submission_id=? and kind='BLIND_MANUSCRIPT'",String.class,id);
  assertThatThrownBy(()->files.download(reviewer,blind,false)).isInstanceOf(ApiException.class);
  reviews.respond(reviewer,assignment,true);assertThat(reviews.view(reviewer,assignment)).containsKeys("files","formSchema").doesNotContainKey("authors");
  assertThatCode(()->files.download(reviewer,blind,false)).doesNotThrowAnyException();
  reviews.submit(reviewer,assignment,Recommendation.MINOR_REVISION,Map.of("score",4),"Clarify methods","Confidential editor note");
  String authorJson=db.encode(service.view(author,id));assertThat(authorJson).doesNotContain(reviewer.id(),reviewer.email(),reviewer.name(),"Confidential editor note","reviewerId");
  assertThat(authorJson).contains("Clarify methods","UNDER_REVIEW");
 }
 @Test void openReviewShowsIdentitiesOnlyAfterAcceptance() throws Exception {
  String id=draft(section);upload(author,id,FileKind.MANUSCRIPT,null,null);transition(author,id,Action.SUBMIT);service.mode(editor,id,ReviewMode.OPEN);String blind=upload(editor,id,FileKind.BLIND_MANUSCRIPT,null,null);files.approveBlind(editor,blind);transition(editor,id,Action.SEND_TO_REVIEW);
  String assignment=reviews.invite(editor,id,reviewer.id(),Instant.now().plusSeconds(86400),form);assertThat(reviews.view(reviewer,assignment)).doesNotContainKey("authors");reviews.respond(reviewer,assignment,true);assertThat(reviews.view(reviewer,assignment)).containsKey("authors");
  reviews.submit(reviewer,assignment,Recommendation.ACCEPT,Map.of("score",5),"Good",null);assertThat(db.encode(service.view(author,id))).contains(reviewer.name());
 }
 @Test void revisionsAreVersionedAndOldInvitationsAreCancelled() throws Exception {
  String id=underReview(),assignment=reviews.invite(editor,id,reviewer.id(),Instant.now().plusSeconds(86400),form);transition(editor,id,Action.REQUEST_REVISIONS);
  assertThat(db.review(assignment).status()).isEqualTo("CANCELLED");assertThatThrownBy(()->reviews.respond(reviewer,assignment,true)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->transition(author,id,Action.SUBMIT_REVISION)).isInstanceOf(ApiException.class);upload(author,id,FileKind.REVISION,null,null);transition(author,id,Action.SUBMIT_REVISION);
  assertThat(db.submission(id,false).revision()).isEqualTo(2);String blind=upload(editor,id,FileKind.BLIND_MANUSCRIPT,null,null);files.approveBlind(editor,blind);transition(editor,id,Action.START_REVIEW_ROUND);assertThat(db.submission(id,false).round()).isEqualTo(2);
 }
 @Test void publicationIsAtomicAndPublicDataExcludesWorkflow() throws Exception {
  String first=ready(),second=ready(),issue=issues.create(editor,1,1,2026,Map.of("en","First issue"));issues.schedule(editor,issue,first,1);issues.schedule(editor,issue,second,2);issues.publish(editor,issue);
  assertThat(db.submission(first,false).state()).isEqualTo(State.PUBLISHED);assertThat(db.submission(second,false).state()).isEqualTo(State.PUBLISHED);
  assertThat(issues.publicArticle(first)).doesNotContainKeys("reviews","ownerId","assignedEditorId","state");
  assertThat(db.jdbc().queryForObject("select count(*) from outbox where event_type='DOI_REGISTER'",Integer.class)).isEqualTo(2);
  assertThatThrownBy(()->upload(layout,first,FileKind.GALLEY,Format.PDF,null)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->issues.publish(editor,issue)).isInstanceOf(ApiException.class);
 }
 @Test void ownerWithEditorFlagStillCannotSeeReviewerDetails() throws Exception {
  String id=underReview();db.jdbc().update("insert into user_role values(?,?,?,null)",db.id(),author.id(),"EDITOR");Actor multi=db.actor(author.email());
  assertThat(access.editor(multi,db.submission(id,false))).isFalse();assertThatThrownBy(()->reviews.invite(multi,id,reviewer.id(),Instant.now().plusSeconds(86400),form)).isInstanceOf(ApiException.class);
 }
 @Test void botsAndDoubleClicksAreExcluded() throws Exception {
  String id=ready();issues.continuous(editor,id,db.submission(id,false).version());org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest("GET","/");request.addHeader("User-Agent","Mozilla/5.0");
  analytics.track(id,null,"ABSTRACT_VIEW",request);analytics.track(id,null,"ABSTRACT_VIEW",request);assertThat(db.jdbc().queryForObject("select count(*) from usage_event",Integer.class)).isEqualTo(1);
  org.springframework.mock.web.MockHttpServletRequest bot=new org.springframework.mock.web.MockHttpServletRequest("GET","/");bot.addHeader("User-Agent","Googlebot");analytics.track(id,null,"ABSTRACT_VIEW",bot);assertThat(db.jdbc().queryForObject("select count(*) from usage_event",Integer.class)).isEqualTo(1);
 }
 @Test void oaiPublishesOnlyPublicRecordsAndEscapesMetadata() throws Exception {
  String unpublished=draft(section),published=ready();issues.continuous(editor,published,db.submission(published,false).version());
  org.springframework.util.LinkedMultiValueMap<String,String> params=new org.springframework.util.LinkedMultiValueMap<>();params.add("verb","ListRecords");params.add("metadataPrefix","oai_dc");String xml=oai.harvest(params);assertThat(xml).contains("oai:allam:"+published).doesNotContain("oai:allam:"+unpublished,"reviewerId",author.email());
  javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);assertThatCode(()->factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))).doesNotThrowAnyException();
  params.add("unknown","value");assertThat(oai.harvest(params)).contains("code=\"badArgument\"");
 }
 @Test void oaiPaginationUsesSignedExclusiveCursors() throws Exception {
  Instant now=Instant.now();
  for(int i=0;i<101;i++) {
   String id=draft(section);db.jdbc().update("update submission set state='PUBLISHED',published_at=?,updated_at=? where id=?",Store.ts(now),Store.ts(now),id);
  }
  org.springframework.util.LinkedMultiValueMap<String,String> params=new org.springframework.util.LinkedMultiValueMap<>();params.add("verb","ListIdentifiers");params.add("metadataPrefix","oai_dc");params.add("set",section);
  String first=oai.harvest(params);assertThat(first.split("<header>",-1).length-1).isEqualTo(100);
  String token=first.substring(first.indexOf("<resumptionToken>")+17,first.indexOf("</resumptionToken>"));
  org.springframework.util.LinkedMultiValueMap<String,String> next=new org.springframework.util.LinkedMultiValueMap<>();next.add("verb","ListIdentifiers");next.add("resumptionToken",token);
  String second=oai.harvest(next);assertThat(second.split("<header>",-1).length-1).isEqualTo(1);assertThat(second).contains("<resumptionToken/>");
  next.set("resumptionToken",token+"tampered");assertThat(oai.harvest(next)).contains("code=\"badResumptionToken\"");
 }
}
