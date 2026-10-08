package com.allam.journal;
import java.util.*;
/** A deliberately bounded JSON Schema subset. Unsupported constraints are rejected at creation. */
public final class FormValidator {
 private FormValidator() {}
 public static void schema(Map<String,Object> schema) {
  if(!"object".equals(schema.get("type")) || !(schema.get("properties") instanceof Map<?,?> props) || props.size()>100) throw ApiException.bad("Schema requires object properties");
  for(String key:schema.keySet()) if(!Set.of("type","properties","required","additionalProperties","title","description").contains(key)) throw ApiException.bad("Unsupported schema keyword: "+key);
  if(schema.containsKey("additionalProperties") && !Boolean.FALSE.equals(schema.get("additionalProperties"))) throw ApiException.bad("additionalProperties must be false");
  if(schema.containsKey("required") && (!(schema.get("required") instanceof List<?> required) || required.stream().anyMatch(k->!(k instanceof String) || !props.containsKey(k)))) throw ApiException.bad("Invalid required fields");
  for(var entry:props.entrySet()) {
   if(!(entry.getKey() instanceof String name) || name.length()>100 || !(entry.getValue() instanceof Map<?,?> p) || !Set.of("string","number","integer","boolean").contains(p.get("type"))) throw ApiException.bad("Only scalar form fields supported");
   for(Object key:p.keySet()) if(!Set.of("type","title","description","enum","minimum","maximum","maxLength").contains(key)) throw ApiException.bad("Unsupported field keyword: "+key);
   if(p.containsKey("enum") && (!(p.get("enum") instanceof List<?> values) || values.isEmpty())) throw ApiException.bad("Invalid enum");
   for(String k:List.of("minimum","maximum","maxLength")) if(p.containsKey(k) && (!(p.get(k) instanceof Number n) || !Double.isFinite(n.doubleValue()))) throw ApiException.bad("Invalid numeric constraint");
   if(p.get("maxLength") instanceof Number n && (n.intValue()<0 || n.doubleValue()!=n.intValue())) throw ApiException.bad("maxLength must be a nonnegative integer");
   if(p.get("minimum") instanceof Number min && p.get("maximum") instanceof Number max && min.doubleValue()>max.doubleValue()) throw ApiException.bad("minimum exceeds maximum");
  }
 }
 public static void answers(Map<String,Object> schema,Map<String,Object> answers) {
  schema(schema); if(answers==null) throw ApiException.bad("Answers required");
  Map<?,?> props=(Map<?,?>)schema.get("properties");
  for(Object key:(List<?>)schema.getOrDefault("required",List.of())) if(!answers.containsKey(key) || answers.get(key)==null) throw ApiException.bad("Missing form answer: "+key);
  for(var e:answers.entrySet()) {
   if(!(props.get(e.getKey()) instanceof Map<?,?> p)) throw ApiException.bad("Unknown answer: "+e.getKey());
   Object v=e.getValue();boolean ok=switch(p.get("type").toString()) { case "string" -> v instanceof String;case "boolean" -> v instanceof Boolean;case "number" -> v instanceof Number;case "integer" -> v instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()==Math.rint(n.doubleValue());default -> false; };
   if(!ok) throw ApiException.bad("Invalid answer type: "+e.getKey());
   if(p.get("enum") instanceof List<?> values && !values.contains(v)) throw ApiException.bad("Answer outside enum");
   if(v instanceof Number n) { if(p.get("minimum") instanceof Number min && n.doubleValue()<min.doubleValue()) throw ApiException.bad("Answer below minimum");if(p.get("maximum") instanceof Number max && n.doubleValue()>max.doubleValue()) throw ApiException.bad("Answer above maximum"); }
   if(v instanceof String text && (text.length()>20000 || p.get("maxLength") instanceof Number max && text.length()>max.intValue())) throw ApiException.bad("Answer too long");
  }
 }
}
