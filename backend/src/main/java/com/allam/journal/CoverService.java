package com.allam.journal;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.allam.journal.Domain.*;
@Service
public class CoverService {
 final Store db;final Access access;final FileService files;final IssueService issues;
 public CoverService(Store db,Access access,FileService files,IssueService issues) { this.db=db;this.access=access;this.files=files;this.issues=issues; }
 @Transactional public String upload(Actor actor,MultipartFile file) throws Exception {
  access.require(actor,Role.EDITOR,Role.JOURNAL_MANAGER);
  if(file.isEmpty() || file.getSize()>5L*1024*1024) throw ApiException.bad("Cover must be at most 5MB");
  byte[] bytes=file.getBytes();String type;
  if(bytes.length>8 && bytes[0]==(byte)137 && bytes[1]=='P' && bytes[2]=='N' && bytes[3]=='G') type="image/png";
  else if(bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216) type="image/jpeg";
  else throw ApiException.bad("PNG or JPEG cover required");
  String id=db.id();Path path=files.storageRoot().resolve(id);Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) try { Files.deleteIfExists(path); } catch(Exception ignored) {} } });
  db.jdbc().update("insert into issue_cover values(?,?,?,?,?)",id,id,type,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));return id;
 }
 @Transactional public void attach(Actor actor,String issue,String cover) {
  access.require(actor,Role.EDITOR,Role.JOURNAL_MANAGER);Map<String,Object> row=issues.lock(issue);
  if(row.get("published_at")!=null) throw ApiException.conflict("Published issue is immutable");
  db.jdbc().update("update issue set cover_file_id=? where id=?",cover,issue);
 }
 public ResponseEntity<Resource> publicCover(String id) {
  if(!db.exists("select count(*)>0 from issue where cover_file_id=? and published_at is not null",id)) throw ApiException.missing();
  Map<String,Object> row=db.jdbc().queryForList("select * from issue_cover where id=?",id).stream().findFirst().orElseThrow(ApiException::missing);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.get("media_type").toString())).header("X-Content-Type-Options","nosniff").body(new FileSystemResource(files.storageRoot().resolve(row.get("storage_key").toString())));
 }
}
