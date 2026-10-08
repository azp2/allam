package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
@Component
@ConditionalOnProperty(name="journal.workers-enabled",havingValue="true",matchIfMissing=true)
public class OutboxWorker {
 final Store db;final JavaMailSender mail;final DoiRegistry doi;final List<JournalPlugin> plugins;final String sender;
 public OutboxWorker(Store db,JavaMailSender mail,DoiRegistry doi,List<JournalPlugin> plugins,@Value("${journal.email}") String sender) {
  this.db=db;this.mail=mail;this.doi=doi;this.plugins=plugins;this.sender=sender;
  if(plugins.stream().anyMatch(p->p.name()==null || !p.name().matches("[a-zA-Z0-9_.-]{1,53}"))) throw new IllegalStateException("Invalid plugin name");
  if(plugins.stream().map(JournalPlugin::name).distinct().count()!=plugins.size()) throw new IllegalStateException("Plugin names must be unique");
 }
 @Scheduled(fixedDelayString="${journal.outbox-delay-ms:1000}") @Transactional
 public void process() {
  List<Map<String,Object>> rows=db.jdbc().queryForList("select * from outbox where status='PENDING' and available_at<=? order by created_at,id limit 1 for update skip locked",Store.ts(Instant.now()));
  if(rows.isEmpty()) return;
  Map<String,Object> event=rows.getFirst();String id=event.get("id").toString(),type=event.get("event_type").toString(),aggregate=event.get("aggregate_id").toString();Map<String,Object> payload=db.decode(event.get("payload").toString());int attempts=((Number)event.get("attempts")).intValue()+1;
  try {
   if(type.equals("EMAIL")) email(payload);
   else if(type.equals("DOI_REGISTER")) doi.register(aggregate);
   else if(type.startsWith("PLUGIN:")) {
    JournalPlugin plugin=plugins.stream().filter(p->p.name().equals(type.substring(7))).findFirst().orElseThrow(()->new IllegalStateException("Plugin unavailable"));
    plugin.handle(payload.get("eventId").toString(),payload.get("eventType").toString(),aggregate,(Map<String,Object>)payload.get("eventPayload"));
   } else {
    for(String recipient:recipients(type,aggregate,payload)) db.event("EMAIL",aggregate,Map.of("recipientId",recipient,"submissionId",aggregate,"eventType",type,"state",Objects.toString(payload.get("to"),"")),"email:"+id+":"+recipient);
    for(JournalPlugin plugin:plugins) if(plugin.supports(type)) db.event("PLUGIN:"+plugin.name(),aggregate,Map.of("eventId",id,"eventType",type,"eventPayload",payload),"plugin:"+id+":"+plugin.name());
   }
   db.jdbc().update("update outbox set status='DONE',attempts=?,last_error=null where id=?",attempts,id);
  } catch(Exception e) {
   // Never retain remote bodies, access tokens, email content or credentials in error records.
   db.jdbc().update("update outbox set status=?,attempts=?,available_at=?,last_error=? where id=?",attempts>=8?"DEAD":"PENDING",attempts,Store.ts(Instant.now().plusSeconds(Math.min(3600,30L*(1L<<Math.min(attempts,7))))),e.getClass().getSimpleName(),id);
  }
 }
 Set<String> recipients(String type,String aggregate,Map<String,Object> payload) {
  if(payload.get("recipientId") instanceof String recipient) return Set.of(recipient);
  Manuscript s=db.submission(aggregate,false);Set<String> result=new LinkedHashSet<>();
  if(type.equals("STATE_CHANGED") || type.equals("DISCUSSION_CREATED") && !"EDITOR_ONLY".equals(payload.get("visibility"))) result.add(s.ownerId());
  if(s.editorId()!=null) result.add(s.editorId());
  result.addAll(db.jdbc().query("select distinct user_id from user_role where role in ('EDITOR','JOURNAL_MANAGER')",(r,n)->r.getString(1)));
  if(type.equals("STATE_CHANGED") || type.equals("DISCUSSION_CREATED") && !"EDITOR_ONLY".equals(payload.get("visibility"))) result.addAll(db.jdbc().query("select user_id from staff_assignment where submission_id=?",(r,n)->r.getString(1),aggregate));
  return result;
 }
 void email(Map<String,Object> payload) {
  List<String> recipients=db.jdbc().query("select email from app_user where id=? and enabled=true",(r,n)->r.getString(1),payload.get("recipientId"));if(recipients.isEmpty()) return;
  String event=payload.get("eventType").toString();List<Map<String,Object>> templates=db.jdbc().queryForList("select subject,body from email_template where event_type=?",event);
  String subject="Journal update: {{eventType}}",body="Submission {{submissionId}} has an update. Workflow: {{state}}. Sign in to view details.";
  if(!templates.isEmpty()) { subject=templates.getFirst().get("subject").toString();body=templates.getFirst().get("body").toString(); }
  for(String key:List.of("submissionId","eventType","state")) { String value=Objects.toString(payload.get(key),"");subject=subject.replace("{{"+key+"}}",value);body=body.replace("{{"+key+"}}",value); }
  SimpleMailMessage message=new SimpleMailMessage();message.setFrom(sender);message.setTo(recipients.getFirst());message.setSubject(subject);message.setText(body);mail.send(message);
 }
 @Scheduled(cron="${journal.reminder-cron:0 0 8 * * *}",zone="UTC") @Transactional
 public void reminders() {
  Instant now=Instant.now();String day=now.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
  List<Map<String,Object>> reviews=db.jdbc().queryForList("select r.id,r.submission_id,r.reviewer_id from review_assignment r join submission s on s.id=r.submission_id where r.status in ('INVITED','ACCEPTED') and s.state in ('IN_REVIEW','REVIEW_ROUND_2') and r.round_no=s.round_no and r.due_at<=?",Store.ts(now.plusSeconds(3*86400)));
  for(Map<String,Object> review:reviews) db.event("REVIEW_REMINDER",review.get("submission_id").toString(),Map.of("recipientId",review.get("reviewer_id"),"submissionId",review.get("submission_id")),"reminder:"+review.get("id")+":"+day);
  db.jdbc().update("delete from orcid_state where expires_at<?",Store.ts(now));
 }
}
