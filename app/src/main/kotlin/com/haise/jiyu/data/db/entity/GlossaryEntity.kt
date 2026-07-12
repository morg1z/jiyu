package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Slovník pojmů (jména postav, techniky, přezdívky...) pro konkrétní mangu -
 * posílá se AI překladači jako závazná instrukce, aby přeložil stejný pojem
 * stejně napříč všemi kapitolami, místo aby si to model "vymýšlel" pokaždé jinak.
 */
@Entity(
    tableName = "glossary_entry",
    indices = [Index(value = ["mangaId", "targetLanguage"])],
)
data class GlossaryEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val sourceTerm: String,
    val targetTerm: String,
    val targetLanguage: String,
)
