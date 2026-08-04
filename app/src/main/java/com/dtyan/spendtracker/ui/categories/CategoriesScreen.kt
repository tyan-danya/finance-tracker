package com.dtyan.spendtracker.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dtyan.spendtracker.data.DefaultCategories
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.domain.model.CategoryTree
import com.dtyan.spendtracker.domain.model.Subcategory
import com.dtyan.spendtracker.ui.components.ColorPickerRow
import com.dtyan.spendtracker.ui.components.ConfirmDialog
import com.dtyan.spendtracker.ui.components.IconPickerGrid
import com.dtyan.spendtracker.ui.components.TextInputDialog

/**
 * Справочник категорий: раскрывающийся список с подкатегориями,
 * создание/удаление и «скрытие» (архивирование) вместо удаления для используемых записей.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    repository: ExpenseRepository,
    onBack: () -> Unit,
) {
    val vm: CategoriesViewModel = viewModel(
        factory = viewModelFactory { initializer { CategoriesViewModel(repository) } }
    )
    val state by vm.uiState.collectAsState()
    val message by vm.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    var showAddCategory by remember { mutableStateOf(false) }
    // id категории, для которой открыт диалог создания подкатегории
    var addSubcategoryFor by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteCategory by remember { mutableStateOf<CategoryTree?>(null) }
    var confirmDeleteSubcategory by remember { mutableStateOf<Subcategory?>(null) }

    // Показ Snackbar с предложением скрыть то, что нельзя удалить.
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        when (current) {
            is CategoriesMessage.CategoryInUse -> {
                val result = snackbarHostState.showSnackbar(
                    message = "Категория используется в тратах. Её можно скрыть",
                    actionLabel = "Скрыть",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    vm.setCategoryArchived(current.categoryId, true)
                }
            }

            is CategoriesMessage.SubcategoryInUse -> {
                val result = snackbarHostState.showSnackbar(
                    message = "Подкатегория используется в тратах. Её можно скрыть",
                    actionLabel = "Скрыть",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    vm.setSubcategoryArchived(current.subcategoryId, true)
                }
            }

            is CategoriesMessage.Info -> snackbarHostState.showSnackbar(current.text)
        }
        vm.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Категории") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleShowArchived() }) {
                        Icon(
                            imageVector = if (state.showArchived) Icons.Filled.Visibility
                            else Icons.Filled.VisibilityOff,
                            contentDescription = if (state.showArchived) "Скрыть скрытые"
                            else "Показывать скрытые",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddCategory = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Категория") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.tree.isEmpty() && !state.loading) {
                item {
                    Text(
                        text = "Категорий пока нет. Добавьте первую кнопкой «Категория».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            items(state.tree, key = { it.category.id }) { tree ->
                CategoryCard(
                    tree = tree,
                    expanded = expanded[tree.category.id] == true,
                    onToggleExpand = {
                        expanded[tree.category.id] = expanded[tree.category.id] != true
                    },
                    onDeleteCategory = { confirmDeleteCategory = tree },
                    onRestoreCategory = { vm.setCategoryArchived(tree.category.id, false) },
                    onAddSubcategory = { addSubcategoryFor = tree.category.id },
                    onDeleteSubcategory = { sub -> confirmDeleteSubcategory = sub },
                    onRestoreSubcategory = { sub -> vm.setSubcategoryArchived(sub.id, false) },
                )
            }

            if (state.showArchived) {
                item {
                    Text(
                        text = "Скрытые категории и подкатегории показаны приглушённо — " +
                            "их можно вернуть кнопкой «Вернуть».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showAddCategory) {
        AddCategoryDialog(
            onDismiss = { showAddCategory = false },
            onConfirm = { name, icon, color ->
                vm.addCategory(name, icon, color)
                showAddCategory = false
            },
        )
    }

    addSubcategoryFor?.let { categoryId ->
        TextInputDialog(
            title = "Новая подкатегория",
            label = "Название",
            onDismiss = { addSubcategoryFor = null },
            onConfirm = { name ->
                vm.addSubcategory(categoryId, name)
                addSubcategoryFor = null
            },
        )
    }

    confirmDeleteCategory?.let { tree ->
        ConfirmDialog(
            title = "Удалить категорию?",
            text = "«${tree.category.name}» будет удалена вместе с подкатегориями. " +
                "Если по ней есть траты, удаление не выполнится — категорию можно скрыть.",
            onDismiss = { confirmDeleteCategory = null },
            onConfirm = {
                vm.deleteCategory(tree.category.id)
                confirmDeleteCategory = null
            },
        )
    }

    confirmDeleteSubcategory?.let { sub ->
        ConfirmDialog(
            title = "Удалить подкатегорию?",
            text = "«${sub.name}» будет удалена. Если по ней есть траты, удаление не выполнится.",
            onDismiss = { confirmDeleteSubcategory = null },
            onConfirm = {
                vm.deleteSubcategory(sub.id)
                confirmDeleteSubcategory = null
            },
        )
    }
}

// --- элементы списка ---

@Composable
private fun CategoryCard(
    tree: CategoryTree,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDeleteCategory: () -> Unit,
    onRestoreCategory: () -> Unit,
    onAddSubcategory: () -> Unit,
    onDeleteSubcategory: (Subcategory) -> Unit,
    onRestoreSubcategory: (Subcategory) -> Unit,
) {
    val category = tree.category
    val dimmed = category.archived

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (dimmed) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dimmed) 0.dp else 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Цветовая метка категории слева
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(5.dp)
                    .height(44.dp)
                    .background(
                        Color(category.colorArgb).copy(alpha = if (dimmed) 0.4f else 1f),
                        RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
                    )
            )
            Spacer(Modifier.width(10.dp))
            Text(text = category.icon, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append(subcategoriesLabel(tree.subcategories.size))
                        if (category.isBuiltIn) append(" · встроенная")
                        if (category.archived) append(" · скрыта")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (category.archived) {
                TextButton(onClick = onRestoreCategory) { Text("Вернуть") }
            } else {
                IconButton(onClick = onDeleteCategory) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Удалить категорию",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }

        if (expanded) {
            HorizontalDivider()
            Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                if (tree.subcategories.isEmpty()) {
                    Text(
                        text = "Подкатегорий нет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                tree.subcategories.forEach { sub ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = sub.name + if (sub.archived) " · скрыта" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sub.archived) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (sub.archived) {
                            TextButton(onClick = { onRestoreSubcategory(sub) }) { Text("Вернуть") }
                        } else {
                            IconButton(onClick = { onDeleteSubcategory(sub) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Удалить подкатегорию",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onAddSubcategory) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Подкатегория")
                }
            }
        }
    }
}

/** Русское склонение для «N подкатегорий». */
private fun subcategoriesLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "подкатегорий"
        mod10 == 1 -> "подкатегория"
        mod10 in 2..4 -> "подкатегории"
        else -> "подкатегорий"
    }
    return "$count $word"
}

/** Диалог создания категории: название + эмодзи + цвет. */
@Composable
private fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String, colorArgb: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(DefaultCategories.iconChoices.first()) }
    var color by remember { mutableStateOf(DefaultCategories.palette.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая категория") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Иконка", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                IconPickerGrid(selected = icon, onSelect = { icon = it })
                Spacer(Modifier.height(12.dp))
                Text("Цвет", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                ColorPickerRow(
                    colors = DefaultCategories.palette,
                    selected = color,
                    onSelect = { color = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), icon, color) },
                enabled = name.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
