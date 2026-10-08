package com.allam.journal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.time.ZoneOffset;
import java.util.*;
import static com.allam.journal.Domain.*;
import static com.allam.journal.Xml.escape;
@RestController
public class PublicPages {
 final Store db;final IssueService issues;final Analytics analytics;final String base,name;
 public PublicPages(Store db,IssueService issues,Analytics analytics,@Value("${journal.base-url}") String base,@Value("${journal.name}") String name) { this.db=db;this.issues=issues;this.analytics=analytics;this.base=base;this.name=name; }
 @GetMapping(value="/articles/{id}",produces=MediaType.TEXT_HTML_VALUE)
 public String article(@PathVariable String id,HttpServletRequest request) {
  issues.publicArticle(id);Manuscript s=db.submission(id,false);analytics.track(id,null,"ABSTRACT_VIEW",request);
  String title=Metadata.text(s.metadata(),"title",s.language());StringBuilder html=new StringBuilder("<!doctype html><html lang=\""+escape(s.language())+"\"><head><meta charset=\"utf-8\"><title>"+escape(title)+"</title>");
  meta(html,"citation_title",title);meta(html,"citation_journal_title",name);meta(html,"citation_publication_date",s.publishedAt().atZone(ZoneOffset.UTC).toLocalDate().toString().replace('-','/'));if(s.doi()!=null) meta(html,"citation_doi",s.doi());
  if(s.metadata().get("authors") instanceof List<?> authors) for(Object raw:authors) if(raw instanceof Map<?,?> author) meta(html,"citation_author",author.get("name"));
  List<Map<String,Object>> galleys=db.jdbc().queryForList("select id,format from submission_file where submission_id=? and kind='GALLEY' and revision_no=?",id,s.revision());
  for(var galley:galleys) if("PDF".equals(galley.get("format"))) {meta(html,"citation_pdf_url",base+"/api/public/galleys/"+galley.get("id")+"/download");break;}
  for(var issue:db.jdbc().queryForList("select i.volume,i.issue_number from issue i join issue_article a on a.issue_id=i.id where a.submission_id=? and i.published_at is not null",id)) {meta(html,"citation_volume",issue.get("volume"));meta(html,"citation_issue",issue.get("issue_number"));}
  html.append("</head><body><main><h1>").append(escape(title)).append("</h1><p>").append(escape(Metadata.text(s.metadata(),"abstract",s.language()))).append("</p><ul>");
  for(var galley:galleys) html.append("<li><a href=\"").append(escape(base+"/api/public/galleys/"+galley.get("id")+"/download")).append("\">").append(escape(galley.get("format"))).append("</a></li>");
  return html.append("</ul></main></body></html>").toString();
 }
 void meta(StringBuilder html,String key,Object value) { html.append("<meta name=\"").append(key).append("\" content=\"").append(escape(value)).append("\">"); }
}
