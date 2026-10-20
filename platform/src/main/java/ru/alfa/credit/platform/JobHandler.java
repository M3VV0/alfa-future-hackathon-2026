package ru.alfa.credit.platform;

import com.fasterxml.jackson.databind.JsonNode;

public interface JobHandler {
  void handle(String kind, JsonNode payload);

  default void retrying(String kind, JsonNode payload) {}

  default void failed(String kind, JsonNode payload) {}
}
