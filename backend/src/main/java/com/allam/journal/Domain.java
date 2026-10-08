package com.allam.journal;
import java.time.Instant;
import java.util.*;
public final class Domain {
 private Domain() {}
 public enum Role { READER, AUTHOR, EDITOR, JOURNAL_MANAGER, SECTION_EDITOR, REVIEWER, COPYEDITOR, LAYOUT_EDITOR, PROOFREADER, SITE_MANAGER }
 public enum State { DRAFT, SUBMITTED_UNASSIGNED, RETURNED_FOR_CORRECTIONS, IN_REVIEW, REVISIONS_REQUIRED, REVISION_SUBMITTED, REVIEW_ROUND_2, IN_COPYEDITING, COPYEDITING_COMPLETED, IN_PRODUCTION, READY_FOR_PUBLICATION, PUBLISHED, REJECTED_ARCHIVED }
 public enum Action { SUBMIT, DESK_REJECT, RETURN_TO_AUTHOR, SEND_TO_REVIEW, REQUEST_REVISIONS, ACCEPT, REJECT, SUBMIT_REVISION, START_REVIEW_ROUND, COMPLETE_COPYEDITING, START_PRODUCTION, APPROVE_PROOFS, PUBLISH }
 public enum ReviewMode { DOUBLE_BLIND, SINGLE_BLIND, OPEN }
 public enum Recommendation { ACCEPT, MINOR_REVISION, MAJOR_REVISION, REJECT }
 public enum FileKind { MANUSCRIPT, SUPPLEMENT, REVISION, BLIND_MANUSCRIPT, ANNOTATION, GALLEY, COVER }
 public enum Format { PDF, HTML, JATS_XML }
 public record Grant(Role role,String sectionId) {}
 public record Actor(String id,String email,String name,List<Grant> grants) {
   public boolean has(Role... roles) { return grants.stream().anyMatch(g -> Arrays.asList(roles).contains(g.role())); }
 }
 public record Manuscript(String id,String ownerId,String sectionId,String editorId,String language,boolean checklist,
   State state,ReviewMode mode,Map<String,Object> metadata,int round,int revision,long version,String doi,Instant createdAt,Instant updatedAt,Instant publishedAt) {}
 public record Review(String id,String submissionId,String reviewerId,int round,int revision,String status,Instant dueAt,
   Map<String,Object> schema,String recommendation,Map<String,Object> answers,String authorComments,String editorComments) {}
}
