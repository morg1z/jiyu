package com.haise.jiyu.source.comix

/**
 * Čistá (bez Android závislosti) logika descramblingu obrázků comix.to - port
 * Descrambler.kt z keiyoushi/extensions-source. CDN chrání stránky kapitol dvěma
 * nezávisle skladatelnými vrstvami, obě se poznají z RESPONSE hlaviček:
 *
 *  - `x-enc-seed` / `x-enc-len` / `x-enc-algo`: byte-level XOR stream nad prvními
 *    `x-enc-len` bajty. algo "2" je dvojznačné (zkusí se víc variant PRNG a vybere
 *    se ta, jejíž výsledek začíná signaturou obrázku), ostatní = LCG.
 *  - `x-scramble-grid`="5x5" + `x-scramble-seed` / `-algo` / `-hash`: obrázek je
 *    rozřezaný na 5×5 dlaždice a přeházený seedovanou permutací. [gridOrder]
 *    vrací mapování `order[dst] = src` (na kterou pozici v zamíchaném obrázku
 *    leží dlaždice patřící na dst).
 */
object ComixScramble {

    const val GRID = 5
    const val NUM_TILES = GRID * GRID

    /** Fragment, kterým [ComixSource] označí v3 tile-scrambled stránky (server pak hlásí
     * `x-scramble-*` jen když request má `v3` query flag a NEMÁ `Origin`). */
    const val SCRAMBLED_FRAGMENT = "scrambled"

    /** Fragment pro legacy byte-XOR stránky (server vrací `x-enc-*` hlavičky jen když
     * request nese `Origin` - přesný opak v3). Fragmenty se po síti neposílají a zároveň
     * drží chráněnou stránku oddělenou od nechráněné v cache. */
    const val LEGACY_FRAGMENT = "enc-scrambled"

    /** CDN drží stejný obrázek pod několika zaměnitelnými segmenty cesty a ten z page
     * listu nemusí existovat - na 404 se zkouší postupně všechny varianty. */
    val PATH_FALLBACK_REGEX = Regex("/(?:i5|s?i+)/")
    val PATH_FALLBACKS = listOf("/i5/", "/si/", "/i/", "/sii/", "/ii/")

    private const val ENC_MULTIPLIER = 1000005
    private const val ENC_INCREMENT = 1234567891
    private const val LCG_MULTIPLIER = 1664525
    private const val LCG_INCREMENT = 1013904223

    /** Co s tělem odpovědi udělat; obě vrstvy se můžou skládat (nejprve XOR, pak dlaždice). */
    class Plan(
        val encSeed: Int,   // 0 = bez XOR vrstvy
        val encLen: Int,
        val encAlgo: String?,
        val gridSeed: Int,  // 0 = bez dlaždicové vrstvy
        val gridAlgo: String?,
    ) {
        val hasXor get() = encSeed != 0
        val hasGrid get() = gridSeed != 0
    }

    /** Přečte `x-enc-*`/`x-scramble-*` hlavičky; `null` = obrázek není chráněný. */
    fun planFromHeaders(header: (String) -> String?): Plan? {
        val encSeed = header("x-enc-seed")?.toLongOrNull()?.toInt()
        val encLen = header("x-enc-len")?.toIntOrNull()
        val encAlgo = header("x-enc-algo")

        val grid = header("x-scramble-grid")
        val gridAlgo = header("x-scramble-algo")
        val gridSeedRaw = header("x-scramble-seed")?.toLongOrNull()?.toInt()
        val gridHash = decodeScrambleHash(header("x-scramble-hash"))

        val xor = encSeed != null && encSeed != 0 && encLen != null
        val tiles = grid == "${GRID}x$GRID" &&
            (gridAlgo == null || gridAlgo == "1" || gridAlgo == "2" || gridAlgo == "3") &&
            gridSeedRaw != null && gridSeedRaw != 0

        if (!xor && !tiles) return null
        return Plan(
            encSeed = if (xor) encSeed!! else 0,
            encLen = encLen ?: 0,
            encAlgo = encAlgo,
            gridSeed = if (tiles) (gridSeedRaw!! xor gridHash) else 0,
            gridAlgo = gridAlgo,
        )
    }

    /** Starší obrázky používají konstantní hash, který se xoruje do seedu. */
    private fun decodeScrambleHash(hash: String?): Int = when (hash?.trim()) {
        "03632" -> 58414
        "02900" -> 117532
        else -> 0
    }

    /** Odstraní XOR stream z prvních [length] bajtů. algo "2" zkusí víc PRNG variant. */
    fun decodeXor(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo != "2") return decodeWithLcg(bytes, seed, length)
        val candidates = listOf(
            decodeWithXorshift(bytes, seed or 1, length, highByte = false),
            decodeWithXorshift(bytes, seed, length, highByte = false),
            decodeWithXorshift(bytes, seed or 1, length, highByte = true),
            decodeWithLcg(bytes, seed, length),
        )
        return candidates.firstOrNull { it.hasImageSignature() } ?: candidates.first()
    }

    private fun decodeWithLcg(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state * ENC_MULTIPLIER + ENC_INCREMENT
            result[i] = (result[i].toInt() xor (state ushr 24)).toByte()
        }
        return result
    }

    private fun decodeWithXorshift(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = initialState
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = xorshift32(state)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[i] = (result[i].toInt() xor key).toByte()
        }
        return result
    }

    private fun xorshift32(state: Int): Int {
        var s = state
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        return s xor (s shl 5)
    }

    /**
     * Permutace dlaždic: `order[srcIdx]` = pozice, na kterou dlaždice `srcIdx` ze
     * STAŽENÉHO (zamíchaného) obrázku patří v dešifrovaném. algo "3" = xorshift32
     * (seed or 1), ostatní = LCG Fisher-Yates.
     */
    fun gridOrder(seed: Int, algo: String?): IntArray {
        val arr = IntArray(NUM_TILES) { it }
        var state = if (algo == "3") seed or 1 else seed
        for (i in NUM_TILES - 1 downTo 1) {
            state = if (algo == "3") xorshift32(state) else state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = ((state.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr
    }

    /** RIFF/WEBP, JPEG, PNG signatura - pozná úspěšně dekódovaný obrázek. */
    fun ByteArray.hasImageSignature(): Boolean = size >= 12 && (
        (this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
            this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
            this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
            this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()) ||
            (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) ||
            (this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() &&
                this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte())
        )
}
