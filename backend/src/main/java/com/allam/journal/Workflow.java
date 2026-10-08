package com.allam.journal;
import org.springframework.stereotype.Component;
import static com.allam.journal.Domain.*;
@Component
public class Workflow {
 public State next(State from,Action action) {
  return switch(action) {
   case SUBMIT -> from==State.DRAFT || from==State.RETURNED_FOR_CORRECTIONS ? State.SUBMITTED_UNASSIGNED : invalid();
   case DESK_REJECT -> from==State.SUBMITTED_UNASSIGNED ? State.REJECTED_ARCHIVED : invalid();
   case RETURN_TO_AUTHOR -> from==State.SUBMITTED_UNASSIGNED ? State.RETURNED_FOR_CORRECTIONS : invalid();
   case SEND_TO_REVIEW -> from==State.SUBMITTED_UNASSIGNED ? State.IN_REVIEW : invalid();
   case REQUEST_REVISIONS -> reviewing(from) ? State.REVISIONS_REQUIRED : invalid();
   case ACCEPT -> reviewing(from) || from==State.REVISION_SUBMITTED ? State.IN_COPYEDITING : invalid();
   case REJECT -> reviewing(from) || from==State.REVISION_SUBMITTED ? State.REJECTED_ARCHIVED : invalid();
   case SUBMIT_REVISION -> from==State.REVISIONS_REQUIRED ? State.REVISION_SUBMITTED : invalid();
   case START_REVIEW_ROUND -> from==State.REVISION_SUBMITTED ? State.REVIEW_ROUND_2 : invalid();
   case COMPLETE_COPYEDITING -> from==State.IN_COPYEDITING ? State.COPYEDITING_COMPLETED : invalid();
   case START_PRODUCTION -> from==State.COPYEDITING_COMPLETED ? State.IN_PRODUCTION : invalid();
   case APPROVE_PROOFS -> from==State.IN_PRODUCTION ? State.READY_FOR_PUBLICATION : invalid();
   case PUBLISH -> from==State.READY_FOR_PUBLICATION ? State.PUBLISHED : invalid();
  };
 }
 public static boolean reviewing(State state) { return state==State.IN_REVIEW || state==State.REVIEW_ROUND_2; }
 private State invalid() { throw ApiException.conflict("Illegal workflow transition"); }
}
