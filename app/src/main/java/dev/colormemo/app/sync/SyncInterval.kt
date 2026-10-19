package dev.colormemo.app.sync

/** WorkManager supports periodic work from 15 minutes; settings use minutes on disk. */
internal object SyncInterval {
    const val DEFAULT_MINUTES = 15L
    const val MAX_MINUTES = 1440L
    val choices = listOf(15L, 30L, 60L, 180L, 360L, 720L, 1440L)

    fun normalize(minutes: Long): Long = minutes.coerceIn(DEFAULT_MINUTES, MAX_MINUTES)
    fun label(minutes: Long): String = if (minutes % 60L == 0L) "${minutes / 60L} 小时" else "$minutes 分钟"
}
