package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ruční oprava přeloženého textu jedné bubliny.
 *
 * ## Proč to má vlastní tabulku a nesedí to rovnou v cache překladu
 * Přeložené stránky se ukládají do `translated_page` pod klíčem, který obsahuje
 * `PIPELINE_VERSION` (viz TranslateRepository) - jakmile se pipeline změní, celý záznam se
 * zahodí a stránka se přeloží znovu. To je správně pro strojový překlad, ale ruční oprava je
 * jediná věc na té stránce, kterou stroj nevyrobí a nedokáže obnovit. Kdyby žila v cache,
 * zmizela by při každém zvednutí verze - jen během jednoho dne se zvedala třikrát.
 *
 * ## Podle čeho se bublina pozná po přepočtu
 * Ne podle pořadí - OCR může příště najít jiný počet bloků a indexy se posunou. Identitou je
 * PŮVODNÍ TEXT bubliny (viz [originalText]): ten vzniká rozpoznáním pořád stejného obrázku,
 * takže je napříč přepočty nejstabilnější, co je k dispozici. Když se i ten změní (jiný OCR
 * model, jinak oříznutá stránka), oprava se prostě nenaparuje a zůstane ležet - degraduje to
 * tiše a bezpečně, nikdy to nepřepíše cizí bublinu.
 */
@Entity(
    tableName = "manual_translation",
    indices = [Index(value = ["chapterId", "pageIndex"])],
)
data class ManualTranslationEntity(
    /** `chapterId::pageIndex::originalText` - viz [com.haise.jiyu.translate.manualEditId]. */
    @PrimaryKey val id: String,
    val chapterId: String,
    val pageIndex: Int,
    /** Původní (nepřeložený) text bubliny - identita napříč přepočty, viz komentář u třídy. */
    val originalText: String,
    /** Co tam má být místo strojového překladu. */
    val text: String,
    val updatedAt: Long,
    /**
     * Ruční posun vykresleného boxu bubliny (viz [com.haise.jiyu.ui.reader.TranslationOverlay])
     * v Dp od jeho vypočtené pozice - null = beze změny (nejčastější případ, drtivá většina
     * oprav mění jen text). Nezávislé na [text]: uživatel může posunout bublinu, aniž by měnil
     * překlad, proto se při ukládání jen textu existující posun zachovává (viz
     * [com.haise.jiyu.translate.TranslateRepository.saveManualEdit]), ne přepisuje na null.
     */
    val offsetXDp: Float? = null,
    val offsetYDp: Float? = null,
)
