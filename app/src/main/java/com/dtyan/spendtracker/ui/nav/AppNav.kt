package com.dtyan.spendtracker.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.notifications.NotificationAccess
import com.dtyan.spendtracker.ui.add.AddExpenseScreen
import com.dtyan.spendtracker.ui.categories.CategoriesScreen
import com.dtyan.spendtracker.ui.exportui.ExportScreen
import com.dtyan.spendtracker.ui.importui.ImportScreen
import com.dtyan.spendtracker.ui.list.ExpenseListScreen
import com.dtyan.spendtracker.ui.onboarding.AutoCaptureOnboardingDialog
import com.dtyan.spendtracker.ui.pending.PendingScreen
import com.dtyan.spendtracker.ui.settings.SettingsScreen
import com.dtyan.spendtracker.ui.stats.StatsScreen

/** Маршруты приложения. Вкладки нижней навигации + «полноэкранные» маршруты. */
object Routes {
    const val LIST = "list"
    const val STATS = "stats"
    const val PENDING = "pending"
    const val MORE = "more"
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
    Tab(Routes.PENDING, "Черновики", Icons.Filled.Inbox),
    Tab(Routes.MORE, "Ещё", Icons.Filled.MoreHoriz),
)

/**
 * Каркас приложения: нижняя навигация и FAB «добавить трату».
 *
 * Вкладок намеренно четыре: разделы, в которые заходят изредка (импорт, экспорт, категории,
 * настройки автоучёта), собраны в «Ещё», а на виду — то, что открывают каждый день,
 * включая «Черновики» со счётчиком неразобранных операций.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(
    repository: ExpenseRepository,
    settings: SettingsStore,
    /**
     * Счётчик нажатий на уведомление о распознанной операции: при каждом увеличении
     * открываем «Черновики». Именно счётчик, а не флаг, — чтобы срабатывало повторно.
     */
    openPendingTicket: Int = 0,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current

    val pendingCount by repository.observePendingCount().collectAsState(initial = 0)

    val isFullScreenRoute = currentRoute == Routes.ADD || currentRoute == Routes.EDIT
    val showFab = currentRoute == Routes.LIST || currentRoute == Routes.STATS

    // Переход из уведомления сразу в очередь подтверждения.
    LaunchedEffect(openPendingTicket) {
        if (openPendingTicket > 0) navController.switchTab(Routes.PENDING)
    }

    // Онбординг автоучёта показывается один раз при первом запуске. Пропустить можно —
    // включить позже во вкладке «Ещё».
    var showOnboarding by remember { mutableStateOf(!settings.current().onboardingShown) }

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
                            icon = {
                                if (tab.route == Routes.PENDING && pendingCount > 0) {
                                    BadgedBox(badge = { Badge { Text("$pendingCount") } }) {
                                        Icon(tab.icon, contentDescription = tab.title)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.title)
                                }
                            },
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
            composable(Routes.PENDING) {
                PendingScreen(
                    repository = repository,
                    settings = settings,
                    onOpenSettings = { navController.switchTab(Routes.MORE) },
                )
            }
            composable(Routes.MORE) {
                SettingsScreen(
                    settings = settings,
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenExport = { navController.navigate(Routes.EXPORT) },
                    onOpenCategories = { navController.navigate(Routes.CATEGORIES) },
                )
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
                    onBack = {
                        if (!navController.popBackStack()) navController.switchTab(Routes.LIST)
                    },
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

    if (showOnboarding) {
        AutoCaptureOnboardingDialog(
            onEnable = {
                settings.setEnabled(true)
                settings.setOnboardingShown()
                showOnboarding = false
                runCatching { context.startActivity(NotificationAccess.settingsIntent()) }
            },
            onSkip = {
                settings.setOnboardingShown()
                showOnboarding = false
                navController.switchTab(Routes.LIST)
            },
        )
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
