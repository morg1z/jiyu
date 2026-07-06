package com.haise.jiyu.source.mangaplus

internal typealias ProtoMsg = Map<Int, List<Any>>

/**
 * Minimální protobuf parser pro MANGA Plus API. Bez externích závislostí.
 *
 * MANGA Plus u nedostupných kapitol (expirovaná free-window, geoblok) vrací
 * chybovou odpověď jako HTML/JSON misto protobuf - parsování takového vstupu
 * by s naivní aritmetikou délek snadno spočítalo index mimo pole a spadlo na
 * IndexOutOfBoundsException. Proto je tu jak ohraničení délek, tak try/catch
 * jako poslední pojistka - volající vždy dostane aspoň prázdnou mapu.
 */
internal fun ByteArray.parseProto(): ProtoMsg = try {
    val result = mutableMapOf<Int, MutableList<Any>>()
    var i = 0
    while (i < size) {
        val (tag, i1) = readVarint(i); i = i1
        val fieldNum = (tag ushr 3).toInt()
        val wireType = (tag and 7L).toInt()
        when (wireType) {
            0 -> { val (v, i2) = readVarint(i); i = i2; result.getOrPut(fieldNum) { mutableListOf() }.add(v) }
            1 -> { i += 8 }
            2 -> {
                val (len, i2) = readVarint(i); i = i2
                val end = (i + len.toInt()).coerceIn(i, size)
                result.getOrPut(fieldNum) { mutableListOf() }.add(copyOfRange(i, end))
                i = end
            }
            5 -> { i += 4 }
            else -> break
        }
    }
    result
} catch (_: Exception) {
    emptyMap()
}

private fun ByteArray.readVarint(start: Int): Pair<Long, Int> {
    var r = 0L; var shift = 0; var i = start
    while (i < size) {
        val b = this[i++].toInt() and 0xFF
        r = r or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    return r to i
}

internal fun ProtoMsg.str(field: Int): String? =
    (this[field]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8)

internal fun ProtoMsg.long(field: Int): Long? = this[field]?.firstOrNull() as? Long

internal fun ProtoMsg.msg(field: Int): ProtoMsg? =
    (this[field]?.firstOrNull() as? ByteArray)?.parseProto()

internal fun ProtoMsg.msgs(field: Int): List<ProtoMsg> =
    (this[field] ?: emptyList()).filterIsInstance<ByteArray>().map { it.parseProto() }
