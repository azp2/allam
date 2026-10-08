package com.allam.journal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
import static com.allam.journal.Store.ts;
@Service
public class IssueService {
 final Store db;final Access access;final SubmissionService submissions;
 public IssueService(Store db,Access access,SubmissionService submissions) { this.db=db;this.access=access;this.submissions=submissions; }
 @Transactional public String create(Actor actor,int volume,int number,int year,Map<String,String> titles) {
  access.require(actor,Role.EDITOR,Role.JOURNAL_MANAGER);
  if(volume<1 || number<1 || year<1900 || year>2200 || titles==null || titles.isEmpty() || titles.values().stream().anyMatch(v->v==null || v.isBlank())) throw ApiException.bad("Invalid issue metadata");
  String id=db.id();db.jdbc().update("insert into issue(id,volume,issue_number,issue_year,titles) values(?,?,?,?,?)",id,volume,number,year,db.encode(titles));return id;
 }
 Map<String,Object> lock(String id) { return db.jdbc().queryForList("select * from issue where id=? for update",id).stream().findFirst().orElseThrow(ApiException::missing); }
 @Transactional public void schedule(Actor actor,String issue,String submission,int position) {
  Map<String,Object> i=lock(issue);Manuscript s=db.submission(submission,true);access.editorial(actor,s);
  if(i.get("published_at")!=null || s.state()!=State.READY_FOR_PUBLICATION || position<0) throw ApiException.conflict("Issue must be unpublished and article proof-approved");
  db.jdbc().update("insert into issue_article values(?,?,?)",issue,submission,position);
 }
 @Transactional public void publish(Actor actor,String issue) {
  access.require(actor,Role.EDITOR,Role.JOURNAL_MANAGER);Map<String,Object> i=lock(issue);
  if(i.get("published_at")!=null) throw ApiException.conflict("Issue already published");
  List<String> ids=db.jdbc().query("select submission_id from issue_article where issue_id=? order by submission_id",(r,n)->r.getString(1),issue);
  if(ids.isEmpty()) throw ApiException.conflict("Cannot publish an empty issue");
  for(String id:ids) {
   Manuscript s=db.submission(id,true);access.editorial(actor,s);
   submissions.apply(actor,s,Action.PUBLISH,"Issue publication");
  }
  db.jdbc().update("update issue set published_at=? where id=?",ts(Instant.now()),issue);
 }
 @Transactional public void continuous(Actor actor,String id,long version) {
  Manuscript s=db.submission(id,true);access.editorial(actor,s);submissions.checkVersion(s,version);
  if(db.exists("select count(*)>0 from issue_article where submission_id=?",id)) throw ApiException.conflict("Article is scheduled in an issue");
  submissions.apply(actor,s,Action.PUBLISH,"Continuous publication");
 }
 public List<Map<String,Object>> publicIssues() { return db.jdbc().queryForList("select id,volume,issue_number,issue_year,titles,cover_file_id,published_at from issue where published_at is not null order by published_at desc").stream().map(this::localized).toList(); }
 Map<String,Object> localized(Map<String,Object> row) { row.put("titles",db.decode(row.get("titles").toString()));return row; }
 public Map<String,Object> publicIssue(String id) {
  Map<String,Object> issue=db.jdbc().queryForList("select * from issue where id=? and published_at is not null",id).stream().findFirst().orElseThrow(ApiException::missing);localized(issue);
  issue.put("articles",db.jdbc().query("select s.* from submission s join issue_article i on i.submission_id=s.id where i.issue_id=? and s.state='PUBLISHED' order by i.position_no,s.id",db::manuscript,id).stream().map(this::article).toList());return issue;
 }
 public Map<String,Object> article(Manuscript s) {
  Map<String,Object> data=new LinkedHashMap<>();data.put("id",s.id());Map<String,Object> publicMetadata=new LinkedHashMap<>(s.metadata());
  if(s.metadata().get("authors") instanceof List<?> authors) {
   List<Map<String,Object>> publicAuthors=new ArrayList<>();
   for(Object raw:authors) if(raw instanceof Map<?,?> author) { Map<String,Object> item=new LinkedHashMap<>();for(String key:List.of("name","orcid","affiliation")) if(author.get(key)!=null) item.put(key,author.get(key));publicAuthors.add(item); }
   publicMetadata.put("authors",publicAuthors);
  }
  data.put("metadata",publicMetadata);data.put("language",s.language());data.put("doi",s.doi());data.put("publishedAt",s.publishedAt());
  data.put("galleys",db.jdbc().queryForList("select id,format,media_type,size_bytes,sha256 from submission_file where submission_id=? and kind='GALLEY' and revision_no=?",s.id(),s.revision()));return data;
 }
 public Map<String,Object> publicArticle(String id) { Manuscript s=db.submission(id,false);if(s.state()!=State.PUBLISHED) throw ApiException.missing();return article(s); }
}
