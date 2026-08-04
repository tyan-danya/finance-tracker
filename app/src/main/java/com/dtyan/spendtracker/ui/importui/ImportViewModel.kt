package com.dtyan.spendtracker.ui.importui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.DuplicateVerdict
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.ImportSummary
import com.dtyan.spendtracker.importer.OperationKind
import com.dtyan.spendtracker.importer.ParseResult
import com.dtyan.spendtracker.importer.StatementImporter
import com.dtyan.spendtracker.importer.TinkoffStatementParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Состояние экрана импорта банковской выписки.
 *
 * Поток экрана: [Idle] → выбор файла → [Loading] (чтение+разбор) → [Parsed] (предпросмотр
 * с выбором операций) → импорт → [Done] (сводка) либо [Error] на любом сбое.
 */
sealed interface ImportUiState {

    /** Стартовый экран: пояснение и кнопка выбора файла. */
    data object Idle : ImportUiState

    /** Идёт чтение и разбор файла. */
    data object Loading : ImportUiState

    /**
     * Файл разобран, показываем предпросмотр.
     *
     * @param selected индексы операций в [ParseResult.operations], отмеченные к импорту.
     *                 Управляем выбором именно по индексам — стабильного id у операции нет.
     * @param importing true, пока идёт запись в базу (крутилка на кнопке).
     */
    data class Parsed(
        val result: ParseResult,
        val fileName: String?,
        val selected: Set<Int>,
        /** Вердикт дубликата для каждой операции (тот же порядок, что и result.operations). */
        val verdicts: List<DuplicateVerdict>,
        val importing: Boolean = false,
    ) : ImportUiState {
        /** Сколько операций отмечено к импорту. */
        val selectedCount: Int get() = selected.size

        fun verdictAt(index: Int): DuplicateVerdict =
            verdicts.getOrElse(index) { DuplicateVerdict.NONE }

        /** Сколько операций похоже на уже существующие записи (в т.ч. добавленные вручную). */
        val suspectedCount: Int get() = verdicts.count { it == DuplicateVerdict.SUSPECTED }

        /** Сколько операций уже импортировалось ранее. */
        val alreadyImportedCount: Int get() = verdicts.count { it == DuplicateVerdict.ALREADY_IMPORTED }
    }

    /** Импорт завершён — показываем сводку и кнопки отката / нового импорта. */
    data class Done(
        val summary: ImportSummary,
        val fileName: String?,
    ) : ImportUiState

    /** Ошибка чтения/разбора/импорта. */
    data class Error(val message: String) : ImportUiState
}

/**
 * Вью-модель экрана импорта. Держит репозиторий и всё состояние предпросмотра;
 * чтение байтов файла делегируется вызывающему (чтобы не тянуть Android Context в модель).
 */
class ImportViewModel(
    private val repository: ExpenseRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    // Разовые сообщения для Snackbar (например, «Импорт отменён»).
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Пользователь выбрал файл. [reader] читает его байты (в вызывающем коде — через
     * contentResolver); здесь чтение и разбор уходят на фоновые диспетчеры с индикатором.
     */
    fun onFilePicked(fileName: String?, reader: suspend () -> ByteArray?) {
        viewModelScope.launch {
            _state.value = ImportUiState.Loading

            val bytes = withContext(Dispatchers.IO) {
                runCatching { reader() }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                _state.value = ImportUiState.Error("Не удалось прочитать файл. Попробуйте выбрать его ещё раз.")
                return@launch
            }

            val result = withContext(Dispatchers.Default) {
                runCatching { TinkoffStatementParser.parse(bytes) }.getOrNull()
            }
            if (result == null) {
                _state.value = ImportUiState.Error("Не удалось разобрать файл. Это выписка Т-Банка в формате CSV?")
                return@launch
            }
            if (result.operations.isEmpty()) {
                val detail = result.warnings.firstOrNull()?.let { "\n\n$it" } ?: ""
                _state.value = ImportUiState.Error(
                    "В файле не найдено ни одной операции.$detail"
                )
                return@launch
            }

            // Проверяем каждую операцию на дублирование против уже существующих записей
            // (в т.ч. добавленных вручную) — спорные покажем отдельно и не отметим по умолчанию.
            val verdicts = withContext(Dispatchers.IO) {
                runCatching {
                    repository.checkDuplicates(StatementImporter.toEntries(result.operations))
                }.getOrElse { List(result.operations.size) { DuplicateVerdict.NONE } }
            }

            // По умолчанию отмечаем операции тех типов, что импортируются автоматически
            // (покупки и доходы), КРОМЕ похожих на уже существующие — их решает пользователь.
            val selected = result.operations.indices
                .filter {
                    result.operations[it].kind.includedByDefault &&
                        verdicts.getOrElse(it) { DuplicateVerdict.NONE } == DuplicateVerdict.NONE
                }
                .toSet()

            _state.value = ImportUiState.Parsed(
                result = result,
                fileName = fileName,
                selected = selected,
                verdicts = verdicts,
            )
        }
    }

    /** Включить/выключить отдельную операцию (чекбокс на строке). */
    fun toggleOperation(index: Int) {
        val current = _state.value as? ImportUiState.Parsed ?: return
        if (current.importing) return
        val selected = current.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        _state.value = current.copy(selected = selected)
    }

    /** Включить/выключить всю группу операций одного типа (Switch у заголовка группы). */
    fun setGroupSelected(kind: OperationKind, include: Boolean) {
        val current = _state.value as? ImportUiState.Parsed ?: return
        if (current.importing) return
        val ops = current.result.operations
        val groupIndices = ops.indices.filter { ops[it].kind == kind }
        val selected = current.selected.toMutableSet()
        if (include) selected.addAll(groupIndices) else selected.removeAll(groupIndices.toSet())
        _state.value = current.copy(selected = selected)
    }

    /** Запускает импорт отмеченных операций. */
    fun runImport() {
        val current = _state.value as? ImportUiState.Parsed ?: return
        if (current.importing) return
        val ops = current.result.operations
        val selectedOps = ops.filterIndexed { i, _ -> i in current.selected }
        if (selectedOps.isEmpty()) return

        _state.value = current.copy(importing = true)
        viewModelScope.launch {
            val summary = runCatching {
                val entries = StatementImporter.toEntries(selectedOps)
                withContext(Dispatchers.IO) {
                    repository.importEntries(
                        entries = entries,
                        bank = current.result.bank,
                        fileName = current.fileName,
                    )
                }
            }.getOrElse {
                _state.value = current.copy(importing = false)
                _message.value = "Не удалось импортировать операции"
                return@launch
            }
            _state.value = ImportUiState.Done(summary = summary, fileName = current.fileName)
        }
    }

    /** Откат последнего импорта: удаляет добавленные операции и возвращает на стартовый экран. */
    fun undoImport() {
        val done = _state.value as? ImportUiState.Done ?: return
        val batchId = done.summary.batchId ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.undoImport(batchId) } }
                .onSuccess {
                    _message.value = "Импорт отменён"
                    _state.value = ImportUiState.Idle
                }
                .onFailure { _message.value = "Не удалось отменить импорт" }
        }
    }

    /** Сбросить экран в начальное состояние (для «Импортировать ещё» или после ошибки). */
    fun reset() {
        _state.value = ImportUiState.Idle
    }
}
