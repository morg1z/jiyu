package com.haise.jiyu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.haise.jiyu.data.db.entity.ManualTranslationEntity

@Dao
interface ManualTranslationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ManualTranslationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ManualTranslationEntity>)

    @Query("SELECT * FROM manual_translation")
    suspend fun getAll(): List<ManualTranslationEntity>

    /** Opravy pro jednu stránku - volá se při každém načtení, takže je to index-covered dotaz. */
    @Query("SELECT * FROM manual_translation WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun forPage(chapterId: String, pageIndex: Int): List<ManualTranslationEntity>

    /** Jeden záznam podle id - viz [com.haise.jiyu.translate.TranslateRepository.saveManualEdit] (zachování existujícího posunu při uložení jen textu). */
    @Query("SELECT * FROM manual_translation WHERE id = :id")
    suspend fun getById(id: String): ManualTranslationEntity?

    @Query("DELETE FROM manual_translation WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Přemapuje ruční opravy na nové `chapterId` po [ChapterDao.relink] (kapitola dostala nové
     * `id`, viz [com.haise.jiyu.data.repository.MangaRepository.recoverMangaLink]) - bez tohohle
     * `chapterId` sloupec ukazuje na neexistující kapitolu a `forPage()` opravy už nikdy nenajde.
     * `id` má tvar `"$chapterId::$pageIndex::$originalText"` (viz [ManualTranslationEntity.id]) -
     * `oldChapterId` je vždy jeho přesná předpona, takže `substr` odřízne jen ji a `newChapterId`
     * se přilepí místo ní, aby `id` zůstalo ve stejném formátu i po relinku.
     */
    @Query("""
        UPDATE manual_translation
        SET id = :newChapterId || substr(id, length(:oldChapterId) + 1),
            chapterId = :newChapterId
        WHERE chapterId = :oldChapterId
    """)
    suspend fun relinkChapter(oldChapterId: String, newChapterId: String)

    @Query("SELECT COUNT(*) FROM manual_translation")
    suspend fun count(): Int
}
