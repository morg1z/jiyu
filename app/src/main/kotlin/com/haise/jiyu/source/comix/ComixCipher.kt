package com.haise.jiyu.source.comix

import java.util.Base64

/**
 * Materiál podepisovací/šifrovací schránky comix.to, zachycený z běhu stránky ve
 * WebView (hook na `window.atob` - viz [ComixScripts.BOOTSTRAP]): tři RC4 s-boxy
 * (256 bajtů) a tři klíče, které stránka skládá z base64 stringů při inicializaci
 * svého crypto modulu. Hodnoty rotují s každým deployi webu, proto se nehardkódují,
 * ale zachytávají čerstvé při každém WebView běhu.
 */
class ComixCipherMaterial(
    val sboxes: List<List<Int>>,
    val keys: List<List<Int>>,
) {
    fun isValid(): Boolean = sboxes.size == 3 &&
        sboxes.all { it.size == 256 } &&
        keys.size == 3 &&
        keys.all { it.isNotEmpty() }
}

/**
 * Nativní reimplementace podpisu a dešifrování API comix.to (přesný port schématu
 * z keiyoushi/extensions-source Comix Cipher.kt). Podepsaný dotaz dostane parametr
 * `_` = sign(path, query); odpověď může přijít zašifrovaná jako `{"e": "..."}`.
 *
 * Substituce je CBC-like: `out[i] = sbox[in[i] xor key[i] xor prev]`, kde `prev` je
 * výstupní (už zsubstituovaný) bajt z předchozí pozice. Inverze tedy musí číst
 * `prev` ze ŠIFROVANÉHO textu.
 */
class ComixCipher(material: ComixCipherMaterial) {

    private val sboxes = material.sboxes.map { it.toIntArray() }
    private val keys = material.keys.map { it.toIntArray() }

    init {
        require(material.isValid()) { "Invalid Comix cipher material" }
    }

    /** Token pro query parametr `_`. [path] je API cesta (`/api/v1/...`), [query] kanonizovaná query. */
    fun sign(path: String, query: String): String {
        var data = buildString {
            append(path.removePrefix("/api/v1"))
            if (query.isNotEmpty()) append("?$query")
        }.toByteArray(Charsets.UTF_8)
        repeat(3) { round ->
            data = substitute(data, sboxes[round], keys[round], PREVIOUS[round])
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    /** Dešifruje obsah pole `e` ze šifrované obálky odpovědi. */
    fun decrypt(value: String): String {
        var data = Base64.getUrlDecoder().decode(value)
        for (round in 2 downTo 0) {
            data = substituteInverse(data, sboxes[round], keys[round], PREVIOUS[round])
        }
        return String(data, Charsets.UTF_8)
    }

    private fun substitute(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val output = ByteArray(data.size)
        var prev = previous
        for (index in data.indices) {
            val substituted = sbox[(data[index].toInt() and 0xff) xor key[index % key.size] xor prev]
            output[index] = substituted.toByte()
            prev = substituted
        }
        return output
    }

    private fun substituteInverse(data: ByteArray, sbox: IntArray, key: IntArray, previous: Int): ByteArray {
        val inverse = IntArray(256)
        for (i in sbox.indices) inverse[sbox[i]] = i

        val output = ByteArray(data.size)
        var prev = previous
        for (index in data.indices) {
            val value = data[index].toInt() and 0xff
            output[index] = (inverse[value] xor key[index % key.size] xor prev).toByte()
            prev = value
        }
        return output
    }

    private companion object {
        // Pocatecni "prev" hodnota pro kazde ze 3 kol - konstanta webu.
        private val PREVIOUS = intArrayOf(189, 133, 32)
    }
}
