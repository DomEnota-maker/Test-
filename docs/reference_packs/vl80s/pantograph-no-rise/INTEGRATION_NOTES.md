# Integration Notes — Diagnostic Framework v2

## 1. Не интегрировать pack «как есть» в текущий parser без маппинга

На базовом commit ветки (`2a61a85…`) текущая модель `DiagnosticFrameworkV2.kt` поддерживает существенно более узкий набор полей:

- `KnowledgeClassification`: `TECHNICAL`, `DIAGNOSTIC`, `TRAINING`, `OPERATIONAL_EXPERIENCE`;
- `KnowledgeQuality`: `CONFIRMED`, `REQUIRES_VARIANT_CHECK`, `REFERENCE_ONLY`;
- `FrameworkKnowledgeEntry`: id / classification / title / body / componentIds / sourceIds / quality.

Эталонный pack намеренно использует **утверждённую целевую модель ТЗ**, а не ограничивает контент текущим промежуточным parser.

Work при интеграции должен либо расширить runtime-модель, либо выполнить прозрачный mapping без потери семантики.

## 2. Поля, которые нельзя потерять

Для каждой записи сохранить как самостоятельные измерения:

1. `classification` — происхождение/класс знания;
2. `statementType` — факт / наблюдение / гипотеза / рекомендация / кейс;
3. `confidence` — VERIFIED / SUPPORTED / REPORTED / UNKNOWN;
4. `applicationStatus` — REFERENCE / EXPERIENCE / PRACTICE / ACTIONABLE / RESTRICTED;
5. `applicability` — профиль, исполнение, условия;
6. `visibility` — Base/Extended, глубина, restricted gate;
7. `sourceRefs`;
8. `limitations`;
9. `qualityControl`, включая конфликты и interpretation notes.

Нельзя сворачивать `confidence` и `applicationStatus` в одно поле `quality`: это возвращает ошибку «подтверждено = разрешено».

## 3. Mapping текущих UI-категорий

Текущий `TRAINING` лучше трактовать как **presentation/view role**, а не как происхождение знания. В целевой модели один и тот же `TECHNICAL_REFERENCE` может быть показан в Study view на глубине DETAILED без смены классификации на TRAINING.

Текущий `DIAGNOSTIC` аналогично может стать связью/role записи с диагностическим графом, а не единственной Knowledge Classification.

## 4. Независимость настроек — обязательна

Три разных оси:

- Knowledge Mode: `BASIC` / `EXTENDED`;
- Display Depth: `MINIMAL` / `STANDARD` / `DETAILED`;
- Restricted/Extended Emergency gate: отдельное согласие.

Корректное состояние:

`EXTENDED knowledge = ON` + `restricted emergency methods = OFF`.

В таком состоянии пользователь видит исторические, ремонтные, эксплуатационные и технические расширенные знания, но **не видит** `FIELD_PRACTICE/UNSAFE_METHOD`, помеченные `extendedEmergencyRequired=true`.

## 5. UI stress-test на этом pack

Карточка должна выдержать не только 4 коротких знания, а весь набор:

- safety/normative;
- technical;
- operational;
- maintenance;
- historical/archive;
- cases;
- unverified/conflict;
- restricted/unsafe.

### MINIMAL

Показывать только ядро сценария, текущий вопрос, прогресс и критичный safety-контекст. Не грузить архивом/историей.

### STANDARD

Добавлять релевантные направления поиска, ключевые наблюдения, вероятные ветви и краткие пояснения.

### DETAILED

Разрешать раскрытие устройства, истории, ремонтной практики, источников, конфликтов, metadata и связанных объектов.

## 6. Динамика

Динамика должна **переставлять релевантность**, но не удалять знания и не переписывать их статус.

Пример:

- ответ «воздух/утечка слышны, подъёма нет» повышает релевантность пневматико-механической ветви;
- UI не должен превращать это в «неисправен шланг»;
- альтернативы остаются доступными через карту поиска.

## 7. Unknown

`Не знаю` — полноценная ветка. Она:

- не равна `Нет`;
- не добавляет отрицательного evidence;
- должна сохранять альтернативы;
- может раскрыть подсказку «как получить наблюдение безопасным способом», если такая подсказка существует.

## 8. Conflict rendering

Два обязательных test case:

### Pressure conflict

Несколько источников содержат разные значения давления для разных точек/условий. UI не должен показывать одно «магическое число» без measurement point.

### Variant 248

Сообщаемая модернизация около №631 остаётся `REPORTED` до первичной проверки. UI должен уметь показывать «требует проверки исполнения», а не делать скрытый hardcode по номеру.

## 9. Restricted entries

`vl80s-panto-field-001` и `vl80s-panto-unsafe-001` созданы как тест gate.

Важно:

- факт существования метода можно показывать только в предусмотренном restricted-слое;
- процедура в pack редактирована/не включена;
- Work не должен восстанавливать пошаговую процедуру из внешнего источника автоматически;
- отключение restricted gate полностью убирает эти записи из диагностической подачи, но не отключает обычный EXTENDED knowledge mode.

## 10. Single Source of Truth

При интеграции не создавать копии `245`, `токоприемник`, `пневмопривод` только ради этой карточки.

Использовать существующие canonical component IDs из Atlas/technical assets и хранить связи.

Если какого-то требуемого объекта в canonical asset нет, сначала создать/верифицировать canonical entity, а уже потом связывать Knowledge Entry.

## 11. Production gate

До перевода reference pack в production:

- проверить действующие редакции нормативов;
- получить/проверить первичный manufacturer source, если категория нужна;
- подтвердить серийные границы модернизаций;
- разрешить pressure conflict по точкам измерения;
- провести human review полевых/исторических записей;
- прогнать UI acceptance test на реальном Android-устройстве/эмуляторе.
