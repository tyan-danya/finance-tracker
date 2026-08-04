package com.dtyan.spendtracker.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.ui.add.AddExpenseScreen
import com.dtyan.spendtracker.ui.categories.CategoriesScreen
import com.dtyan.spendtracker.ui.exportui.ExportScreen
import com.dtyan.spendtracker.ui.importui.ImportScreen
import com.dtyan.spendtracker.ui.list.ExpenseListScreen
import com.dtyan.spendtracker.ui.stats.StatsScreen

/** Маршруты приложения. Вкладки нижней навигации + два «полноэкранных» маршрута. */
object Routes {
    const val LIST = "list"
    const val STATS = "stats"
    const val IMPORT = "import"
    const val EXPORT = "export"
    const val CATEGORIES = "categories"
    const val ADD = "add"
    const val EDIT = "edit/{expenseId}"
    const val EDIT_ARG = "expenseId"

    fun edit(id: Long) = "edit/$id"
}

private data class Tab(val route: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.LIST, "Записи", Icons.AutoMirrored.Filled.List),
    Tab(Routes.STATS, "Статистика", Icons.Filled.PieChart),
    Tab(Routes.IMPORT, "Импорт", Icons.Filled.FileDownload),
    Tab(Routes.EXPORT, "Экспорт", Icons.Filled.Share),
    Tab(Routes.CATEGORIES, "Категории", Icons.Filled.Category),
)

/**
 * Каркас приложения: нижняя навигация из четырёх вкладок и FAB «добавить трату».
 * На экранах создания/редактирования панель и FAB скрываются — там свой Scaffold.
 */
@Composable
fun AppNav(repository: ExpenseRepository) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val isFullScreenRoute = currentRoute == Routes.ADD || currentRoute == Routes.EDIT
    val showFab = currentRoute == Routes.LIST || currentRoute == Routes.STATS

    Scaffold(
        // Инсеты обрабатывают сами экраны (у каждого свой Scaffold с TopAppBar),
        // иначе статус-бар будет отступлен дважды.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!isFullScreenRoute) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showFab) {
                FloatingActionButton(onClick = { navController.navigate(Routes.ADD) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Добавить трату")
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIST,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.LIST) {
                ExpenseListScreen(
                    repository = repository,
                    onEdit = { id -> navController.navigate(Routes.edit(id)) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(repository = repository)
            }
            composable(Routes.IMPORT) {
                ImportScreen(repository = repository)
            }
            composable(Routes.EXPORT) {
                ExportScreen(repository = repository)
            }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    repository = repository,
                    // На вкладке «Категории» уходить некуда — возвращаемся к записям.
                    onBack = { navController.switchTab(Routes.LIST) },
                )
            }
            composable(Routes.ADD) {
                AddExpenseScreen(
                    repository = repository,
                    editExpenseId = null,
                    onDone = { navController.popBackStack() },
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                )
            }
            composable(
                route = Routes.EDIT,
                arguments = listOf(navArgument(Routes.EDIT_ARG) { type = NavType.LongType }),
            ) { entry ->
                val expenseId = entry.arguments?.getLong(Routes.EDIT_ARG) ?: -1L
                AddExpenseScreen(
                    repository = repository,
                    editExpenseId = expenseId,
                    onDone = { navController.popBackStack() },
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                )
            }
        }
    }
}

/** Переход по вкладке: без накопления стека и с сохранением состояния экрана. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
