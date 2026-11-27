package com.handykit.app.navigation

/** 二级页面路由。顶层四 tab 之外的功能都从首页进入。 */
enum class SubRoute(val key: String) {
    FUEL("fuel"),
    SCALE("scale"),
    NOTE("note"),
    BACKUP("backup"),
    REORDER("reorder"),
    ;

    companion object {
        fun fromKey(key: String?): SubRoute? = entries.firstOrNull { it.key == key }
    }
}
