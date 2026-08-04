package com.dtyan.spendtracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.model.CategoryTree
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Сообщение для Snackbar. Удаление категории/подкатегории может быть заблокировано —
 * тогда предлагаем архивировать («скрыть») вместо удаления.
 */
sealed interface CategoriesMessage {
    /** Категорию нельзя удалить: на неё ссылаются траты. */
    data class CategoryInUse(val categoryId: Long) : CategoriesMessage

    /** Подкатегорию нельзя удалить: на неё ссылаются траты. */
    data class SubcategoryInUse(val subcategoryId: Long) : CategoriesMessage

    data class Info(val text: String) : CategoriesMessage
}

data class CategoriesUiState(
    val tree: List<CategoryTree> = emptyList(),
    val showArchived: Boolean = false,
    val loading: Boolean = true,
)

class CategoriesViewModel(
    private val repository: ExpenseRepository,
) : ViewModel() {

    private val showArchived = MutableStateFlow(false)

    private val _message = MutableStateFlow<CategoriesMessage?>(null)
    val message: StateFlow<CategoriesMessage?> = _message.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<CategoriesUiState> =
        showArchived.flatMapLatest { includeArchived ->
            repository.observeCategoryTree(includeArchived = includeArchived).map { tree ->
                CategoriesUiState(tree = tree, showArchived = includeArchived, loading = false)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CategoriesUiState(),
        )

    fun toggleShowArchived() {
        showArchived.value = !showArchived.value
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun addCategory(name: String, icon: String, colorArgb: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.addCategory(name, icon, colorArgb) }
                .onFailure { _message.value = CategoriesMessage.Info("Не удалось создать категорию") }
        }
    }

    fun renameCategory(id: Long, name: String, icon: String, colorArgb: Int) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.renameCategory(id, name, icon, colorArgb) }
    }

    fun addSubcategory(categoryId: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addSubcategory(categoryId, name) }
    }

    /** Пытается удалить категорию; если она используется — просит показать подсказку про «Скрыть». */
    fun deleteCategory(id: Long) {
        viewModelScope.launch {
            val deleted = repository.deleteCategoryIfUnused(id)
            if (!deleted) _message.value = CategoriesMessage.CategoryInUse(id)
        }
    }

    fun deleteSubcategory(id: Long) {
        viewModelScope.launch {
            val deleted = repository.deleteSubcategoryIfUnused(id)
            if (!deleted) _message.value = CategoriesMessage.SubcategoryInUse(id)
        }
    }

    fun setCategoryArchived(id: Long, archived: Boolean) {
        viewModelScope.launch { repository.setCategoryArchived(id, archived) }
    }

    fun setSubcategoryArchived(id: Long, archived: Boolean) {
        viewModelScope.launch { repository.setSubcategoryArchived(id, archived) }
    }
}
