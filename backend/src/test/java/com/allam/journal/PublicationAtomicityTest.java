package com.allam.journal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:atomicity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class PublicationAtomicityTest {
 @Autowired Store db;@Autowired AdminService admin;@Autowired SubmissionService submissions;@Autowired IssueService issues;
 @Test void failureInLaterArticleRollsBackEarlierPublicationAndEvents() {
  String authorId=admin.register(db.id()+"@example.org","atomic-test-password-123","Author");
  String email=db.id()+"@example.org",editorId=admin.register(email,"atomic-test-password-123","Editor");
  db.jdbc().update("insert into user_role values(?,?,?,null)",db.id(),editorId,"JOURNAL_MANAGER");Actor editor=db.actor(email);
  Actor author=db.jdbc().query("select email from app_user where id=?",(r,n)->db.actor(r.getString(1)),authorId).getFirst();
  String section=admin.section(editor,Map.of("en","Atomicity"));Map<String,Object> metadata=Map.of("title",Map.of("en","Title"),"abstract",Map.of("en","Abstract"),"authors",List.of(Map.of("name","Author","email",author.email())));
  String first=submissions.create(author,section,"en",true,metadata),second=submissions.create(author,section,"en",true,metadata);
  // Seed an inconsistent import: a ready article without its galley. The publication guard must fail atomically.
  db.jdbc().update("update submission set state='READY_FOR_PUBLICATION' where id in (?,?)",first,second);
  String earliest=first.compareTo(second)<0?first:second;
  db.jdbc().update("insert into submission_file(id,submission_id,uploader_id,kind,format,revision_no,original_name,media_type,storage_key,size_bytes,sha256,created_at) values(?,?,?,'GALLEY','PDF',1,'article.pdf','application/pdf',?,10,?,?)",db.id(),earliest,editorId,db.id(),"0".repeat(64),Store.ts(Instant.now()));
  String issue=issues.create(editor,1,1,2026,Map.of("en","Atomic issue"));issues.schedule(editor,issue,first,1);issues.schedule(editor,issue,second,2);
  assertThatThrownBy(()->issues.publish(editor,issue)).isInstanceOf(ApiException.class).hasMessageContaining("Required file");
  assertThat(db.submission(first,false).state()).isEqualTo(State.READY_FOR_PUBLICATION);assertThat(db.submission(second,false).state()).isEqualTo(State.READY_FOR_PUBLICATION);
  assertThat(db.jdbc().queryForMap("select published_at from issue where id=?",issue).get("published_at")).isNull();
  assertThat(db.jdbc().queryForObject("select count(*) from workflow_audit where submission_id in (?,?)",Integer.class,first,second)).isZero();
  assertThat(db.jdbc().queryForObject("select count(*) from outbox where aggregate_id in (?,?)",Integer.class,first,second)).isZero();
 }
}
