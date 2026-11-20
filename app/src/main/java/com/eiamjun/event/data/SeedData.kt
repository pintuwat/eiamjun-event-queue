package com.eiamjun.event.data

/**
 * The event's menu, written into an empty database on first launch.
 *
 * **This is the stall's own menu, read back off the terminal** — not the guess it started as. The
 * dishes, the options and the prices were typed into ตั้งค่า → แก้ไขเมนู over the first two
 * events, and on 19/11/2569 the owner's instruction was to take what is on the device as the
 * truth and bring the code to it. So a fresh install, or a second terminal, now starts where the
 * first one actually is rather than where someone once guessed it would be.
 *
 * It is still editable in the app, which is the point: the next change belongs in ตั้งค่า on the
 * morning of an event, not in a build. Copying it back here is a thing to do afterwards, when
 * something has settled.
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

    data class SeedItem(
        val name: String,
        val price: Int,
        val groups: List<SeedGroup>,
        /** "ซื้อ N ได้ราคาชุด", or zeroes for the usual case of no deal. */
        val promoQty: Int = 0,
        val promoPrice: Int = 0
    )

    val items: List<SeedItem> = listOf(
        // Two rows, not one dish with a เนื้อ/หมู question behind it. The pad is the whole
        // ordering interface and a key is one tap; the meat is also the first thing the
        // kitchen reads, and a dish name carries it in the large type without the cook
        // going down to the options line.
        SeedItem(
            name = "ก๋วยเตี๋ยวคลุกหมู",
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
                // เลือกได้หลายอย่าง, and it has to be: a one-of group with a single option can
                // never be switched off, so an optional extra put in one would be compulsory.
                SeedGroup(
                    name = "เพิ่มเติม",
                    multiSelect = true,
                    options = listOf(
                        SeedOption("แคปป๊อปถัง", priceDelta = 39)
                    )
                ),
                SeedGroup(
                    name = "หมายเหตุ",
                    multiSelect = true,
                    options = listOf(
                        SeedOption("ไม่ใส่ผัก"),
                        SeedOption("ไม่ใส่ผักชี"),
                        SeedOption("ลูกชิ้นล้วน")
                    )
                )
            )
        ),
        SeedItem(
            name = "ก๋วยเตี๋ยวคลุกเนื้อ",
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
                // เลือกได้หลายอย่าง, and it has to be: a one-of group with a single option can
                // never be switched off, so an optional extra put in one would be compulsory.
                SeedGroup(
                    name = "เพิ่มเติม",
                    multiSelect = true,
                    options = listOf(
                        SeedOption("แคปป๊อปถัง", priceDelta = 39)
                    )
                ),
                SeedGroup(
                    name = "หมายเหตุ",
                    multiSelect = true,
                    options = listOf(
                        SeedOption("ไม่ใส่ผัก"),
                        SeedOption("ไม่ใส่ผักชี"),
                        SeedOption("ลูกชิ้นล้วน")
                    )
                )
            )
        ),
        // Two keys rather than one key with a ขนาด question behind it. Nothing to ask means one
        // tap, which is the reason the order sheet is opened per dish rather than for every key —
        // and the size then reaches the ticket and the kitchen board as part of the dish's name,
        // in the large type, instead of on the small options line below it.
        SeedItem(name = "แคปป๊อป เล็ก", price = 39, groups = emptyList()),
        SeedItem(name = "แคปป๊อป ใหญ่", price = 59, groups = emptyList()),
        // Nothing to ask, so one tap — and the deal is applied by the pad rather than by a second
        // key for "3 ขวด". A key per quantity is a key the staff can forget to use, and a customer
        // who buys three and is charged 117 is the failure worth designing out.
        SeedItem(
            name = "น้ำซอสก๋วยเตี๋ยว",
            price = 39,
            groups = emptyList(),
            promoQty = 3,
            promoPrice = 100
        )
    )

    suspend fun seed(dao: MenuDao) {
        if (dao.count() > 0) return
        items.forEachIndexed { itemIndex, item ->
            val itemId = dao.insertItem(
                MenuItemEntity(
                    name = item.name,
                    price = item.price,
                    sortOrder = itemIndex,
                    promoQty = item.promoQty,
                    promoPrice = item.promoPrice
                )
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
