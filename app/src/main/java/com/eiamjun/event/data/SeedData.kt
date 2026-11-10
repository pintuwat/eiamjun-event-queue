package com.eiamjun.event.data

/**
 * The event's menu, written into an empty database on first launch.
 *
 * ⚠️ **The prices here are placeholders.** The dishes and the options are what the owner described;
 * the numbers were not given and are a guess. Every one of them is editable in the app
 * (ตั้งค่า → แก้ไขเมนู) precisely so the real prices can be typed in on the morning rather than
 * waiting on a build — which is the same reason the shop's till stopped carrying prices in code.
 *
 * Seeded **only into a database that does not exist yet**, so a terminal that has been selling all
 * weekend never has its prices overwritten by an update.
 */
object SeedData {

    /** A group to ask about, before it has ids. */
    data class SeedGroup(
        val name: String,
        val multiSelect: Boolean,
        val options: List<SeedOption>
    )

    data class SeedOption(val name: String, val priceDelta: Int = 0, val isDefault: Boolean = false)

    data class SeedItem(val name: String, val price: Int, val groups: List<SeedGroup>)

    val items: List<SeedItem> = listOf(
        SeedItem(
            name = "ก๋วยเตี๋ยวคลุก",
            price = 50,
            groups = listOf(
                // One-of, and the first question, because the noodle is what the customer says
                // first — the same order of operations the shop's own order screen follows.
                SeedGroup(
                    name = "เส้น",
                    multiSelect = false,
                    options = listOf(
                        SeedOption("เส้นหมี่", isDefault = true),
                        SeedOption("เส้นเล็ก"),
                        SeedOption("มาม่า")
                    )
                ),
                SeedGroup(
                    name = "ความเผ็ด",
                    multiSelect = false,
                    options = listOf(
                        SeedOption("ไม่เผ็ด"),
                        SeedOption("เผ็ดน้อย", isDefault = true),
                        SeedOption("เผ็ดกลาง"),
                        SeedOption("เผ็ดมาก")
                    )
                ),
                // เลือกได้หลายอย่าง, and it has to be: a one-of group with a single option can
                // never be switched off, so an optional extra put in one would be compulsory.
                SeedGroup(
                    name = "เพิ่มเติม",
                    multiSelect = true,
                    options = listOf(
                        SeedOption("เพิ่มแคปป๊อป", priceDelta = 10),
                        SeedOption("พิเศษ", priceDelta = 10)
                    )
                ),
                SeedGroup(
                    name = "หมายเหตุ",
                    multiSelect = true,
                    options = listOf(
                        SeedOption("ไม่ใส่ถั่ว"),
                        SeedOption("ไม่ใส่ผัก"),
                        SeedOption("ไม่ใส่ผักชี"),
                        SeedOption("ไม่ผงชูรส")
                    )
                )
            )
        ),
        // Nothing to ask. A key with no groups goes straight into the basket on one tap, which is
        // the reason the order sheet is opened per dish rather than for every key.
        SeedItem(name = "แคปป๊อป", price = 20, groups = emptyList())
    )

    suspend fun seed(dao: MenuDao) {
        if (dao.count() > 0) return
        items.forEachIndexed { itemIndex, item ->
            val itemId = dao.insertItem(
                MenuItemEntity(name = item.name, price = item.price, sortOrder = itemIndex)
            )
            item.groups.forEachIndexed { groupIndex, group ->
                val groupId = dao.insertGroup(
                    OptionGroupEntity(
                        itemId = itemId,
                        name = group.name,
                        multiSelect = group.multiSelect,
                        sortOrder = groupIndex
                    )
                )
                group.options.forEachIndexed { optionIndex, option ->
                    dao.insertOption(
                        OptionEntity(
                            groupId = groupId,
                            name = option.name,
                            priceDelta = option.priceDelta,
                            sortOrder = optionIndex,
                            isDefault = option.isDefault
                        )
                    )
                }
            }
        }
    }
}
