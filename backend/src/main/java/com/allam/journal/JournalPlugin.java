package com.allam.journal;
import java.util.Map;
/** Register an implementation as a Spring bean in an extension JAR. Handlers must be idempotent. */
public interface JournalPlugin {
 String name();
 boolean supports(String eventType);
 void handle(String eventId,String eventType,String submissionId,Map<String,Object> payload) throws Exception;
}
