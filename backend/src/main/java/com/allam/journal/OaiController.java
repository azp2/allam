package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.allam.journal.Domain.*;
import static com.allam.journal.Xml.escape;
@RestController
public class OaiController {
 final Store db;final String base,name,email,secret;
 static final String PREFIX="oai:allam:";
 static class OaiError extends RuntimeException { final String code;OaiError(String code,String message) { super(message);this.code=code; } }
 public OaiController(Store db,@Value("${journal.base-url}") String base,@Value("${journal.name}") String name,@Value("${journal.email}") String email,@Value("${journal.oai-token-secret:}") String secret) { this.db=db;this.base=base;this.name=name;this.email=email;this.secret=secret.isBlank()?UUID.randomUUID().toString():secret; }
 @RequestMapping(value="/oai",method={RequestMethod.GET,RequestMethod.POST},produces=MediaType.APPLICATION_XML_VALUE)
 public String harvest(@RequestParam MultiValueMap<String,String> parameters) {
  String verb=parameters.getFirst("verb"),body;boolean validRequest=true;
  try {
   for(var entry:parameters.entrySet()) if(entry.getValue().size()!=1) throw error("badArgument","Repeated argument");
   if(verb==null || !Set.of("Identify","ListMetadataFormats","ListSets","GetRecord","ListRecords","ListIdentifiers").contains(verb)) throw error("badVerb","Unknown verb");
   Set<String> allowed=switch(verb) {case "Identify" -> Set.of("verb");case "ListMetadataFormats" -> Set.of("verb","identifier");case "ListSets" -> Set.of("verb","resumptionToken");case "GetRecord" -> Set.of("verb","identifier","metadataPrefix");default -> Set.of("verb","metadataPrefix","from","until","set","resumptionToken");};
   if(!allowed.containsAll(parameters.keySet())) throw error("badArgument","Unknown argument");
   body=switch(verb) {
    case "Identify" -> identify();
    case "ListMetadataFormats" -> formats(parameters.getFirst("identifier"));
    case "ListSets" -> sets(parameters.getFirst("resumptionToken"));
    case "GetRecord" -> { prefix(parameters.getFirst("metadataPrefix"));yield "<GetRecord>"+record(find(parameters.getFirst("identifier")),false)+"</GetRecord>"; }
    default -> records(verb,parameters);
   };
  } catch(OaiError e) { validRequest=!Set.of("badVerb","badArgument").contains(e.code);body="<error code=\""+e.code+"\">"+escape(e.getMessage())+"</error>"; }
  StringBuilder request=new StringBuilder("<request");if(validRequest) for(var entry:parameters.entrySet()) request.append(" ").append(entry.getKey()).append("=\"").append(escape(entry.getValue().getFirst())).append("\"");
  request.append(">").append(escape(base+"/oai")).append("</request>");
  return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.openarchives.org/OAI/2.0/ http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd\"><responseDate>"+Instant.now().truncatedTo(ChronoUnit.SECONDS)+"</responseDate>"+request+body+"</OAI-PMH>";
 }
 OaiError error(String code,String message) { return new OaiError(code,message); }
 String identify() {
  List<Manuscript> first=db.jdbc().query("select * from submission where state='PUBLISHED' order by updated_at,id limit 1",db::manuscript);
  String earliest=first.isEmpty()?"1970-01-01T00:00:00Z":first.getFirst().updatedAt().truncatedTo(ChronoUnit.SECONDS).toString();
  return "<Identify><repositoryName>"+escape(name)+"</repositoryName><baseURL>"+escape(base+"/oai")+"</baseURL><protocolVersion>2.0</protocolVersion><adminEmail>"+escape(email)+"</adminEmail><earliestDatestamp>"+earliest+"</earliestDatestamp><deletedRecord>no</deletedRecord><granularity>YYYY-MM-DDThh:mm:ssZ</granularity></Identify>";
 }
 String formats(String identifier) {
  if(identifier!=null) find(identifier);
  return "<ListMetadataFormats><metadataFormat><metadataPrefix>oai_dc</metadataPrefix><schema>http://www.openarchives.org/OAI/2.0/oai_dc.xsd</schema><metadataNamespace>http://www.openarchives.org/OAI/2.0/oai_dc/</metadataNamespace></metadataFormat></ListMetadataFormats>";
 }
 String sets(String token) {
  if(token!=null) throw error("badResumptionToken","ListSets is not paginated");
  List<Map<String,Object>> sections=db.jdbc().queryForList("select id,names from section order by id");if(sections.isEmpty()) throw error("noSetHierarchy","No sections configured");
  StringBuilder result=new StringBuilder("<ListSets>");
  for(var s:sections) result.append("<set><setSpec>").append(escape(s.get("id"))).append("</setSpec><setName>").append(escape(db.decode(s.get("names").toString()).values().iterator().next())).append("</setName></set>");
  return result.append("</ListSets>").toString();
 }
 void prefix(String prefix) { if(prefix==null) throw error("badArgument","metadataPrefix required");if(!prefix.equals("oai_dc")) throw error("cannotDisseminateFormat","Only oai_dc is supported"); }
 Manuscript find(String identifier) {
  if(identifier==null) throw error("badArgument","identifier required");
  if(!identifier.startsWith(PREFIX)) throw error("idDoesNotExist","Unknown identifier");
  List<Manuscript> rows=db.jdbc().query("select * from submission where id=? and state='PUBLISHED'",db::manuscript,identifier.substring(PREFIX.length()));if(rows.isEmpty()) throw error("idDoesNotExist","Unknown identifier");return rows.getFirst();
 }
 Instant date(String value,boolean until) {
  if(value==null) return until?Instant.now():Instant.EPOCH;
  try {
   if(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(until?86400:0);
   if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) throw new IllegalArgumentException();
   return Instant.parse(value).plusSeconds(until?1:0);
  } catch(Exception e) { throw error("badArgument","Invalid UTC datestamp"); }
 }
 String records(String verb,MultiValueMap<String,String> p) {
  Map<String,Object> criteria;String token=p.getFirst("resumptionToken");
  if(token!=null) {
   if(p.size()!=2) throw error("badArgument","Token cannot be combined with selection arguments");criteria=readToken(token);
   if(!verb.equals(criteria.get("verb"))) throw error("badResumptionToken","Token belongs to another verb");
  } else {
   prefix(p.getFirst("metadataPrefix"));String from=p.getFirst("from"),until=p.getFirst("until");
   if(from!=null && until!=null && from.length()!=until.length()) throw error("badArgument","Date granularities must match");
   Instant start=date(from,false),end=date(until,true);if(!start.isBefore(end)) throw error("badArgument","Invalid date range");
   criteria=new LinkedHashMap<>();criteria.put("verb",verb);criteria.put("from",start.toString());criteria.put("until",end.toString());criteria.put("snapshot",Instant.now().toString());criteria.put("set",Objects.toString(p.getFirst("set"),""));criteria.put("lastAt",Instant.EPOCH.toString());criteria.put("lastId","");criteria.put("expires",Instant.now().plusSeconds(3600).toString());
  }
  String set=criteria.get("set").toString();
  Instant from=Instant.parse(criteria.get("from").toString()),until=Instant.parse(criteria.get("until").toString()),snapshot=Instant.parse(criteria.get("snapshot").toString()),last=Instant.parse(criteria.get("lastAt").toString());
  String sql="select * from submission where state='PUBLISHED' and updated_at>=? and updated_at<? and updated_at<=? and (updated_at>? or (updated_at=? and id>?))"+(set.isEmpty()?"":" and section_id=?")+" order by updated_at,id limit 101";
  List<Object> args=new ArrayList<>(List.of(Store.ts(from),Store.ts(until),Store.ts(snapshot),Store.ts(last),Store.ts(last),criteria.get("lastId")));if(!set.isEmpty()) args.add(set);
  List<Manuscript> rows=db.jdbc().query(sql,db::manuscript,args.toArray());if(rows.isEmpty()) throw error("noRecordsMatch","No published records match");
  StringBuilder result=new StringBuilder("<"+verb+">");for(Manuscript s:rows.subList(0,Math.min(100,rows.size()))) result.append(record(s,verb.equals("ListIdentifiers")));
  if(rows.size()>100) { Manuscript lastRow=rows.get(99);criteria.put("lastAt",lastRow.updatedAt().toString());criteria.put("lastId",lastRow.id());result.append("<resumptionToken>").append(writeToken(criteria)).append("</resumptionToken>"); }
  else if(token!=null) result.append("<resumptionToken/>");
  return result.append("</"+verb+">").toString();
 }
 String record(Manuscript s,boolean headerOnly) {
  String header="<header><identifier>"+PREFIX+s.id()+"</identifier><datestamp>"+s.updatedAt().truncatedTo(ChronoUnit.SECONDS)+"</datestamp><setSpec>"+escape(s.sectionId())+"</setSpec></header>";
  if(headerOnly) return header;
  StringBuilder dc=new StringBuilder("<record>"+header+"<metadata><oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.openarchives.org/OAI/2.0/oai_dc/ http://www.openarchives.org/OAI/2.0/oai_dc.xsd\">");
  for(String field:List.of("title","abstract")) if(s.metadata().get(field) instanceof Map<?,?> translations) for(var e:translations.entrySet()) dc.append("<dc:").append(field.equals("abstract")?"description":"title").append(" xml:lang=\"").append(escape(e.getKey())).append("\">").append(escape(e.getValue())).append("</dc:").append(field.equals("abstract")?"description":"title").append(">");
  if(s.metadata().get("authors") instanceof List<?> authors) for(Object a:authors) if(a instanceof Map<?,?> author) dc.append("<dc:creator>").append(escape(author.get("name"))).append("</dc:creator>");
  if(s.metadata().get("keywords") instanceof List<?> keywords) for(Object k:keywords) dc.append("<dc:subject>").append(escape(k)).append("</dc:subject>");
  dc.append("<dc:identifier>").append(escape(base+"/articles/"+s.id())).append("</dc:identifier>");if(s.doi()!=null) dc.append("<dc:identifier>https://doi.org/").append(escape(s.doi())).append("</dc:identifier>");
  return dc.append("<dc:publisher>").append(escape(name)).append("</dc:publisher><dc:date>").append(s.publishedAt().atZone(ZoneOffset.UTC).toLocalDate()).append("</dc:date><dc:language>").append(escape(s.language())).append("</dc:language><dc:type>Text</dc:type></oai_dc:dc></metadata></record>").toString();
 }
 String sign(String value) {
  try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); }
 }
 String writeToken(Map<String,Object> criteria) { String value=Base64.getUrlEncoder().withoutPadding().encodeToString(db.encode(criteria).getBytes(StandardCharsets.UTF_8));return value+"."+sign(value); }
 Map<String,Object> readToken(String token) {
  try {
   if(token.length()>4000) throw new IllegalArgumentException();String[] parts=token.split("\\.");if(parts.length!=2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
   Map<String,Object> criteria=db.decode(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));if(!Instant.parse(criteria.get("expires").toString()).isAfter(Instant.now())) throw new IllegalArgumentException();return criteria;
  } catch(Exception e) { throw error("badResumptionToken","Invalid or expired token"); }
 }
}
