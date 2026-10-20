package ru.alfa.credit.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.kagkarlsson.scheduler.*;
import com.github.kagkarlsson.scheduler.task.*;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class Jobs {
  private static final Logger LOG = LoggerFactory.getLogger(Jobs.class);
  private static final long[] BACKOFF = {2, 5, 15, 30, 60};
  private final JdbcTemplate db;
  private final Json json;
  private final TransactionTemplate tx;
  private final ObjectProvider<JobHandler> handler;
  private final MeterRegistry metrics;
  private final String schema;
  private final SchedulerClient client;
  private final Task<Void> task;

  public Jobs(
      DataSource ds,
      JdbcTemplate db,
      Json json,
      TransactionTemplate tx,
      ObjectProvider<JobHandler> handler,
      MeterRegistry metrics,
      @Value("${jobs.schema}") String schema) {
    this.db = db;
    this.json = json;
    this.tx = tx;
    this.handler = handler;
    this.metrics = metrics;
    if (!Set.of("core", "assessment").contains(schema))
      throw new IllegalArgumentException("Job schema");
    this.schema = schema;
    io.micrometer.core.instrument.Gauge.builder(
            "workflow.queue.oldest.seconds",
            db,
            jdbc -> {
              Double value =
                  jdbc.queryForObject(
                      "select coalesce(extract(epoch from (now()-min(execution_time))),0) from "
                          + schema
                          + ".scheduled_tasks where execution_time<now()",
                      Double.class);
              return value == null ? 0 : value;
            })
        .register(metrics);
    for (String state : List.of("PENDING", "RUNNING", "RETRY_WAIT", "FAILED"))
      io.micrometer.core.instrument.Gauge.builder(
              "workflow.jobs.current",
              db,
              jdbc ->
                  jdbc.queryForObject(
                      "select count(*) from " + schema + ".job where status=?",
                      Integer.class,
                      state))
          .tag("state", state)
          .register(metrics);
    task =
        Tasks.custom("workflow", Void.class)
            .execute(
                (instance, context) -> {
                  Instant retry = execute(UUID.fromString(instance.getId()));
                  return retry == null
                      ? new CompletionHandler.OnCompleteRemove<>()
                      : (complete, operations) -> operations.reschedule(complete, retry);
                });
    client =
        SchedulerClient.Builder.create(new TransactionAwareDataSourceProxy(ds), task)
            .tableName(schema + ".scheduled_tasks")
            .build();
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  @Profile("worker")
  Scheduler scheduler(DataSource ds) {
    return Scheduler.create(ds, task)
        .tableName(schema + ".scheduled_tasks")
        .threads(3)
        .pollingInterval(Duration.ofSeconds(1))
        .heartbeatInterval(Duration.ofSeconds(5))
        .missedHeartbeatsLimit(4)
        .build();
  }

  public UUID enqueue(String kind, String dedup, Object payload) {
    return enqueueAt(kind, dedup, payload, Instant.now());
  }

  public UUID enqueueAt(String kind, String dedup, Object payload, Instant at) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Enqueue requires an active transaction");
    UUID id = UUID.randomUUID();
    int inserted =
        db.update(
            "insert into "
                + schema
                + ".job(id,kind,dedup_key,payload,status,trace_id) values"
                + " (?,?,?,?::jsonb,'PENDING',?) on conflict(dedup_key) do nothing",
            id,
            kind,
            dedup,
            json.write(payload),
            TraceFilter.current());
    if (inserted == 0)
      return db.queryForObject(
          "select id from " + schema + ".job where dedup_key=?", UUID.class, dedup);
    client.schedule(task.instance(id.toString()), at);
    return id;
  }

  public Instant execute(UUID id) {
    Map<String, Object> job = db.queryForMap("select * from " + schema + ".job where id=?", id);
    if (Set.of("SUCCEEDED", "FAILED").contains(job.get("status"))) return null;
    String kind = (String) job.get("kind");
    JsonNode payload = json.read(job.get("payload").toString());
    MDC.put("trace_id", job.get("trace_id").toString());
    MDC.put("job_id", id.toString());
    if (payload.has("applicationId"))
      MDC.put("application_id", payload.path("applicationId").asText());
    if (payload.has("revision")) MDC.put("revision", payload.path("revision").asText());
    if (payload.has("requestId")) MDC.put("request_id", payload.path("requestId").asText());
    long started = System.nanoTime();
    try {
      tx.executeWithoutResult(
          s -> {
            Map<String, Object> locked =
                db.queryForMap("select * from " + schema + ".job where id=? for update", id);
            if (Set.of("SUCCEEDED", "FAILED").contains(locked.get("status"))) return;
            db.update(
                "update " + schema + ".job set status='RUNNING',updated_at=now() where id=?", id);
            handler.getObject().handle(kind, payload);
            db.update(
                "update " + schema + ".job set status='SUCCEEDED',updated_at=now() where id=?", id);
          });
      metrics.counter("workflow.jobs", "kind", kind, "result", "success").increment();
      return null;
    } catch (RuntimeException e) {
      // The failed domain transaction has rolled back before retry bookkeeping starts.
      return tx.execute(
          s -> {
            Map<String, Object> locked =
                db.queryForMap("select * from " + schema + ".job where id=? for update", id);
            int attempts = ((Number) locked.get("attempts")).intValue() + 1;
            boolean failed = attempts > BACKOFF.length;
            db.update(
                "update "
                    + schema
                    + ".job set status=?,attempts=?,error_code=?,updated_at=now() where id=?",
                failed ? "FAILED" : "RETRY_WAIT",
                attempts,
                e.getClass().getSimpleName(),
                id);
            db.update(
                "insert into " + schema + ".job_attempt(job_id,attempt,error_code) values (?,?,?)",
                id,
                attempts,
                e.getClass().getSimpleName());
            if (failed) handler.getObject().failed(kind, payload);
            else handler.getObject().retrying(kind, payload);
            metrics
                .counter("workflow.jobs", "kind", kind, "result", failed ? "failed" : "retry")
                .increment();
            LOG.warn(
                "Job attempt kind={} attempt={} terminal={} error_code={}",
                kind,
                attempts,
                failed,
                e.getClass().getSimpleName());
            return failed ? null : Instant.now().plusSeconds(BACKOFF[attempts - 1]);
          });
    } finally {
      metrics
          .timer("workflow.duration", "kind", kind)
          .record(Duration.ofNanos(System.nanoTime() - started));
      MDC.clear();
    }
  }

  public void replay(UUID id) {
    var rows = db.queryForList("select * from " + schema + ".job where id=? for update", id);
    if (rows.isEmpty()) throw Problem.missing();
    Map<String, Object> job = rows.getFirst();
    if (!"FAILED".equals(job.get("status"))) throw Problem.conflict("JOB_NOT_FAILED");
    enqueue(
        (String) job.get("kind"),
        "replay:" + id + ":" + UUID.randomUUID(),
        json.read(job.get("payload").toString()));
  }
}
