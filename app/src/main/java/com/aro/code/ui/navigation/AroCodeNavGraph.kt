package com.aro.code.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aro.code.ui.chat.AgentChatScreen
import com.aro.code.ui.editor.CodeEditorScreen
import com.aro.code.ui.settings.SettingsScreen
import com.aro.code.ui.workspace.WorkspaceScreen

sealed class Screen(val route: String) {
    object Workspace : Screen("workspace")
    object Editor    : Screen("editor/{filePath}") {
        fun createRoute(filePath: String) = "editor/${filePath}"
    }
    object Chat     : Screen("chat")
    object Settings : Screen("settings")
}

@Composable
fun AroCodeNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Workspace.route) {

        composable(Screen.Workspace.route) {
            WorkspaceScreen(
                onFileOpen    = { path -> navController.navigate(Screen.Editor.createRoute(path)) },
                onChatOpen    = { navController.navigate(Screen.Chat.route) },
                onSettingsOpen = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route     = Screen.Editor.route,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStack ->
            val path = backStack.arguments?.getString("filePath") ?: ""
            CodeEditorScreen(filePath = path)
        }

        composable(Screen.Chat.route) {
            AgentChatScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
