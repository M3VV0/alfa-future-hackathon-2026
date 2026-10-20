package ru.alfa.credit.core;

public final class ReasonText {
  private ReasonText() {}

  public static String explain(String code) {
    return switch (code) {
      case "SCORER_ALWAYS_REJECT" ->
          "Заявка отклонена временным демонстрационным скорером, который всегда возвращает отказ."
              + " Это не оценка реальной кредитоспособности.";
      case "BUSINESS_INACTIVE_OR_NOT_FOUND" ->
          "Локальная проверка не подтвердила действующий статус бизнеса.";
      case "OVERDUE_GT_90" -> "В синтетической кредитной истории есть просрочка свыше 90 дней.";
      case "HIGH_RISK" -> "Оценка риска превышает допустимый уровень демонстрационных правил.";
      case "BUSINESS_TOO_YOUNG" -> "Возраст бизнеса не соответствует выбранному продукту.";
      case "PRODUCT_LIMITS" ->
          "Запрошенные сумма или срок не соответствуют условиям выбранного продукта.";
      case "NON_POSITIVE_INCOME" -> "Подтверждённый чистый доход должен быть положительным.";
      case "INSUFFICIENT_CAPACITY" ->
          "Доступного ежемесячного дохода недостаточно для минимальной суммы продукта.";
      case "COUNTEROFFER" -> "Предложена меньшая сумма при выбранном сроке.";
      case "APPROVED" -> "Предложены запрошенные условия.";
      default -> "Требуется уточнение сведений: " + code;
    };
  }
}
