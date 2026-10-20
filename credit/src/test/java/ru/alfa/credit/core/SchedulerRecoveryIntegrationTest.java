package ru.alfa.credit.core;

import static org.assertj.core.api.Assertions.*;

import com.github.kagkarlsson.scheduler.*;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class SchedulerRecoveryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17.6-alpine3.22");

  @Test
  void recoversAnExecutionClaimedByACrashedWorker() throws Exception {
    var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    var db = new JdbcTemplate(ds);
    db.execute(
        "create table scheduled_tasks(task_name varchar(100),task_instance varchar(100),task_data"
            + " bytea,execution_time timestamptz not null,picked boolean,picked_by"
            + " varchar(100),last_success timestamptz,last_failure timestamptz,consecutive_failures"
            + " int,last_heartbeat timestamptz,version bigint,primary"
            + " key(task_name,task_instance))");
    CountDownLatch completed = new CountDownLatch(1);
    AtomicInteger effects = new AtomicInteger();
    var task =
        Tasks.oneTime("recovery")
            .execute(
                (instance, context) -> {
                  effects.incrementAndGet();
                  completed.countDown();
                });
    var client = SchedulerClient.Builder.create(ds, task).build();
    client.schedule(task.instance("crashed-worker-job"), Instant.now());
    // Reproduce the persisted state left by a process killed after claiming a task.
    db.update(
        "update scheduled_tasks set"
            + " picked=true,picked_by='dead-worker',last_heartbeat=now()-interval '1 hour'");
    Scheduler replacement =
        Scheduler.create(ds, task)
            .pollingInterval(Duration.ofMillis(100))
            .heartbeatInterval(Duration.ofSeconds(1))
            .missedHeartbeatsLimit(4)
            .build();
    try {
      replacement.start();
      assertThat(completed.await(15, TimeUnit.SECONDS)).isTrue();
      assertThat(effects.get()).isEqualTo(1);
    } finally {
      replacement.stop();
    }
  }
}
