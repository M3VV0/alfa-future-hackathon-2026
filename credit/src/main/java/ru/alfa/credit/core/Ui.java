package ru.alfa.credit.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import ru.alfa.credit.platform.Json;

@Component("ui")
public class Ui {
  private final Json json;

  public Ui(Json json) {
    this.json = json;
  }

  public UUID command() {
    return UUID.randomUUID();
  }

  public String money(Object value) {
    if (value == null || value.toString().isBlank()) return "—";
    return number(new BigDecimal(value.toString()).movePointLeft(2)) + " ₽";
  }

  public String number(BigDecimal value) {
    NumberFormat fmt = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
    fmt.setMaximumFractionDigits(2);
    return fmt.format(value);
  }

  public String percent(Object value) {
    return number(new BigDecimal(value.toString()).multiply(BigDecimal.valueOf(100))) + "%";
  }

  public String date(Object value) {
    if (value == null) return "—";
    if (value instanceof java.sql.Date date)
      return date.toLocalDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    if (value instanceof LocalDate date)
      return date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    Instant instant =
        value instanceof java.sql.Timestamp t
            ? t.toInstant()
            : value instanceof Instant i ? i : null;
    return instant == null
        ? value.toString()
        : DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
            .withZone(ZoneId.of("Europe/Moscow"))
            .format(instant);
  }

  public String isoDate(Object value) {
    return value == null ? "" : value.toString().substring(0, 10);
  }

  public String shortId(Object value) {
    return value.toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  public String field(Object value, String key) {
    if (value == null) return "";
    JsonNode node = value instanceof JsonNode n ? n : json.read(value.toString());
    return node.path(key).isNull() ? "" : node.path(key).asText("");
  }

  public List<String> info(Object value) {
    List<String> result = new ArrayList<>();
    if (value != null)
      for (JsonNode n : json.read(value.toString())) result.add(infoText(n.asText()));
    return result;
  }

  public String infoText(String code) {
    return switch (code) {
      case "revenueMinor" -> "Укажите среднемесячный доход бизнеса.";
      case "expensesMinor" -> "Укажите среднемесячные расходы.";
      case "debtMinor" ->
          "Укажите ежемесячные платежи по действующим кредитам. Если их нет — введите 0.";
      case "period" -> "Выберите отчётный месяц.";
      case "FINANCIAL_REPORT" -> "Приложите финансовый отчёт в формате JSON.";
      case "DOCUMENT_INN_MISMATCH" -> "ИНН в отчёте должен совпадать с ИНН компании.";
      case "DOCUMENT_MISMATCH_period" -> "Месяц в отчёте должен совпадать с месяцем в заявке.";
      case "DOCUMENT_MISMATCH_revenueMinor" -> "Доходы в отчёте и в заявке должны совпадать.";
      case "DOCUMENT_MISMATCH_expensesMinor" -> "Расходы в отчёте и в заявке должны совпадать.";
      case "EXTERNAL_DATA_CLARIFICATION" ->
          "Проверьте сведения о компании: данные локальной проверки требуют уточнения.";
      default -> "Проверьте сведения и приложенные документы.";
    };
  }

  public String status(Object value) {
    return switch (value.toString()) {
      case "DRAFT" -> "Черновик";
      case "SUBMITTED" -> "Заявка отправлена";
      case "VALIDATING" -> "Проверяем данные";
      case "ENRICHING" -> "Проверяем компанию";
      case "SCORING", "DECIDING" -> "На рассмотрении";
      case "NEEDS_INFO" -> "Нужны сведения";
      case "REJECTED" -> "Отказ";
      case "OFFERED" -> "Есть предложение";
      case "ACCEPTED" -> "Условия приняты";
      case "SIGNED_SIMULATED" -> "Договор подписан · имитация";
      case "DISBURSED_SIMULATED" -> "Кредит выдан · имитация";
      case "CANCELLED" -> "Отменена";
      case "DECLINED" -> "Предложение отклонено";
      case "EXPIRED" -> "Срок предложения истёк";
      default -> "Обрабатывается";
    };
  }

  public String tone(Object status) {
    return switch (status.toString()) {
      case "NEEDS_INFO" -> "warning";
      case "REJECTED" -> "danger";
      case "OFFERED", "ACCEPTED", "SIGNED_SIMULATED", "DISBURSED_SIMULATED" -> "success";
      case "SUBMITTED", "VALIDATING", "ENRICHING", "SCORING", "DECIDING" -> "processing";
      default -> "neutral";
    };
  }

  public boolean active(Object status) {
    return Set.of("SUBMITTED", "VALIDATING", "ENRICHING", "SCORING", "DECIDING")
        .contains(status.toString());
  }

  public boolean editable(Object status) {
    return Set.of("DRAFT", "NEEDS_INFO").contains(status.toString());
  }

  public int step(Object status) {
    return editable(status) ? 1 : active(status) ? 2 : 3;
  }

  public String product(Object id) {
    return "equipment".equals(id.toString()) ? "На оборудование" : "На оборотные средства";
  }

  public String reason(String reason) {
    return reason.contains("временным демонстрационным скорером")
        ? "В этой демонстрационной версии все заявки получают отказ. Это не оценка реальной"
            + " кредитоспособности вашего бизнеса."
        : reason;
  }

  public String error(String code) {
    return switch (code) {
      case "STALE_VERSION" ->
          "Заявка уже изменилась. Обновите страницу, чтобы увидеть актуальные данные.";
      case "COMMAND_PAYLOAD_MISMATCH" ->
          "Данные этой операции уже изменились. Обновите страницу и попробуйте ещё раз.";
      case "INVALID_TRANSITION" ->
          "Это действие больше недоступно на текущем этапе. Обновите страницу.";
      case "DOCUMENT_NOT_AVAILABLE" ->
          "Один из документов недоступен. Обновите список и выберите доступный файл.";
      case "DOCUMENT_SIZE" -> "Размер файла должен быть от 1 байта до 10 МБ.";
      case "DOCUMENT_FILENAME" -> "В имени файла есть недопустимые символы. Переименуйте файл.";
      case "DOCUMENT_SIGNATURE", "DOCUMENT_REPORT_SCHEMA", "DOCUMENT_KIND" ->
          "Проверьте файл: для отчёта нужен JSON по шаблону, для другого документа — JSON или PDF.";
      case "FUTURE_FINANCIAL_PERIOD" -> "Отчётный месяц не может быть в будущем.";
      case "INVALID_PROFILE", "INVALID_COMPANY", "INVALID_FORM", "INVALID_DRAFT" ->
          "Проверьте обязательные поля и формат введённых данных.";
      case "NOT_FOUND" -> "Страница не найдена или у вас нет доступа к этим данным.";
      case "OFFER_EXPIRED" -> "Срок действия предложения истёк. Можно создать новую заявку.";
      case "REPORT_DATA_REQUIRED" ->
          "Сначала заполните и сохраните доходы, расходы и отчётный месяц в заявке.";
      default ->
          "Сервис временно недоступен. Введённые данные остаются в форме. Попробуйте ещё раз"
              + " позже.";
    };
  }
}
