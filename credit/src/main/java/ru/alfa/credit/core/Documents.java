package ru.alfa.credit.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.alfa.credit.platform.*;

@Service
public class Documents {
  private final JdbcTemplate db;
  private final Access access;
  private final DocumentStorage storage;
  private final Json json;
  private final TransactionTemplate tx;
  private final Commands commands;
  private final Jobs jobs;

  public Documents(
      JdbcTemplate db,
      Access access,
      DocumentStorage storage,
      Json json,
      TransactionTemplate tx,
      Commands commands,
      Jobs jobs) {
    this.db = db;
    this.access = access;
    this.storage = storage;
    this.json = json;
    this.tx = tx;
    this.commands = commands;
    this.jobs = jobs;
  }

  public UUID upload(
      UUID user,
      UUID company,
      String kind,
      String filename,
      String contentType,
      byte[] content,
      UUID command) {
    access.company(user, company);
    if (content.length == 0 || content.length > 10 * 1024 * 1024)
      throw Problem.invalid("DOCUMENT_SIZE");
    if (!Set.of("FINANCIAL_REPORT", "OTHER").contains(kind)) throw Problem.invalid("DOCUMENT_KIND");
    if (filename == null
        || filename.length() > 200
        || filename.contains("/")
        || filename.contains("\\")
        || filename.chars().anyMatch(c -> c < 32)) throw Problem.invalid("DOCUMENT_FILENAME");
    JsonNode metadata;
    if ("application/json".equals(contentType)) {
      try {
        metadata = json.read(new String(content, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        throw Problem.invalid("DOCUMENT_SIGNATURE");
      }
      if (!metadata.isObject()) throw Problem.invalid("DOCUMENT_SIGNATURE");
      if ("FINANCIAL_REPORT".equals(kind)
          && (!Inn.valid(metadata.path("inn").asText())
              || !metadata.path("period").asText().matches("[0-9]{4}-(0[1-9]|1[0-2])")
              || !metadata.path("revenueMinor").canConvertToLong()
              || !metadata.path("revenueMinor").isIntegralNumber()
              || !metadata.path("expensesMinor").canConvertToLong()
              || !metadata.path("expensesMinor").isIntegralNumber()
              || metadata.path("revenueMinor").asLong() < 0
              || metadata.path("expensesMinor").asLong() < 0))
        throw Problem.invalid("DOCUMENT_REPORT_SCHEMA");
    } else if ("application/pdf".equals(contentType)
        && content.length >= 5
        && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")
        && "OTHER".equals(kind)) metadata = json.tree(Map.of("format", "PDF"));
    else throw Problem.invalid("DOCUMENT_SIGNATURE");
    String checksum = Json.sha256(content);
    // Persist a staging object and its cleanup task before S3 IO. A crash cannot leave an untracked
    // key.
    UUID id =
        tx.execute(
            s ->
                commands.run(
                    user,
                    command,
                    List.of("upload", company, kind, filename, contentType, checksum),
                    () -> {
                      UUID doc = UUID.randomUUID();
                      String key = company + "/" + doc;
                      db.update(
                          "insert into"
                              + " core.document(id,company_id,uploaded_by,object_key,checksum,kind,content_type,filename,size,metadata)"
                              + " values (?,?,?,?,?,?,?,?,?,?::jsonb)",
                          doc,
                          company,
                          user,
                          key,
                          checksum,
                          kind,
                          contentType,
                          filename,
                          content.length,
                          json.write(metadata));
                      jobs.enqueueAt(
                          "CLEAN_DOCUMENT",
                          "clean:" + doc,
                          Map.of("documentId", doc),
                          Instant.now().plusSeconds(3600));
                      return doc;
                    }));
    tx.executeWithoutResult(
        s -> {
          var doc = db.queryForMap("select * from core.document where id=? for update", id);
          if ("READY".equals(doc.get("state"))) return;
          if (!"PENDING".equals(doc.get("state")))
            throw Problem.conflict("UPLOAD_EXPIRED_USE_NEW_COMMAND");
          storage.put(doc.get("object_key").toString(), content, contentType);
          db.update("update core.document set state='READY' where id=?", id);
          access.audit(user, "DOCUMENT_UPLOAD", id);
        });
    return id;
  }

  public record Content(byte[] bytes, String contentType, String filename) {}

  public Content download(UUID user, UUID id) {
    var docs = db.queryForList("select * from core.document where id=? and state='READY'", id);
    if (docs.isEmpty()) throw Problem.missing();
    var doc = docs.getFirst();
    access.company(user, (UUID) doc.get("company_id"));
    byte[] bytes = storage.get(doc.get("object_key").toString());
    if (!Json.sha256(bytes).equals(doc.get("checksum")))
      throw new IllegalStateException("DOCUMENT_CHECKSUM");
    access.audit(user, "DOCUMENT_DOWNLOAD", id);
    return new Content(bytes, doc.get("content_type").toString(), doc.get("filename").toString());
  }

  public void cleanup(UUID id) {
    var doc = db.queryForMap("select * from core.document where id=? for update", id);
    if ("PENDING".equals(doc.get("state"))) {
      storage.delete(doc.get("object_key").toString());
      db.update("update core.document set state='DELETED' where id=?", id);
    }
  }
}
