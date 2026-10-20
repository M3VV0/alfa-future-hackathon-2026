package ru.alfa.credit.core;

import java.math.*;
import java.time.YearMonth;
import java.util.*;
import org.springframework.validation.BindingResult;

/** Browser amounts are rubles. Legacy minor-unit fields remain supported on the same web routes. */
public class LoanForm {
  private String amountRubles,
      revenueRubles,
      expensesRubles,
      debtRubles,
      termMonths,
      purpose = "",
      period;
  private Long amountMinor, revenueMinor, expensesMinor, debtMinor;
  private List<UUID> documentIds = new ArrayList<>();

  public Draft draft(BindingResult errors) {
    Long amount = money(amountRubles, amountMinor, "amountRubles", true, errors);
    Long revenue = money(revenueRubles, revenueMinor, "revenueRubles", false, errors);
    Long expenses = money(expensesRubles, expensesMinor, "expensesRubles", false, errors);
    Long debt = money(debtRubles, debtMinor, "debtRubles", false, errors);
    int months = 0;
    try {
      months = Integer.parseInt(termMonths);
      if (months < 1 || months > 120) throw new NumberFormatException();
    } catch (Exception e) {
      errors.rejectValue("termMonths", "invalid", "Укажите срок от 1 до 120 месяцев.");
    }
    if (purpose == null || purpose.isBlank() || purpose.length() > 1000)
      errors.rejectValue("purpose", "invalid", "Укажите цель финансирования: до 1 000 символов.");
    String normalizedPeriod = period == null || period.isBlank() ? null : period;
    if (normalizedPeriod != null)
      try {
        if (!normalizedPeriod.matches("[0-9]{4}-(0[1-9]|1[0-2])")
            || YearMonth.parse(normalizedPeriod).isAfter(YearMonth.now()))
          throw new IllegalArgumentException();
      } catch (Exception e) {
        errors.rejectValue("period", "invalid", "Выберите текущий или прошедший месяц.");
      }
    if (documentIds == null) documentIds = new ArrayList<>();
    if (documentIds.size() > 20 || documentIds.contains(null))
      errors.rejectValue("documentIds", "invalid", "Можно приложить до 20 документов.");
    if (errors.hasErrors()) return null;
    return new Draft(
        amount, months, purpose.trim(), revenue, expenses, debt, normalizedPeriod, documentIds);
  }

  private Long money(
      String rubles, Long minor, String field, boolean required, BindingResult errors) {
    if (rubles == null) {
      if (minor != null && minor >= 0 && (!required || minor > 0)) return minor;
      if (!required && minor == null) return null;
    } else if (rubles.isBlank() && !required) return null;
    else
      try {
        String normalized =
            rubles.replace(" ", "").replace("\u00a0", "").replace("\u202f", "").replace(',', '.');
        if (!normalized.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw new NumberFormatException();
        long result = new BigDecimal(normalized).movePointRight(2).longValueExact();
        if (required && result == 0) throw new NumberFormatException();
        return result;
      } catch (Exception ignored) {
      }
    errors.rejectValue(
        field,
        "invalid",
        required
            ? "Введите сумму больше нуля, не более двух знаков после запятой."
            : "Введите неотрицательную сумму в рублях, не более двух знаков после запятой.");
    return null;
  }

  public static LoanForm from(Draft draft) {
    LoanForm form = new LoanForm();
    form.amountRubles = rubles(draft.amountMinor());
    form.termMonths = Integer.toString(draft.termMonths());
    form.purpose = draft.purpose();
    form.revenueRubles = rubles(draft.revenueMinor());
    form.expensesRubles = rubles(draft.expensesMinor());
    form.debtRubles = rubles(draft.debtMinor());
    form.period = draft.period();
    form.documentIds = new ArrayList<>(draft.documentIds());
    return form;
  }

  private static String rubles(Long minor) {
    return minor == null ? "" : BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString();
  }

  public String getAmountRubles() {
    return amountRubles;
  }

  public void setAmountRubles(String v) {
    amountRubles = v;
  }

  public String getRevenueRubles() {
    return revenueRubles;
  }

  public void setRevenueRubles(String v) {
    revenueRubles = v;
  }

  public String getExpensesRubles() {
    return expensesRubles;
  }

  public void setExpensesRubles(String v) {
    expensesRubles = v;
  }

  public String getDebtRubles() {
    return debtRubles;
  }

  public void setDebtRubles(String v) {
    debtRubles = v;
  }

  public String getTermMonths() {
    return termMonths;
  }

  public void setTermMonths(String v) {
    termMonths = v;
  }

  public String getPurpose() {
    return purpose;
  }

  public void setPurpose(String v) {
    purpose = v;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String v) {
    period = v;
  }

  public List<UUID> getDocumentIds() {
    return documentIds;
  }

  public void setDocumentIds(List<UUID> v) {
    documentIds = v;
  }

  public Long getAmountMinor() {
    return amountMinor;
  }

  public void setAmountMinor(Long v) {
    amountMinor = v;
  }

  public Long getRevenueMinor() {
    return revenueMinor;
  }

  public void setRevenueMinor(Long v) {
    revenueMinor = v;
  }

  public Long getExpensesMinor() {
    return expensesMinor;
  }

  public void setExpensesMinor(Long v) {
    expensesMinor = v;
  }

  public Long getDebtMinor() {
    return debtMinor;
  }

  public void setDebtMinor(Long v) {
    debtMinor = v;
  }
}
