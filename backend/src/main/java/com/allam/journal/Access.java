package com.allam.journal;
import org.springframework.stereotype.Component;
import java.util.Objects;
import static com.allam.journal.Domain.*;
@Component
public class Access {
 final Store db;
 public Access(Store db) { this.db=db; }
 public void require(Actor a,Role... roles) { if(!a.has(roles)) throw ApiException.forbidden(); }
 public boolean editor(Actor a,Manuscript s) {
  if(a.id().equals(s.ownerId())) return false;
  return a.has(Role.EDITOR,Role.JOURNAL_MANAGER) || a.grants().stream().anyMatch(g -> g.role()==Role.SECTION_EDITOR && Objects.equals(g.sectionId(),s.sectionId()) && Objects.equals(a.id(),s.editorId()));
 }
 public void editorial(Actor a,Manuscript s) { if(!editor(a,s)) throw ApiException.forbidden(); }
 public void manager(Actor a) { require(a,Role.JOURNAL_MANAGER,Role.SITE_MANAGER); }
 public void owner(Actor a,Manuscript s) { if(!a.id().equals(s.ownerId()) || !a.has(Role.AUTHOR)) throw ApiException.forbidden(); }
 public boolean staff(Actor a,Manuscript s,Role role) { return a.has(role) && db.exists("select count(*)>0 from staff_assignment where submission_id=? and user_id=? and role=?",s.id(),a.id(),role.name()); }
 public boolean read(Actor a,Manuscript s) { return s.ownerId().equals(a.id()) && a.has(Role.AUTHOR) || editor(a,s) || db.exists("select count(*)>0 from staff_assignment sa join user_role r on r.user_id=sa.user_id and r.role=sa.role where sa.submission_id=? and sa.user_id=?",s.id(),a.id()); }
}
