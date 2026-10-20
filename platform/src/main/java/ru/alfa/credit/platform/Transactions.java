package ru.alfa.credit.platform;

import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class Transactions {
  @Bean
  TransactionTemplate transactionTemplate(
      org.springframework.transaction.PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }
}
