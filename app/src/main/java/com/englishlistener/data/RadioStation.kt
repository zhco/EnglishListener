package com.englishlistener.data

/**
 * 电台频道数据模型
 */
data class RadioStation(
    val id: String,
    val name: String,
    val accent: Accent,
    val streamUrl: String,
    val description: String,
    val category: Category
)

enum class Accent(val label: String) {
    BRITISH("英音"),
    AMERICAN("美音"),
    CHINESE("中式英语"),
    AUSTRALIAN("澳音"),
    OTHER("其他")
}

enum class Category(val label: String) {
    NEWS("新闻"),
    CULTURE("文化"),
    COMPREHENSIVE("综合")
}
