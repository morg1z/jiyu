package com.haise.jiyu.source

object LanguageMap {
    val displayNames = listOf(
        "Auto", "English", "Czech", "Indonesian", "Japanese", "Korean",
        "Chinese", "French", "Spanish", "Portuguese", "German", "Russian", "Polish",
    )

    fun toMangaDexCode(name: String): String = when (name) {
        "English" -> "en"
        "Czech" -> "cs"
        "Indonesian" -> "id"
        "Japanese" -> "ja"
        "Korean" -> "ko"
        "Chinese" -> "zh"
        "French" -> "fr"
        "Spanish" -> "es"
        "Portuguese" -> "pt-br"
        "German" -> "de"
        "Russian" -> "ru"
        "Polish" -> "pl"
        else -> "en"
    }
}
