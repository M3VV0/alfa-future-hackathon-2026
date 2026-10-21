input data:
1. id: int
2. requested_amount: decimal
3. currency: string
4. term_months: int
5. payment_frequency: enum {monthly, quarterly}
6. financing_purpose: enum {working_capital, equipment, investment, refinancing, business_auto, commercial_mortgage}
7. applicant_inn: string
8. declared_collateral_value: decimal?
9. applicant_entity_type: enum {legal_entity, ip}
10. sex: enum {male, female}?
11. citizenship_kind: enum {russian, foreign, stateless}?
12. country_code_raw: string?
13. formation_method_name: enum {"Создание юридического лица", "Создание юридического лица путем реорганизации в форме преобразования"}?
14. legal_events: [json{kind: enum {reorganization_completed}, source_status_code: string?, description: string, event_date: date, record_date: date}, ...]
15. kpp: string?
16. legal_name: string?
17. ogrn: string?
18. ogrnip: string?
19. entity_type: enum {legal_entity, ip}
20. location: json{region_code: string, region: string, district: string?, city: string, settlement: string?}?
21. msp_included_on: date?
22. snapshot_on: date
23. msp_category: enum {micro, small, medium}
24. average_headcount: int?
25. report_year: int
26. is_newly_registered: bool
27. is_social_enterprise: bool
28. products: [json{okpd2_code: string, name: string, innovative_or_hightech: bool}, ...]?
29. partnerships: [json{customer_name: string, agreement_number: string, agreement_date: date}, ...]?
30. participants_count: int
31. has_predecessors: bool
32. has_successors: bool

output data (class Scorer):
1. одно число, float, отражающее расчётную годовую ставку в процентах: 25.0 означает 25% годовых. Это оценка для решения по заявке, а не подтверждение одобрения.

на основе этого числа предлагается выносить решение о выдаче или не выдаче кредита и далее отправлять следующую информацию:
1. OK/DECLINED/OFFER: enum
2. id: int
3. credit_sum: int (предположительно в тыс. руб.)
4. interest_rate: float (предположительно годовая)
5. credit_time: int (предположительно в месяцах)