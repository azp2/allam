package com.allam.journal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.allam.journal.Domain.*;
@Service
public class Analytics {
 final Store db;final String salt;
 // Replace/extend this heuristic with the maintained COUNTER robot list before audited reporting.
 static final Pattern BOTS=Pattern.compile("bot|crawler|spider|slurp|headless|curl|wget|python|monitor",Pattern.CASE_INSENSITIVE);
 public Analytics(Store db,@Value("${journal.analytics-salt}") String salt) { this.db=db;this.salt=salt; }
 @Transactional public void track(String submission,String file,String metric,HttpServletRequest request) {
  String agent=Objects.toString(request.getHeader("User-Agent"),"");
  if(!"GET".equals(request.getMethod()) || salt.isBlank() || agent.isBlank() || BOTS.matcher(agent).find()) return;
  Manuscript s=db.submission(submission,true);if(s.state()!=State.PUBLISHED) return;
  // Ignore untrusted forwarded headers. Configure a trusted proxy explicitly at deployment.
  String visitor=OrcidService.hash(salt+"|"+LocalDate.now(ZoneOffset.UTC)+"|"+request.getRemoteAddr()+"|"+agent);
  Instant now=Instant.now();
  boolean duplicate=db.exists("select count(*)>0 from usage_event where submission_id=? and visitor_hash=? and metric=? and (file_id=? or (file_id is null and ? is null)) and occurred_at>=?",submission,visitor,metric,file,file,Store.ts(now.minusSeconds(30)));
  if(!duplicate) db.jdbc().update("insert into usage_event values(?,?,?,?,?,?)",db.id(),submission,file,metric,visitor,Store.ts(now));
 }
}
