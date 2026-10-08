package com.allam.journal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.allam.journal.Domain.*;
import static com.allam.journal.Store.ts;
@Service
public class FileService {
 final Store db;final Access access;private final Path root;
 public FileService(Store db,Access access,@Value("${journal.storage}") String path) throws IOException {
  this.db=db;this.access=access;this.root=Path.of(path).toAbsolutePath().normalize();Files.createDirectories(root);
 }
 public Path storageRoot() { return root; }
 @Transactional public String upload(Actor actor,String submission,FileKind kind,Format format,String assignment,MultipartFile upload) throws Exception {
  Manuscript s=db.submission(submission,true);boolean owner=s.ownerId().equals(actor.id()) && actor.has(Role.AUTHOR);
  boolean editable=Set.of(State.DRAFT,State.RETURNED_FOR_CORRECTIONS,State.REVISIONS_REQUIRED).contains(s.state());
  boolean editor=access.editor(actor,s);int revision=s.revision();
  switch(kind) {
   case MANUSCRIPT,SUPPLEMENT -> { if(!owner || !editable || s.state()==State.REVISIONS_REQUIRED && kind==FileKind.MANUSCRIPT) throw ApiException.forbidden(); }
   case REVISION -> { if(!owner || s.state()!=State.REVISIONS_REQUIRED) throw ApiException.forbidden();revision++; }
   case BLIND_MANUSCRIPT -> { if(!editor || !Set.of(State.SUBMITTED_UNASSIGNED,State.REVISION_SUBMITTED).contains(s.state())) throw ApiException.forbidden(); }
   case GALLEY -> { if(s.state()!=State.IN_PRODUCTION || !access.staff(actor,s,Role.LAYOUT_EDITOR) || format==null) throw ApiException.forbidden(); }
   case ANNOTATION -> {
    if(assignment==null) throw ApiException.bad("Review assignment required");Review r=db.review(assignment);
    if(!r.submissionId().equals(submission) || !r.reviewerId().equals(actor.id()) || !actor.has(Role.REVIEWER) || !r.status().equals("ACCEPTED") || !Workflow.reviewing(s.state()) || r.round()!=s.round()) throw ApiException.forbidden();revision=r.revision();
   }
   case COVER -> throw ApiException.bad("Use /api/covers for issue cover images");
  }
  if(upload.isEmpty() || upload.getSize()>25L*1024*1024) throw ApiException.bad("Invalid file size");
  String name=Objects.toString(upload.getOriginalFilename(),"file").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "_");
  if(name.length()>255 || name.isBlank()) throw ApiException.bad("Invalid filename");
  byte[] bytes=upload.getBytes();String media=detect(name,bytes,kind,format);
  String key=db.id();Path target=root.resolve(key);Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
   @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) try { Files.deleteIfExists(target); } catch(IOException ignored) {} }
  });
  String id=db.id();String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  db.jdbc().update("insert into submission_file(id,submission_id,uploader_id,kind,format,revision_no,original_name,media_type,storage_key,size_bytes,sha256,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",id,submission,actor.id(),kind.name(),format==null?null:format.name(),revision,name,media,key,bytes.length,digest,ts(Instant.now()));
  if(kind==FileKind.ANNOTATION) db.jdbc().update("insert into review_file values(?,?)",assignment,id);
  db.jdbc().update("update submission set version=version+1,updated_at=? where id=?",ts(Instant.now()),submission);
  // Uploading another galley is disallowed after proof approval by the state guard above.
  return id;
 }
 private String detect(String name,byte[] b,FileKind kind,Format format) {
  String lower=name.toLowerCase(Locale.ROOT);String head=new String(b,0,Math.min(b.length,1024),java.nio.charset.StandardCharsets.UTF_8).stripLeading();
  String type;
  if(lower.endsWith(".pdf") && head.startsWith("%PDF-")) type="application/pdf";
  else if(lower.endsWith(".docx") && b.length>4 && b[0]=='P' && b[1]=='K') type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  else if(lower.endsWith(".xml") && head.startsWith("<")) type="application/xml";
  else if(lower.endsWith(".html") && head.startsWith("<")) type="text/html";
  else if(kind==FileKind.SUPPLEMENT && (lower.endsWith(".csv") || lower.endsWith(".txt"))) type="text/plain";
  else if(kind==FileKind.SUPPLEMENT && lower.endsWith(".png") && b.length>8 && b[0]==(byte)137 && b[1]=='P' && b[2]=='N' && b[3]=='G') type="image/png";
  else if(kind==FileKind.SUPPLEMENT && (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && b.length>3 && b[0]==(byte)255 && b[1]==(byte)216) type="image/jpeg";
  else throw ApiException.bad("Unsupported file or invalid signature");
  if(kind==FileKind.GALLEY && !(format==Format.PDF && type.equals("application/pdf") || format==Format.HTML && type.equals("text/html") || format==Format.JATS_XML && type.equals("application/xml"))) throw ApiException.bad("Galley format and content differ");
  return type;
 }
 @Transactional public void approveBlind(Actor actor,String id) {
  Map<String,Object> file=row(id);Manuscript s=db.submission(file.get("submission_id").toString(),true);access.editorial(actor,s);
  if(!Set.of(State.SUBMITTED_UNASSIGNED,State.REVISION_SUBMITTED).contains(s.state())) throw ApiException.conflict("Blinded content is frozen during review");
  if(!file.get("kind").equals("BLIND_MANUSCRIPT")) throw ApiException.bad("Not a blinded manuscript");
  db.jdbc().update("update submission_file set blind_approved=true where id=?",id);
  db.jdbc().update("update submission set version=version+1,updated_at=? where id=?",ts(Instant.now()),s.id());
 }
 public Map<String,Object> row(String id) { return db.jdbc().queryForList("select * from submission_file where id=?",id).stream().findFirst().orElseThrow(ApiException::missing); }
 public Resource download(Actor actor,String id,boolean publicOnly) {
  Map<String,Object> f=row(id);Manuscript s=db.submission(f.get("submission_id").toString(),false);String kind=f.get("kind").toString();
  boolean allowed=s.state()==State.PUBLISHED && kind.equals("GALLEY");
  if(!publicOnly && actor!=null) {
   if(access.editor(actor,s)) allowed=true;
   else if(actor.id().equals(s.ownerId()) && actor.has(Role.AUTHOR)) allowed |= !Set.of("ANNOTATION","BLIND_MANUSCRIPT").contains(kind);
   else if(access.staff(actor,s,Role.COPYEDITOR) || access.staff(actor,s,Role.LAYOUT_EDITOR) || access.staff(actor,s,Role.PROOFREADER)) allowed |= !kind.equals("ANNOTATION");
   else if(actor.has(Role.REVIEWER)) {
    if(kind.equals("BLIND_MANUSCRIPT") && Boolean.TRUE.equals(f.get("blind_approved"))) allowed |= db.exists("select count(*)>0 from review_assignment where submission_id=? and reviewer_id=? and revision_no=? and status in ('ACCEPTED','SUBMITTED')",s.id(),actor.id(),f.get("revision_no"));
    if(kind.equals("ANNOTATION")) allowed |= db.exists("select count(*)>0 from review_file rf join review_assignment r on r.id=rf.assignment_id where rf.file_id=? and r.reviewer_id=?",id,actor.id());
   }
  }
  if(!allowed) throw ApiException.forbidden();
  Path path=root.resolve(f.get("storage_key").toString()).normalize();if(!path.getParent().equals(root) || !Files.isRegularFile(path)) throw ApiException.missing();return new FileSystemResource(path);
 }
 public List<Map<String,Object>> list(Actor actor,String submission) {
  Manuscript s=db.submission(submission,false);if(!access.read(actor,s)) throw ApiException.forbidden();
  boolean editor=access.editor(actor,s) && !actor.id().equals(s.ownerId());
  String sql="select id,kind,format,revision_no,media_type,size_bytes,sha256 from submission_file where submission_id=?";
  if(!editor) sql+=" and kind not in ('ANNOTATION','BLIND_MANUSCRIPT')";
  return db.jdbc().queryForList(sql,submission);
 }
}
