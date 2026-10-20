package ru.alfa.credit.core;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class S3StorageIntegrationTest {
  static final String CONFIG =
      """
      metadata_dir = "/tmp/meta"
      data_dir = "/tmp/data"
      db_engine = "sqlite"
      replication_factor = 1
      rpc_bind_addr = "[::]:3901"
      rpc_public_addr = "127.0.0.1:3901"
      [s3_api]
      s3_region = "garage"
      api_bind_addr = "[::]:3900"
      """;

  @Container
  static GenericContainer<?> garage =
      new GenericContainer<>("dxflrs/garage:v2.1.0")
          .withCopyToContainer(Transferable.of(CONFIG), "/etc/garage.toml")
          .withEnv("GARAGE_RPC_SECRET", "0".repeat(64))
          .withExposedPorts(3900)
          .withCommand("/garage", "server")
          .waitingFor(
              org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                  ".*S3 API server listening.*\\n", 1));

  static void cli(String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "/garage";
    System.arraycopy(args, 0, command, 1, args.length);
    var result = garage.execInContainer(command);
    assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
  }

  @Test
  void garageSupportsPutGetDeleteAndReconfiguredAdapter() throws Exception {
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              var identity = garage.execInContainer("/garage", "node", "id");
              assertThat(identity.getExitCode()).withFailMessage(identity.getStderr()).isZero();
              assertThat(identity.getStdout()).isNotBlank();
            });
    String node =
        garage
            .execInContainer("/garage", "node", "id")
            .getStdout()
            .lines()
            .findFirst()
            .orElseThrow()
            .split("@")[0];
    cli("layout", "assign", "-z", "test", "-c", "1G", node);
    cli("layout", "apply", "--version", "1");
    String access = "GK0123456789abcdef01234567", secret = "1".repeat(64);
    cli("key", "import", access, secret, "--yes");
    cli("bucket", "create", "documents");
    cli("bucket", "allow", "--read", "--write", "--owner", "documents", "--key", access);
    String endpoint = "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);
    var storage = new S3DocumentStorage(endpoint, "documents", "garage", access, secret, true);
    byte[] data = "synthetic-document".getBytes(StandardCharsets.UTF_8);
    storage.put("company/report", data, "application/json");
    assertThat(storage.get("company/report")).isEqualTo(data);
    storage.close();
    var replacement = new S3DocumentStorage(endpoint, "documents", "garage", access, secret, true);
    assertThat(replacement.get("company/report")).isEqualTo(data);
    replacement.delete("company/report");
    assertThatThrownBy(() -> replacement.get("company/report"))
        .isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
    replacement.close();
  }
}
