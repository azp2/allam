package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static com.allam.journal.Xml.escape;
@RestController
@RequestMapping("/api/preservation")
public class PreservationController {
 final Store db;final IssueService issues;final FileService files;final String base,name;
 public PreservationController(Store db,IssueService issues,FileService files,@Value("${journal.base-url}") String base,@Value("${journal.name}") String name) { this.db=db;this.issues=issues;this.files=files;this.base=base;this.name=name; }
 @GetMapping(value="/manifest",produces=MediaType.TEXT_HTML_VALUE)
 public String manifest() {
  StringBuilder html=new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>"+escape(name)+" preservation manifest</title></head><body><h1>"+escape(name)+"</h1><p>Preservation access to published content. Network harvesting requires publisher permission and network agreement.</p><ul>");
  for(var issue:issues.publicIssues()) html.append("<li><a href=\"").append(escape(base+"/api/preservation/issues/"+issue.get("id")+"/manifest")).append("\">Volume ").append(issue.get("volume")).append(" issue ").append(issue.get("issue_number")).append("</a></li>");
  // Include continuously published articles too.
  for(var article:db.jdbc().queryForList("select id from submission where state='PUBLISHED'")) html.append("<li><a href=\"").append(escape(base+"/articles/"+article.get("id"))).append("\">Article ").append(escape(article.get("id"))).append("</a></li>");
  return html.append("</ul></body></html>").toString();
 }
 @GetMapping(value="/issues/{id}/manifest",produces=MediaType.TEXT_HTML_VALUE)
 public String issueManifest(@PathVariable String id) {
  issues.publicIssue(id);StringBuilder html=new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Published issue</title></head><body><ul>");
  for(var f:issueFiles(id)) html.append("<li><a href=\"").append(escape(base+"/api/public/galleys/"+f.get("id")+"/download")).append("\">").append(escape(f.get("id"))).append("</a></li>");
  return html.append("</ul></body></html>").toString();
 }
 @GetMapping("/issues/{id}/inventory") public Map<String,Object> inventory(@PathVariable String id) { return Map.of("issue",issues.publicIssue(id),"files",issueFiles(id)); }
 List<Map<String,Object>> issueFiles(String id) { return db.jdbc().queryForList("select f.id,f.submission_id,f.media_type,f.size_bytes,f.sha256 from submission_file f join submission s on s.id=f.submission_id join issue_article a on a.submission_id=s.id where a.issue_id=? and s.state='PUBLISHED' and f.kind='GALLEY' and f.revision_no=s.revision_no order by f.id",id); }
 @GetMapping("/issues/{id}/package.zip") public ResponseEntity<StreamingResponseBody> deposit(@PathVariable String id) {
  Map<String,Object> metadata=issues.publicIssue(id);List<Map<String,Object>> inventory=issueFiles(id);
  StreamingResponseBody body=output->{
   try(ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
    write(zip,"bagit.txt","BagIt-Version: 1.0\nTag-File-Character-Encoding: UTF-8\n");StringBuilder checksums=new StringBuilder();
    byte[] json=db.encode(metadata).getBytes(StandardCharsets.UTF_8);zip.putNextEntry(new ZipEntry("data/issue.json"));zip.write(json);zip.closeEntry();checksums.append(OrcidService.hash(new String(json,StandardCharsets.UTF_8))).append("  data/issue.json\n");
    for(var file:inventory) {
     String fileId=file.get("id").toString(),path="data/galleys/"+fileId;zip.putNextEntry(new ZipEntry(path));
     try(InputStream input=files.download(null,fileId,true).getInputStream()) { input.transferTo(zip); }zip.closeEntry();checksums.append(file.get("sha256")).append("  ").append(path).append("\n");
    }
    write(zip,"manifest-sha256.txt",checksums.toString());write(zip,"bag-info.txt","Source-Organization: "+name.replace("\n", " ")+"\nExternal-Identifier: "+id+"\n");
   }
  };
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=issue-"+id+".zip").body(body);
 }
 void write(ZipOutputStream zip,String path,String text) throws IOException {zip.putNextEntry(new ZipEntry(path));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
