package com.eiamjun.event

import android.app.Application
import com.eiamjun.event.data.AppDatabase
import com.eiamjun.event.data.EventLog
import com.eiamjun.event.data.SeedData
import com.eiamjun.event.net.KitchenSync
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Two things happen once per process, and both are here rather than on a screen.
 *
 * The menu is seeded into an empty database, and the outbox is drained. The shop's till learned
 * why the second one cannot live on a screen: hung off the till, a push only happened while
 * somebody was looking at that till, so a price edited elsewhere never went up. Here it runs for
 * the life of the process — which for a handheld at a stall is the whole service.
 */
@HiltAndroidApp
class EventApplication : Application() {

    @Inject lateinit var database: AppDatabase
    @Inject lateinit var sync: KitchenSync
    @Inject lateinit var log: EventLog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { SeedData.seed(database.menuDao()) }
                .onFailure { log.error("ใส่เมนูตั้งต้นไม่สำเร็จ: ${it.message}") }
            // Anything left from the last service goes up as soon as there is a network, before
            // anybody taps anything.
            runCatching { sync.flush() }
        }
    }
}
