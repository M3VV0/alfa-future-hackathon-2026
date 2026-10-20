package ru.alfa.credit.core;

import static ru.alfa.credit.core.Application.State.*;

import java.util.*;

public final class StateTransitions {
  private StateTransitions() {}

  private static final Map<Application.State, Set<Application.State>> ALLOWED =
      Map.ofEntries(
          Map.entry(DRAFT, Set.of(SUBMITTED, CANCELLED)), Map.entry(SUBMITTED, Set.of(VALIDATING)),
          Map.entry(VALIDATING, Set.of(NEEDS_INFO, ENRICHING)),
              Map.entry(NEEDS_INFO, Set.of(SUBMITTED, CANCELLED)),
          Map.entry(ENRICHING, Set.of(SCORING, NEEDS_INFO)), Map.entry(SCORING, Set.of(DECIDING)),
          Map.entry(DECIDING, Set.of(REJECTED, OFFERED, NEEDS_INFO)),
              Map.entry(OFFERED, Set.of(ACCEPTED, DECLINED, EXPIRED)),
          Map.entry(ACCEPTED, Set.of(SIGNED_SIMULATED)),
              Map.entry(SIGNED_SIMULATED, Set.of(DISBURSED_SIMULATED)));

  public static boolean allowed(Application.State from, Application.State to) {
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }
}
