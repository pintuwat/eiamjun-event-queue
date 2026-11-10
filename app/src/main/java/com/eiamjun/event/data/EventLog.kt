package com.eiamjun.event.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this terminal has been doing, readable on the device in ตั้งค่า → ตรวจสอบระบบ.
 *
 * ⚠️ **In memory, and gone on the next launch.** The shop's till has exactly this and it cost a
 * day: a fortnight of "it failed sometimes" left not one row to read, and the cause had to be
 * found by enumerating the rule instead of by looking. The honest fix is a table; this is here
 * because a print or a push that fails during a service needs *something* on the screen in front
 * of the staff, and that is a different job from keeping a record.
 */
@Singleton
class EventLog @Inject constructor() {

    data class Entry(val at: Long, val text: String, val bad: Boolean)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun info(text: String) = add(text, bad = false)
    fun error(text: String) = add(text, bad = true)

    private fun add(text: String, bad: Boolean) {
        _entries.value = (listOf(Entry(System.currentTimeMillis(), text, bad)) + _entries.value)
            .take(MAX)
    }

    private companion object {
        const val MAX = 200
    }
}
