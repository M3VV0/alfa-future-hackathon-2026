# Контракт Scorer и схема датасета

Ниже перечислены 32 входных поля текущего CSV; типы описывают данные, а `?` — допустимые пропуски. Это не список обязательных аргументов `predict`.

input data:

1. id: int
2. requested_amount: decimal
3. currency: string (в текущей реализации только RUB)
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

## Вход при применении

Обязательны восемь полей: `requested_amount`, `currency`, `term_months`, `payment_frequency`, `financing_purpose`, `applicant_entity_type`, `msp_category`, `snapshot_on`. Остальные поля можно не передавать; сырые идентификаторы и названия не входят в модельные признаки, но из года ОГРН/ОГРНИП вычисляется приблизительный возраст регистрации. Даты передаются строками `YYYY-MM-DD`; вложенные блоки принимаются как JSON-строки или словари/списки.

Числа принимаются как числовые значения или строки. Сумма и срок должны быть конечными и положительными, залог и численность — конечными и неотрицательными, если известны. В CSV срок и численность целые, но текущая проверка `Scorer` допускает дробные значения: строгая проверка целочисленности не реализована. Обработка проверяет все переданные блоки до отбора признаков выбранной модели.

output data (class Scorer):

1. одно число, float, отражающее расчётную годовую ставку в процентах: 25.0 означает 25% годовых. Это оценка для решения по заявке, а не подтверждение одобрения.

`Scorer()` создаёт готовый pipeline: загружает выбранный обученный estimator из `artifacts/selected_model.joblib` и подключает общие преобразования `features.py`. Повторное обучение не выполняется. `await scorer.predict(row)` принимает одну запись (словарь/Mapping или `pandas.Series`) без `target` и возвращает `float` — ставку в процентах годовых. Поле `target` запрещено; DataFrame и список записей отклоняются, даже если содержат только одну строку.

Предобработка и прогноз выполняются в рабочем потоке без блокировки event loop; вызовы одного экземпляра сериализованы, CatBoost ограничен двумя потоками. Ошибки входа и вычисления передаются вызывающему коду через `await`. [EXPERIMENT.md](EXPERIMENT.md) описывает запуск и пример применения.

## Предлагаемый будущий ответ кредитного процесса

Следующая структура не реализована в `Scorer`: отдельная бизнес-логика должна определить решение и единицы суммы. Сейчас модель возвращает только ставку.

На основе ставки и дополнительных правил предлагается формировать:

1. OK/DECLINED/OFFER: enum
2. id: int
3. credit_sum: int (предположительно в тыс. руб.)
4. interest_rate: float (предположительно годовая)
5. credit_time: int (предположительно в месяцах)
