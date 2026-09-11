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

    /** Opravy pro jednu stránku - volá se při každém načtení, takže je to index-covered dotaz. */
    @Query("SELECT * FROM manual_translation WHERE chapterId = :chapterId AND pageIndex = :pageIndex")
    suspend fun forPage(chapterId: String, pageIndex: Int): List<ManualTranslationEntity>

    /** Jeden záznam podle id - viz [com.haise.jiyu.translate.TranslateRepository.saveManualEdit] (zachování existujícího posunu při uložení jen textu). */
    @Query("SELECT * FROM manual_translation WHERE id = :id")
    suspend fun getById(id: String): ManualTranslationEntity?

    @Query("DELETE FROM manual_translation WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM manual_translation")
    suspend fun count(): Int
}
