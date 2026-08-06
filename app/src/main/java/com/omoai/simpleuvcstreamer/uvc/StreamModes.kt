package com.omoai.simpleuvcstreamer.uvc

/**
 * One MJPEG resolution and the frame rates advertised for it.
 *
 * Wire format from native:
 * `WxH|fps1,fps2|defaultFps|isDeviceDefault;...`
 */
data class StreamMode(
    val width: Int,
    val height: Int,
    val fpsList: List<Int>,
    val defaultFps: Int,
    val isDeviceDefault: Boolean,
) {
    val sizeLabel: String get() = "${width}x${height}"

    fun fpsLabels(): List<String> = fpsList.map { "$it fps" }

    fun nearestFps(prefer: Int): Int {
        if (fpsList.isEmpty()) return prefer
        return fpsList.minBy { kotlin.math.abs(it - prefer) }
    }

    companion object {
        fun parse(raw: String): List<StreamMode> {
            if (raw.isBlank()) return emptyList()
            return raw.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { parseOne(it) }
                .sortedWith(
                    compareByDescending<StreamMode> { it.width * it.height }
                        .thenByDescending { it.width }
                )
        }

        private fun parseOne(line: String): StreamMode? {
            val parts = line.split('|')
            if (parts.size < 4) return null
            val size = parts[0].split('x')
            if (size.size != 2) return null
            val w = size[0].toIntOrNull() ?: return null
            val h = size[1].toIntOrNull() ?: return null
            val fpsList = parts[1].split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
            if (fpsList.isEmpty()) return null
            val defaultFps = parts[2].toIntOrNull()?.takeIf { it > 0 }
                ?: fpsList.first()
            val isDefault = parts[3].trim() == "1"
            return StreamMode(
                width = w,
                height = h,
                fpsList = fpsList,
                defaultFps = if (fpsList.contains(defaultFps)) defaultFps else fpsList.first(),
                isDeviceDefault = isDefault,
            )
        }

        fun preferredIndex(modes: List<StreamMode>): Int {
            val deviceIdx = modes.indexOfFirst { it.isDeviceDefault }
            if (deviceIdx >= 0) return deviceIdx
            val preferred = listOf(
                "1280x720", "960x540", "800x600", "640x480", "640x360", "320x240"
            )
            for (p in preferred) {
                val idx = modes.indexOfFirst { it.sizeLabel == p }
                if (idx >= 0) return idx
            }
            return modes.lastIndex.coerceAtLeast(0)
        }

        fun deviceDefault(modes: List<StreamMode>): StreamMode? {
            if (modes.isEmpty()) return null
            return modes[preferredIndex(modes)]
        }

        fun indexOfSize(modes: List<StreamMode>, width: Int, height: Int): Int {
            return modes.indexOfFirst { it.width == width && it.height == height }
        }

        fun parseFpsLabel(label: String): Int? {
            return label.trim().removeSuffix("fps").trim().toIntOrNull()
        }
    }
}
