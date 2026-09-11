package com.haise.jiyu.translate

import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.entity.GlossaryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tenký wrapper nad [GlossaryDao] - drží pojmy (jména, techniky, přezdívky) svázané na
 * konkrétní mangu a cílový jazyk, aby je [GeminiUltraPrompt] mohl vynutit jako závazné
 * napříč kapitolami (viz Problem F - "Gravity Magic" překládané pokaždé jinak).
 */
@Singleton
class GlossaryRepository @Inject constructor(
    private val dao: GlossaryDao,
) {
    fun observeForManga(mangaId: String): Flow<List<GlossaryEntity>> = dao.observeForManga(mangaId)

    suspend fun getMap(mangaId: String, targetLanguage: String): Map<String, String> =
        dao.getForMangaAndLanguage(mangaId, targetLanguage).associate { it.sourceTerm to it.targetTerm }

    /**
     * Plné entity (ne sbalená mapa jako [getMap]) - potřeba pro [GlossaryPlaceholders], které
     * na rozdíl od promptové injekce potřebuje vědět, KTERÉ pojmy mají [GlossaryEntity.protectExact].
     */
    suspend fun getProtectedEntries(mangaId: String, targetLanguage: String): List<GlossaryEntity> =
        dao.getForMangaAndLanguage(mangaId, targetLanguage).filter { it.protectExact }

    /**
     * id je deterministický (ne náhodný) z mangaId+sourceTerm+targetLanguage - stejná
     * konvence jako [com.haise.jiyu.ui.detail.MangaDetailViewModel.addGlossaryEntry], aby
     * upsert stejného pojmu z obou míst přepsal tentýž řádek místo vytvoření duplicity.
     */
    suspend fun upsert(mangaId: String, sourceTerm: String, targetTerm: String, targetLanguage: String, protectExact: Boolean = false) {
        val source = sourceTerm.trim()
        if (source.isBlank() || targetTerm.isBlank()) return
        dao.upsert(
            GlossaryEntity(
                id = "$mangaId::${source.lowercase()}::$targetLanguage",
                mangaId = mangaId,
                sourceTerm = source,
                targetTerm = targetTerm.trim(),
                targetLanguage = targetLanguage,
                protectExact = protectExact,
            ),
        )
    }

    suspend fun delete(entry: GlossaryEntity) = dao.delete(entry)
}
