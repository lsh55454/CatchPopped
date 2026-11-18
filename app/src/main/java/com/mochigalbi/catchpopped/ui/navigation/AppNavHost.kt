package com.mochigalbi.catchpopped.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mochigalbi.catchpopped.ui.detail.DetailScreen
import com.mochigalbi.catchpopped.ui.home.HomeScreen
import com.mochigalbi.catchpopped.ui.record.RecordScreen
import com.mochigalbi.catchpopped.ui.settings.SettingsScreen

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onRecord = { navController.navigate("record") },
                onOpen = { id -> navController.navigate("detail/$id") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable("record") {
            RecordScreen(onDone = { navController.popBackStack() })
        }
        composable(
            route = "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                id = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
