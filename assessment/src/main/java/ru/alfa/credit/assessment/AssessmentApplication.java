package ru.alfa.credit.assessment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "ru.alfa.credit")
public class AssessmentApplication {
  public static void main(String[] args) {
    SpringApplication.run(AssessmentApplication.class, args);
  }
}
