package com.aro.code.ui.navigation

import android.util.Base64
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
        fun createRoute(filePath: String): String {
            val encoded = Base64.encodeToString(
                filePath.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
            return "editor/$encoded"
        }
    }
    object Chat     : Screen("chat?engine={engine}&file={file}") {
        fun createRoute(engine: String = "antigravity", file: String = ""): String {
            val encodedFile = if (file.isNotBlank()) {
                Base64.encodeToString(file.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            } else ""
            return "chat?engine=$engine&file=$encodedFile"
        }
    }
    object Settings : Screen("settings")
}

@Composable
fun AroCodeNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Screen.Workspace.route) {

        composable(Screen.Workspace.route) {
            WorkspaceScreen(
                onFileOpen = { path -> navController.navigate(Screen.Editor.createRoute(path)) },
                onChatOpen = { engine -> navController.navigate(Screen.Chat.createRoute(engine)) },
                onSettingsOpen = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Editor.route,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStack ->
            val rawParam = backStack.arguments?.getString("filePath") ?: ""
            val decodedPath = runCatching {
                val bytes = Base64.decode(rawParam, Base64.URL_SAFE or Base64.NO_WRAP)
                String(bytes, Charsets.UTF_8)
            }.getOrDefault(rawParam)

            CodeEditorScreen(
                filePath = decodedPath,
                onBack = { navController.popBackStack() },
                onAskAi = { path ->
                    navController.navigate(Screen.Chat.createRoute(engine = "antigravity", file = path))
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("engine") {
                    type = NavType.StringType
                    defaultValue = "antigravity"
                },
                navArgument("file") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStack ->
            val engineStr = backStack.arguments?.getString("engine") ?: "antigravity"
            val fileParam = backStack.arguments?.getString("file") ?: ""
            val decodedFile = if (fileParam.isNotBlank()) {
                runCatching {
                    val bytes = Base64.decode(fileParam, Base64.URL_SAFE or Base64.NO_WRAP)
                    String(bytes, Charsets.UTF_8)
                }.getOrDefault("")
            } else ""

            val engine = if (engineStr.equals("gemini", ignoreCase = true)) {
                com.aro.code.ui.chat.ChatEngine.GEMINI
            } else {
                com.aro.code.ui.chat.ChatEngine.ANTIGRAVITY
            }
            AgentChatScreen(
                initialEngine = engine,
                initialFilePath = decodedFile,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
