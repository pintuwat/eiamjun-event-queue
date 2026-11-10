package com.eiamjun.event.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eiamjun.event.data.MenuDao
import com.eiamjun.event.data.MenuItemEntity
import com.eiamjun.event.data.MenuItemWithOptions
import com.eiamjun.event.data.OptionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Editing the menu on the device.
 *
 * What it can change: a dish's name and price, whether it is on sale, and each option's name and
 * price delta — plus adding and deleting options within a group. What it cannot: create or delete
 * a **group**. Groups are the questions the stall asks; they were decided when the menu was
 * written, and the morning of an event is the wrong moment to invent a new one. (The shop's own
 * till does have group creation, and it took a release of its own to get right.)
 *
 * ⚠️ Editing a price never rewrites an order already taken — a line copies the name and the price
 * at the moment it is ordered. That is deliberate, and it is also what makes ลบ safe here.
 */
@HiltViewModel
class MenuEditViewModel @Inject constructor(private val dao: MenuDao) : ViewModel() {

    val menu: StateFlow<List<MenuItemWithOptions>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rename(item: MenuItemEntity, name: String) {
        viewModelScope.launch { dao.updateItem(item.copy(name = name)) }
    }

    fun reprice(item: MenuItemEntity, price: Int) {
        viewModelScope.launch { dao.updateItem(item.copy(price = price.coerceAtLeast(0))) }
    }

    /** Off the pad without being deleted — "ของหมด" mid-service, back with one tap. */
    fun setActive(item: MenuItemEntity, active: Boolean) {
        viewModelScope.launch { dao.setActive(item.id, active) }
    }

    fun renameOption(option: OptionEntity, name: String) {
        viewModelScope.launch { dao.updateOption(option.copy(name = name)) }
    }

    /** A delta may be negative — that is how a smaller portion takes money off. */
    fun setOptionDelta(option: OptionEntity, delta: Int) {
        viewModelScope.launch { dao.updateOption(option.copy(priceDelta = delta)) }
    }

    fun addOption(groupId: Long, name: String, delta: Int, after: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            dao.insertOption(
                OptionEntity(
                    groupId = groupId,
                    name = name.trim(),
                    priceDelta = delta,
                    sortOrder = after + 1
                )
            )
        }
    }

    fun deleteOption(option: OptionEntity) {
        viewModelScope.launch { dao.deleteOption(option) }
    }
}
