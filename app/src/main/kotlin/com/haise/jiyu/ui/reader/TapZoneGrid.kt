package com.haise.jiyu.ui.reader

enum class TapZoneAction {
    NONE, SHOW_PANEL, PREV_PAGE, NEXT_PAGE, PREV_CHAPTER, NEXT_CHAPTER
}

// 9 akcí v row-major pořadí: top-left, top-center, top-right, mid-left, mid-center, mid-right, ...
data class TapZoneGrid(val grid: List<TapZoneAction>) {
    constructor() : this(DEFAULT_GRID)

    operator fun get(row: Int, col: Int): TapZoneAction =
        grid.getOrElse(row * 3 + col) { TapZoneAction.NONE }

    fun withAction(row: Int, col: Int, action: TapZoneAction): TapZoneGrid =
        TapZoneGrid(grid.toMutableList().also { it[row * 3 + col] = action })

    fun serialize(): String = grid.joinToString(",") { it.name }

    companion object {
        val DEFAULT_GRID: List<TapZoneAction> = listOf(
            TapZoneAction.NONE,        TapZoneAction.SHOW_PANEL,  TapZoneAction.NONE,
            TapZoneAction.PREV_PAGE,   TapZoneAction.SHOW_PANEL,  TapZoneAction.NEXT_PAGE,
            TapZoneAction.NONE,        TapZoneAction.NONE,         TapZoneAction.NONE,
        )

        fun deserialize(s: String): TapZoneGrid {
            if (s.isBlank()) return TapZoneGrid()
            return try {
                val actions = s.split(",").mapNotNull {
                    runCatching { TapZoneAction.valueOf(it.trim()) }.getOrNull()
                }
                if (actions.size == 9) TapZoneGrid(actions) else TapZoneGrid()
            } catch (_: Exception) { TapZoneGrid() }
        }
    }
}
