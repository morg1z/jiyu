package com.haise.jiyu.translate

// Dělení textu na dávky pro překlad - vytaženo z TranslateRepository (bez změny chování).

/**
 * Rozdělí stránky (v pořadí) do dávek, kde součet délky bublinových textů v jedné dávce
 * nepřekročí [TranslateRepository.CHAPTER_CHUNK_CHAR_LIMIT] a součet bublin nepřekročí
 * [TranslateRepository.CHAPTER_CHUNK_BUBBLE_LIMIT] - jedna stránka je vždy atomická (nikdy se
 * nerozdělí mezi dvě dávky), stejný princip jako [chunkParagraphs] u novel překladu.
 *
 * Proč i limit na počet: výstup modelu má pevný max_tokens a každá bublina stojí v JSON
 * odpovědi ~60-130 tokenů obálky (id+original+translated+size_tag+is_sfx+syllable_breaks
 * +notes) bez ohledu na to, jak krátký její text je. Stránka plná dvoupísmenných SFX
 * ("HUF") má pod 200 znaků, ale třeba 40 bublin -> ~4000 výstupních tokenů a odpověď se
 * usekne v půlce (audit Vagabond ch.1: 37 bublin zachráněno z prefixu, ocásek dávky šel
 * celý zbytečně do repair retry).
 */
internal fun chunkPages(pageIndices: List<Int>, bubblesByPage: Map<Int, List<ClassifiedBubble>>): List<List<Int>> {
    val chunks = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var currentLen = 0
    var currentBubbles = 0
    for (pageIndex in pageIndices) {
        val pageBubbles = bubblesByPage.getValue(pageIndex)
        val len = pageBubbles.sumOf { it.raw.text.length }
        if (current.isNotEmpty() &&
            (currentLen + len > TranslateRepository.CHAPTER_CHUNK_CHAR_LIMIT ||
                currentBubbles + pageBubbles.size > TranslateRepository.CHAPTER_CHUNK_BUBBLE_LIMIT)
        ) {
            chunks += current
            current = mutableListOf()
            currentLen = 0
            currentBubbles = 0
        }
        current += pageIndex
        currentLen += len
        currentBubbles += pageBubbles.size
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}

/**
 * Jeden "kousek" poslaný k překladu jako samostatná položka dávky. Normální (krátký)
 * odstavec je jeden unit s [isContinuation]=false. Odstavec delší než
 * [NOVEL_CHUNK_CHAR_LIMIT] se rozseká na věty ([splitAtSentenceBoundaries]) do víc units -
 * první má isContinuation=false (začíná nový odstavec), zbytek true (patří k tomu samému
 * odstavci, při skládání výsledku zpátky se spojí mezerou, ne novým řádkem).
 */
internal data class TranslationUnit(val text: String, val isContinuation: Boolean)

internal fun toTranslationUnits(paragraphs: List<String>): List<TranslationUnit> {
    val units = mutableListOf<TranslationUnit>()
    for (p in paragraphs) {
        if (p.length <= TranslateRepository.NOVEL_CHUNK_CHAR_LIMIT) {
            units += TranslationUnit(p, isContinuation = false)
        } else {
            splitAtSentenceBoundaries(p, TranslateRepository.NOVEL_CHUNK_CHAR_LIMIT).forEachIndexed { i, piece ->
                units += TranslationUnit(piece, isContinuation = i > 0)
            }
        }
    }
    return units
}

/**
 * Rozdělí text na konce vět (. ! ?) a hladově balí do kusů pod [limit] - NIKDY neuseknuté
 * uprostřed věty. Když ani jedna věta sama o sobě nevejde do limitu (extrémně dlouhá věta
 * bez interpunkce), vrátí ji jako jeden předimenzovaný kus - radši jedno moc velké API
 * volání než rozseknutá věta v půlce.
 */
internal fun splitAtSentenceBoundaries(text: String, limit: Int): List<String> {
    val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
    if (sentences.size <= 1) return listOf(text)

    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    for (s in sentences) {
        if (current.isNotEmpty() && current.length + s.length + 1 > limit) {
            pieces += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(" ")
        current.append(s)
    }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
}

internal fun chunkUnits(units: List<TranslationUnit>): List<List<TranslationUnit>> {
    val chunks = mutableListOf<List<TranslationUnit>>()
    var current = mutableListOf<TranslationUnit>()
    var currentLen = 0
    for (u in units) {
        if (current.isNotEmpty() && currentLen + u.text.length > TranslateRepository.NOVEL_CHUNK_CHAR_LIMIT) {
            chunks += current
            current = mutableListOf()
            currentLen = 0
        }
        current += u
        currentLen += u.text.length
    }
    if (current.isNotEmpty()) chunks += current
    return chunks
}
