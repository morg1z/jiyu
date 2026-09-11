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
    /**
     * Opt-in: [sourceTerm] se před odesláním k překladu nahradí neprůhledným tokenem a po
     * odpovědi vrátí zpátky přesně [targetTerm] (viz [com.haise.jiyu.translate.TranslateRepository]
     * a [com.haise.jiyu.translate.GlossaryPlaceholders]) - model tak nemá šanci pojem
     * ohnout/přeložit jinak. Výchozí `false` schválně (ne vždy zapnuté) - české skloňování
     * občas vyžaduje pojem ohnout podle pádu, což by zmrazený token znemožnil.
     */
    val protectExact: Boolean = false,
)
