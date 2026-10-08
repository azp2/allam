package com.allam.journal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OutboxWorkerTest {
 @Test void domainEventsFanOutToIndependentMailAndPluginJobs() throws Exception {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);Store db=spy(new Store(jdbc,new ObjectMapper()));JavaMailSender mail=mock(JavaMailSender.class);JournalPlugin plugin=mock(JournalPlugin.class);
  when(plugin.name()).thenReturn("plagiarism");when(plugin.supports("STATE_CHANGED")).thenReturn(true);
  OutboxWorker worker=spy(new OutboxWorker(db,mail,mock(DoiRegistry.class),List.of(plugin),"editor@example.org"));
  Map<String,Object> payload=Map.of("submissionId","article","from","DRAFT","to","SUBMITTED_UNASSIGNED");
  String encodedPayload=db.encode(payload);
  when(jdbc.queryForList(startsWith("select * from outbox"),any(Timestamp.class))).thenReturn(List.of(Map.of("id","event-1","event_type","STATE_CHANGED","aggregate_id","article","payload",encodedPayload,"attempts",0)));
  doReturn(Set.of("author")).when(worker).recipients("STATE_CHANGED","article",payload);doNothing().when(db).event(anyString(),anyString(),anyMap(),anyString());
  worker.process();
  verify(db).event(eq("EMAIL"),eq("article"),argThat(p->p.get("recipientId").equals("author") && !p.containsKey("reviewerId")),eq("email:event-1:author"));
  verify(db).event(eq("PLUGIN:plagiarism"),eq("article"),argThat(p->p.get("eventId").equals("event-1")),eq("plugin:event-1:plagiarism"));
  verifyNoInteractions(mail);verify(plugin,never()).handle(anyString(),anyString(),anyString(),anyMap());
  verify(jdbc).update(eq("update outbox set status='DONE',attempts=?,last_error=null where id=?"),eq(1),eq("event-1"));
 }
 @Test void smtpFailuresBecomeDeadWithoutStoringSensitiveMessages() {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);Store db=new Store(jdbc,new ObjectMapper());JavaMailSender mail=mock(JavaMailSender.class);
  when(jdbc.queryForList(startsWith("select * from outbox"),any(Timestamp.class))).thenReturn(List.of(Map.of("id","event-2","event_type","EMAIL","aggregate_id","article","payload",db.encode(Map.of("recipientId","author","eventType","STATE_CHANGED","submissionId","article")),"attempts",7)));
  when(jdbc.query(eq("select email from app_user where id=? and enabled=true"),any(org.springframework.jdbc.core.RowMapper.class),eq("author"))).thenReturn(List.of("author@example.org"));
  when(jdbc.queryForList("select subject,body from email_template where event_type=?","STATE_CHANGED")).thenReturn(List.of());
  doThrow(new MailSendException("private remote response with secret")).when(mail).send(any(SimpleMailMessage.class));
  new OutboxWorker(db,mail,mock(DoiRegistry.class),List.of(),"editor@example.org").process();
  verify(jdbc).update(eq("update outbox set status=?,attempts=?,available_at=?,last_error=? where id=?"),eq("DEAD"),eq(8),any(Timestamp.class),eq("MailSendException"),eq("event-2"));
 }
 @Test void duplicateOrOversizedPluginNamesAreRejected() {
  JournalPlugin plugin=mock(JournalPlugin.class);when(plugin.name()).thenReturn("x".repeat(54));
  assertThatThrownBy(()->new OutboxWorker(mock(Store.class),mock(JavaMailSender.class),mock(DoiRegistry.class),List.of(plugin),"editor@example.org")).isInstanceOf(IllegalStateException.class);
 }
}
