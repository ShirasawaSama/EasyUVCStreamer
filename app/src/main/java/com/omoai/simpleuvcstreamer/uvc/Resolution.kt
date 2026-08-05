package com.omoai.simpleuvcstreamer.uvc

object Resolution {
    fun parse(resStr: String): List<String> {
        return resStr.split(";")
            .map { it.trim() }
            .filter { it.contains("x") }
            .distinct()
            .sortedWith(
                compareByDescending<String> {
                    val parts = it.split("x")
                    parts[0].trim().toInt() * parts[1].trim().toInt()
                }.thenByDescending {
                    it.split("x")[0].trim().toInt()
                }
            )
    }

    fun preferredIndex(resList: List<String>): Int {
        val preferred = listOf(
            "1280x720", "960x540", "800x600", "640x480", "640x360", "320x240"
        )
        for (p in preferred) {
            val idx = resList.indexOf(p)
            if (idx >= 0) return idx
        }
        return resList.lastIndex.coerceAtLeast(0)
    }

    fun parseSize(label: String): Pair<Int, Int>? {
        val parts = label.split("x")
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return w to h
    }
}
