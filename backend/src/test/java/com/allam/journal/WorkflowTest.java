package com.allam.journal;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.allam.journal.Domain.*;
class WorkflowTest {
 final Workflow workflow=new Workflow();
 @Test void completeLifecycleAndRepeatedRevisionRounds() {
  State state=State.DRAFT;
  for(Action action:List.of(Action.SUBMIT,Action.RETURN_TO_AUTHOR,Action.SUBMIT,Action.SEND_TO_REVIEW,Action.REQUEST_REVISIONS,Action.SUBMIT_REVISION,Action.START_REVIEW_ROUND,Action.REQUEST_REVISIONS,Action.SUBMIT_REVISION,Action.START_REVIEW_ROUND,Action.ACCEPT,Action.COMPLETE_COPYEDITING,Action.START_PRODUCTION,Action.APPROVE_PROOFS,Action.PUBLISH)) state=workflow.next(state,action);
  assertThat(state).isEqualTo(State.PUBLISHED);
 }
 @Test void terminalStatesCannotTransition() {
  for(State state:List.of(State.PUBLISHED,State.REJECTED_ARCHIVED)) for(Action action:Action.values()) assertThatThrownBy(()->workflow.next(state,action)).isInstanceOf(ApiException.class);
 }
 @Test void cannotSkipReviewOrProofs() {
  assertThatThrownBy(()->workflow.next(State.DRAFT,Action.ACCEPT)).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->workflow.next(State.IN_PRODUCTION,Action.PUBLISH)).isInstanceOf(ApiException.class);
  assertThat(workflow.next(State.SUBMITTED_UNASSIGNED,Action.DESK_REJECT)).isEqualTo(State.REJECTED_ARCHIVED);
 }
 @Test void customFormValidatesConstraintsAndRejectsUnsupportedSchemas() {
  Map<String,Object> schema=Map.of("type","object","properties",Map.of("score",Map.of("type","integer","minimum",1,"maximum",5)),"required",List.of("score"),"additionalProperties",false);
  assertThatCode(()->FormValidator.answers(schema,Map.of("score",4))).doesNotThrowAnyException();
  assertThatThrownBy(()->FormValidator.answers(schema,Map.of("score",6))).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->FormValidator.answers(schema,Map.of("score",1.5))).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->FormValidator.answers(schema,Map.of())).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->FormValidator.answers(schema,Map.of("score",3,"private","x"))).isInstanceOf(ApiException.class);
  assertThatThrownBy(()->FormValidator.schema(Map.of("type","object","properties",Map.of(),"$ref","remote"))).isInstanceOf(ApiException.class);
 }
 @Test void localizedMetadataAndXmlAreValidated() {
  Map<String,Object> metadata=Map.of("title",Map.of("en","Title","ar","عنوان"),"abstract",Map.of("en","Abstract","ar","ملخص"),"authors",List.of(Map.of("name","Author","email","author@example.org")));
  assertThatCode(()->Metadata.validate(metadata,"ar",true)).doesNotThrowAnyException();
  assertThatThrownBy(()->Metadata.validate(metadata,"fr",true)).isInstanceOf(ApiException.class);
  assertThat(Xml.escape("<title>&\"\u0001")).isEqualTo("&lt;title&gt;&amp;&quot;");
 }
}
