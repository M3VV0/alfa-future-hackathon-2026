package ru.alfa.credit.contracts;

public interface RiskScorer {
  AssessmentResult score(AssessmentRequest request);
}
