package com.allam.journal;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.Map;
@RestController
@RequestMapping("/api")
public class CoverController {
 final Store db;final CoverService covers;
 public CoverController(Store db,CoverService covers) { this.db=db;this.covers=covers; }
 @PostMapping(value="/covers",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
 public Map<String,String> upload(Principal p,@RequestPart("file") MultipartFile file) throws Exception {return Map.of("id",covers.upload(db.actor(p.getName()),file));}
 record CoverInput(String coverId) {}
 @PutMapping("/issues/{id}/cover") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void attach(Principal p,@PathVariable String id,@RequestBody CoverInput input) { if(input.coverId()==null) throw ApiException.bad("coverId required");covers.attach(db.actor(p.getName()),id,input.coverId()); }
 @GetMapping("/public/covers/{id}") public ResponseEntity<Resource> cover(@PathVariable String id) {return covers.publicCover(id);}
}
