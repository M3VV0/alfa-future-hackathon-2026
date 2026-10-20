package ru.alfa.credit.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "ru.alfa.credit")
public class CreditApplication {
  public static void main(String[] args) {
    SpringApplication.run(CreditApplication.class, args);
  }
}
