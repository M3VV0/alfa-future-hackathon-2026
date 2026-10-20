package ru.alfa.credit.platform;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  public Problem(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public static Problem missing() {
    return new Problem(404, "NOT_FOUND");
  }

  public static Problem conflict(String code) {
    return new Problem(409, code);
  }

  public static Problem invalid(String code) {
    return new Problem(422, code);
  }
}
