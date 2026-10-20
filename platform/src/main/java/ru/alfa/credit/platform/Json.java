package ru.alfa.credit.platform;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Json {
  private final ObjectMapper mapper;

  public Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON serialization", e);
    }
  }

  public JsonNode tree(Object value) {
    return mapper.valueToTree(value);
  }

  public JsonNode read(String value) {
    return parse(value, JsonNode.class);
  }

  public <T> T parse(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }

  public String hash(Object value) {
    return sha256(write(canonical(tree(value))).getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      names.forEach(n -> sorted.set(n, canonical(node.get(n))));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode out = mapper.createArrayNode();
      node.forEach(n -> out.add(canonical(n)));
      return out;
    }
    return node;
  }
}
