package com.eiamjun.event.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.eiamjun.event.ui.menu.MenuEditScreen
import com.eiamjun.event.ui.order.OrderScreen
import com.eiamjun.event.ui.queue.QueueScreen
import com.eiamjun.event.ui.settings.SettingsScreen

/**
 * Four destinations, and the till is the start.
 *
 * Flat — no nested graphs and nothing passed between screens but a back action. The staff reach
 * คิว and ตั้งค่า from the till's own app bar and come straight back; anything deeper than that
 * is a tap spent while somebody waits.
 */
object Routes {
    const val ORDER = "order"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val MENU = "menu"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.ORDER) {
        composable(Routes.ORDER) {
            OrderScreen(
                onOpenQueue = { nav.navigate(Routes.QUEUE) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.QUEUE) {
            QueueScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenMenuEditor = { nav.navigate(Routes.MENU) }
            )
        }
        composable(Routes.MENU) {
            MenuEditScreen(onBack = { nav.popBackStack() })
        }
    }
}
