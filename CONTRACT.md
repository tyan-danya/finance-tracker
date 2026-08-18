# SpendTracker — контракт модулей (ОБЯЗАТЕЛЬНО К СОБЛЮДЕНИЮ)

Приложение: учёт личных трат. Android, Kotlin 2.0.21, Jetpack Compose (Material 3), Room, minSdk 26, compileSdk 35.
Язык интерфейса — **русский**. Валюта — рубль. Пакет — `com.dtyan.spendtracker`.

Все суммы **всегда** в копейках (`Long`, минорные единицы). Никаких `Double` для денег.
Даты — `java.time.LocalDate` (включён core library desugaring, поэтому java.time доступна на minSdk 26).

## Уже реализовано (НЕ МЕНЯТЬ, только использовать)

### `domain/model/Models.kt`
```kotlin
enum class PaymentMethod(val title: String) {
    CARD("Карта"), CASH("Наличные"), TRANSFER("Перевод"), ONLINE("Онлайн"), OTHER("Другое");
    companion object { fun fromName(raw: String?): PaymentMethod }
}

data class ExpenseRecord(
    val id: Long, val amountMinor: Long, val currency: String,
    val categoryId: Long, val categoryName: String,
    val subcategoryId: Long?, val subcategoryName: String?,
    val date: LocalDate, val note: String,
    val paymentMethod: PaymentMethod, val createdAt: Long,
) { val amount: Double }

data class Category(val id: Long, val name: String, val icon: String, val colorArgb: Int,
                    val isBuiltIn: Boolean, val sortOrder: Int, val archived: Boolean)
data class Subcategory(val id: Long, val categoryId: Long, val name: String,
                       val isBuiltIn: Boolean, val sortOrder: Int, val archived: Boolean)
data class CategoryTree(val category: Category, val subcategories: List<Subcategory>)

data class ExpenseDraft(
    val id: Long? = null, val amountMinor: Long, val categoryId: Long,
    val subcategoryId: Long? = null, val date: LocalDate, val note: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.CARD, val currency: String = "RUB",
)
```

### `domain/model/Period.kt`
```kotlin
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate): Boolean
    val days: Int   // включая обе границы
}

sealed interface Period {
    val title: String
    fun range(dataBounds: DateRange?): DateRange?   // AllTime возвращает dataBounds (может быть null)
    fun previous(): Period?                          // AllTime возвращает null

    data class Month(val yearMonth: YearMonth) : Period
    data class Day(val date: LocalDate) : Period
    data class Year(val year: Int) : Period
    data class Custom(val from: LocalDate, val to: LocalDate) : Period
    data object AllTime : Period

    companion object {
        val DAY_FORMAT: DateTimeFormatter    // dd.MM.yyyy
        fun currentMonth(today: LocalDate): Period
    }
}
```

### `domain/MoneyFormat.kt`
```kotlin
object MoneyFormat {
    fun format(minor: Long, withSymbol: Boolean = true): String   // "1 234,56 ₽"
    fun formatCompact(minor: Long): String                        // "1,2к", "3,4 млн"
    fun parseToMinor(input: String): Long?                        // null если не распарсилось/отрицательное
    fun formatPercent(share: Double): String                      // "12,3 %"
}
```

### `domain/stats/Stats.kt` (модели — уже написаны, НЕ переписывать)
```kotlin
data class CategoryStat(val categoryId: Long, val categoryName: String,
                        val totalMinor: Long, val count: Int, val share: Double)
data class SubcategoryStat(val subcategoryId: Long?, val subcategoryName: String,
                           val categoryId: Long, val categoryName: String,
                           val totalMinor: Long, val count: Int, val share: Double)
data class PaymentMethodStat(val method: PaymentMethod, val totalMinor: Long, val count: Int, val share: Double)
data class WeekdayStat(val dayOfWeek: DayOfWeek, val totalMinor: Long, val count: Int)
data class SeriesPoint(val date: LocalDate, val label: String, val totalMinor: Long, val count: Int)
data class Comparison(val currentMinor: Long, val previousMinor: Long) {
    val deltaMinor: Long; val deltaRatio: Double?   // null если previousMinor == 0
}
data class PeriodStats(
    val period: Period, val range: DateRange?,
    val totalMinor: Long, val count: Int,
    val averagePerTransactionMinor: Long, val averagePerDayMinor: Long,
    val medianTransactionMinor: Long, val maxSingle: ExpenseRecord?,
    val byCategory: List<CategoryStat>, val bySubcategory: List<SubcategoryStat>,
    val byPaymentMethod: List<PaymentMethodStat>, val byWeekday: List<WeekdayStat>,
    val dailySeries: List<SeriesPoint>, val monthlySeries: List<SeriesPoint>,
    val records: List<ExpenseRecord>,
) { val isEmpty: Boolean; val topCategory: CategoryStat? }
```

### `data/ExpenseRepository.kt`
```kotlin
class ExpenseRepository(categoryDao: CategoryDao, expenseDao: ExpenseDao) {
    fun observeExpenses(): Flow<List<ExpenseRecord>>
    fun observeCategoryTree(includeArchived: Boolean = false): Flow<List<CategoryTree>>
    suspend fun getAllExpenses(): List<ExpenseRecord>
    suspend fun seedDefaultsIfEmpty()
    suspend fun addExpense(draft: ExpenseDraft): Long
    suspend fun updateExpense(draft: ExpenseDraft)      // требует draft.id != null
    suspend fun deleteExpense(id: Long)
    suspend fun deleteAllExpenses()
    suspend fun addCategory(name: String, icon: String, colorArgb: Int): Long
    suspend fun addSubcategory(categoryId: Long, name: String): Long
    suspend fun renameCategory(id: Long, name: String, icon: String, colorArgb: Int)
    suspend fun setCategoryArchived(id: Long, archived: Boolean)
    suspend fun setSubcategoryArchived(id: Long, archived: Boolean)
    suspend fun deleteCategoryIfUnused(id: Long): Boolean   // false если категория используется
    suspend fun deleteSubcategoryIfUnused(id: Long): Boolean
}
```

### `data/db/` — Room
`CategoryEntity`, `SubcategoryEntity`, `ExpenseEntity` (поле даты — `epochDay: Long`),
`CategoryDao`, `ExpenseDao`, `ExpenseRow`, `AppDatabase.get(context)` +
`AppDatabase` имеет `categoryDao()` и `expenseDao()`.

### `data/DefaultCategories.kt`
```kotlin
object DefaultCategories {
    data class Seed(val name: String, val icon: String, val colorArgb: Int, val subcategories: List<String>)
    val tree: List<Seed>            // 17 категорий с подкатегориями
    val palette: List<Int>          // цвета ARGB для новых категорий
    val iconChoices: List<String>   // эмодзи-иконки
}
```

### `AppContainer.kt` / `SpendApp.kt`
```kotlin
class AppContainer(context: Context) { val repository: ExpenseRepository }
class SpendApp : Application() { val container: AppContainer }
```

### `ui/theme/Theme.kt`
```kotlin
@Composable fun SpendTrackerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
val ChartPalette: List<Color>   // 12 цветов для графиков
```

---

## Контракты того, что ещё пишется (разные агенты — соблюдать сигнатуры дословно)

### `domain/stats/StatsCalculator.kt`
```kotlin
object StatsCalculator {
    fun compute(all: List<ExpenseRecord>, period: Period): PeriodStats
    fun compare(all: List<ExpenseRecord>, period: Period): Comparison
    fun dataBounds(all: List<ExpenseRecord>): DateRange?
    fun availableMonths(all: List<ExpenseRecord>): List<YearMonth>   // по убыванию
}
```

### `export/` 
```kotlin
object CsvExporter {
    fun export(records: List<ExpenseRecord>): String
}
object JsonExporter {
    fun export(records: List<ExpenseRecord>, generatedAtEpochMillis: Long, appVersion: String): String
}
object AnalysisBundle {
    /** Готовый markdown-отчёт, который пользователь отдаёт LLM с просьбой оптимизировать траты. */
    fun build(all: List<ExpenseRecord>, today: LocalDate): String
}
enum class ExportFormat(val ext: String, val mime: String, val title: String) {
    CSV("csv", "text/csv", "CSV — таблица"),
    JSON("json", "application/json", "JSON — полные данные"),
    ANALYSIS("md", "text/markdown", "Отчёт для ИИ-анализа"),
}
class ExportManager(context: Context) {
    fun write(format: ExportFormat, content: String, today: LocalDate): Uri
    fun shareIntent(uri: Uri, format: ExportFormat): Intent
}
```
FileProvider уже объявлен в манифесте: authority = `"${applicationId}.fileprovider"`,
пути — `<cache-path name="exports" path="exports/" />`. Файлы писать в `context.cacheDir/exports/`.

### `ui/components/Charts.kt`
```kotlin
data class ChartSlice(val label: String, val value: Long, val color: Color)
data class ChartBar(val label: String, val value: Long, val color: Color)
data class ChartLinePoint(val label: String, val value: Long)

@Composable fun DonutChart(slices: List<ChartSlice>, modifier: Modifier = Modifier,
                           centerTitle: String = "", centerValue: String = "",
                           selectedIndex: Int? = null, onSliceClick: ((Int) -> Unit)? = null)
@Composable fun BarChart(bars: List<ChartBar>, modifier: Modifier = Modifier,
                         valueFormatter: (Long) -> String = { MoneyFormat.formatCompact(it) })
@Composable fun LineChart(points: List<ChartLinePoint>, modifier: Modifier = Modifier,
                          showArea: Boolean = true,
                          valueFormatter: (Long) -> String = { MoneyFormat.formatCompact(it) })
@Composable fun HorizontalBarList(items: List<ChartBar>, modifier: Modifier = Modifier,
                                  valueFormatter: (Long) -> String = { MoneyFormat.format(it) },
                                  onItemClick: ((Int) -> Unit)? = null)
@Composable fun ChartLegend(slices: List<ChartSlice>, modifier: Modifier = Modifier,
                            valueFormatter: (Long) -> String = { MoneyFormat.format(it) })
```

### Экраны (сигнатуры фиксированы — навигация вызывает именно так)
```kotlin
// ui/add/AddExpenseScreen.kt
@Composable fun AddExpenseScreen(repository: ExpenseRepository, editExpenseId: Long?,
                                 onDone: () -> Unit, onManageCategories: () -> Unit)
// ui/list/ExpenseListScreen.kt
@Composable fun ExpenseListScreen(repository: ExpenseRepository, onEdit: (Long) -> Unit)
// ui/stats/StatsScreen.kt
@Composable fun StatsScreen(repository: ExpenseRepository)
// ui/categories/CategoriesScreen.kt
@Composable fun CategoriesScreen(repository: ExpenseRepository, onBack: () -> Unit)
// ui/exportui/ExportScreen.kt
@Composable fun ExportScreen(repository: ExpenseRepository)
// ui/pending/PendingScreen.kt
@Composable fun PendingScreen(repository: ExpenseRepository, settings: SettingsStore,
                              onOpenSettings: () -> Unit)
// ui/settings/SettingsScreen.kt
@Composable fun SettingsScreen(settings: SettingsStore, onOpenImport: () -> Unit,
                               onOpenExport: () -> Unit, onOpenCategories: () -> Unit)
```

### Автоучёт из уведомлений (`notifications/`, БД v3)

Правило, которое не нарушается: **из уведомления трата не создаётся никогда** — только
запись в `pending_operations`. В `expenses` она попадает лишь через
`confirmPendingOperation`, то есть по действию пользователя.

```kotlin
// notifications/BankCatalog.kt — какие пакеты слушаем
data class BankSource(val code: String, val title: String, val packages: Set<String>,
                      val enabledByDefault: Boolean = true, val isSms: Boolean = false)
object BankCatalog {
    val sources: List<BankSource>
    val defaultEnabledCodes: Set<String>
    fun byPackage(packageName: String?): BankSource?
    fun title(code: String): String
    fun bankBySmsSender(sender: String?): String?
}

// notifications/NotificationParser.kt — чистая функция, без Android
object NotificationParser {
    fun parse(packageName: String, title: String?, text: String?, postedAtMillis: Long): ParsedNotification?
}
enum class NotificationKind { PURCHASE, WITHDRAWAL, TRANSFER_OUT, INCOME, REFUND, UNKNOWN }
data class ParsedNotification(/* bank, kind, amountMinor, currency, merchant, cardMask, rawText… */) {
    val isRecognized: Boolean
    val dedupKey: String        // он же externalId подтверждённой траты
}

// notifications/MerchantNormalizer.kt
object MerchantNormalizer {
    fun display(raw: String?): String
    fun key(raw: String?): String            // латиница, верхний регистр, без ООО/городов/цифр
    fun matches(a: String?, b: String?): Boolean
}
object MerchantDictionary { fun suggest(merchant: String?): BankCategoryMapper.Suggestion? }

// notifications/ — Android-часть
class BankNotificationListener : NotificationListenerService()
class NotificationIntake(repository, settings) { suspend fun handle(pkg, title, text, postedAt): Long? }
class PendingNotifier(context) { fun ensureChannel(); fun notifyPending(op); fun cancel(id) }
class PendingActionReceiver : BroadcastReceiver()   // кнопки в шторке
object NotificationAccess {
    fun isGranted(context): Boolean
    fun settingsIntent(): Intent
    fun isInstalled(context, source: BankSource): Boolean
}

// data/SettingsStore.kt
data class AutoCaptureSettings(val enabled: Boolean, val enabledBanks: Set<String>,
                               val notifyOnCapture: Boolean, val onboardingShown: Boolean)
class SettingsStore(context) {
    fun current(): AutoCaptureSettings
    fun observe(): Flow<AutoCaptureSettings>
    fun setEnabled(enabled: Boolean); fun setBankEnabled(code: String, enabled: Boolean)
    fun setNotifyOnCapture(enabled: Boolean); fun setOnboardingShown()
}

// data/ExpenseRepository.kt — новые методы (существующие не менялись)
fun observePendingOperations(): Flow<List<PendingOperation>>
fun observePendingCount(): Flow<Int>
suspend fun addPendingOperation(entry: PendingEntry): Long?
suspend fun getPendingOperation(id: Long): PendingOperation?
suspend fun setPendingCategory(id: Long, categoryId: Long?, subcategoryId: Long?)
suspend fun confirmPendingOperation(id: Long, draft: ExpenseDraft): ConfirmResult
suspend fun rejectPendingOperation(id: Long): PendingEntry?     // вернуть можно через add
suspend fun rejectAllPendingOperations(): Int
suspend fun findSuspectedDuplicates(operations: List<PendingOperation>, windowDays: Int = 1): Set<Long>
```

### Общие требования к коду
- Комментарии и строки UI — на русском.
- Никаких новых Gradle-зависимостей. Доступно: Compose BOM 2024.12.01 (ui, material3,
  material-icons-extended), navigation-compose 2.8.5, lifecycle-viewmodel-compose 2.8.7,
  room 2.6.1, kotlinx-serialization-json 1.7.3, kotlinx-coroutines 1.9.0.
  В тестах: junit4, truth, robolectric 4.14.1, androidx.test:core, room-testing, coroutines-test.
- ViewModel создавать так:
  ```kotlin
  val vm: XViewModel = viewModel(factory = viewModelFactory {
      initializer { XViewModel(repository) }
  })
  ```
  (`androidx.lifecycle.viewmodel.initializer`, `androidx.lifecycle.viewmodel.viewModelFactory`,
  `androidx.lifecycle.viewmodel.compose.viewModel`)
- Не запускать Gradle — сборкой и починкой ошибок занимается координатор.
