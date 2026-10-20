package ru.alfa.credit.core;

public interface DocumentStorage {
  void put(String key, byte[] data, String contentType);

  byte[] get(String key);

  void delete(String key);
}
