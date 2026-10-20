package ru.alfa.credit.core;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

@Component
public class S3DocumentStorage implements DocumentStorage {
  private final S3Client client;
  private final String bucket;

  public S3DocumentStorage(
      @Value("${storage.endpoint}") String endpoint,
      @Value("${storage.bucket}") String bucket,
      @Value("${storage.region}") String region,
      @Value("${storage.access-key}") String access,
      @Value("${storage.secret-key}") String secret,
      @Value("${storage.path-style:true}") boolean pathStyle) {
    this.bucket = bucket;
    client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(pathStyle)
                    .chunkedEncodingEnabled(false)
                    .build())
            .requestChecksumCalculation(
                software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(
                software.amazon.awssdk.core.checksums.ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(20))
                        .apiCallAttemptTimeout(Duration.ofSeconds(5)))
            .build();
  }

  public void put(String key, byte[] data, String contentType) {
    client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(data));
  }

  public byte[] get(String key) {
    return client
        .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
        .asByteArray();
  }

  public void delete(String key) {
    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @jakarta.annotation.PreDestroy
  void close() {
    client.close();
  }
}
