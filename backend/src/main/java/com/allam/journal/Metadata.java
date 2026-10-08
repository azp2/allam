package com.allam.journal;
import java.util.*;
import java.util.regex.Pattern;
public final class Metadata {
 private Metadata() {}
 public static void validate(Map<String,Object> metadata,String language,boolean complete) {
  if(metadata==null || metadata.size()>20) throw ApiException.bad("Invalid metadata");
  if(!Set.of("title","abstract","authors","keywords","funding","references").containsAll(metadata.keySet())) throw ApiException.bad("Unknown metadata field");
  for(String field:List.of("title","abstract")) {
   Object raw=metadata.get(field);
   if(raw==null && !complete) continue;
   if(!(raw instanceof Map<?,?> translations) || translations.isEmpty()) throw ApiException.bad(field+" must be a locale-to-text object");
   for(var e:translations.entrySet()) if(!(e.getKey() instanceof String locale) || !locale.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*") || !(e.getValue() instanceof String text) || text.isBlank() || text.length()>20000) throw ApiException.bad("Invalid localized "+field);
   if(complete && !translations.containsKey(language)) throw ApiException.bad("Missing "+language+" translation for "+field);
  }
  Object raw=metadata.get("authors");
  if(raw==null && !complete) return;
  if(!(raw instanceof List<?> authors) || authors.isEmpty() || authors.size()>100) throw ApiException.bad("Authors are required");
  for(Object author:authors) {
   if(!(author instanceof Map<?,?> a) || !(a.get("name") instanceof String name) || name.isBlank() || name.length()>200 || !(a.get("email") instanceof String email) || email.length()>254 || !Pattern.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+",email)) throw ApiException.bad("Each author needs a name and email");
   if(a.get("orcid")!=null && (!(a.get("orcid") instanceof String orcid) || !orcid.matches("[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]"))) throw ApiException.bad("Invalid ORCID format; verification requires OAuth");
  }
  for(String field:List.of("keywords","funding","references")) if(metadata.containsKey(field) && (!(metadata.get(field) instanceof List<?> values) || values.size()>2000)) throw ApiException.bad(field+" must be an array");
 }
 public static String text(Map<String,Object> metadata,String field,String language) {
  Object raw=metadata.get(field);if(!(raw instanceof Map<?,?> m) || m.isEmpty()) return "";
  return Objects.toString(m.containsKey(language)?m.get(language):m.values().iterator().next(),"");
 }
}
