package us.tractat.bee.web

/**
 * A QR code (ISO/IEC 18004) for a short text: byte mode, error correction level M, versions 1 to
 * [MAX_VERSION], which holds 213 bytes, plenty for a join link. Small enough to read whole, so the
 * page needs no script from elsewhere: a plain-HTTP LAN page has no CDN to call.
 *
 * After Project Nayuki's QR Code generator (MIT License, https://www.nayuki.io/page/qr-code-generator-library),
 * trimmed to the one mode and level bee uses.
 *
 * [size] modules a side, [dark] one per module, row by row. The four-module quiet zone around the
 * symbol is the renderer's to draw.
 */
internal class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    /** Whether the module in [row], [column] is dark. */
    fun dark(row: Int, column: Int): Boolean = modules[row][column]

    companion object {
        const val MAX_VERSION = 10

        /** [text] as UTF-8, under whichever mask scores lowest by the standard's penalty rules. */
        fun of(text: String): QrCode {
            val data = text.encodeToByteArray()
            return (0 until MASKS).map { encode(data, it) }.minBy { it.penalty() }
        }

        /** [text] under [mask], 0 to 7: what a test compares with a reference encoder's output. */
        internal fun withMask(text: String, mask: Int): QrCode {
            require(mask in 0 until MASKS) { "No mask $mask: a QR code has masks 0 to 7." }
            return encode(text.encodeToByteArray(), mask)
        }

        private fun encode(data: ByteArray, mask: Int): QrCode {
            val version = (1..MAX_VERSION).firstOrNull { HEADER_BITS + countBits(it) + data.size * 8 <= dataCodewords(it) * 8 }
                ?: throw IllegalArgumentException("${data.size} bytes is too long for a version-$MAX_VERSION QR code.")
            val grid = Grid(version)
            grid.drawFunctionPatterns()
            grid.drawCodewords(interleaved(version, padded(data, version)))
            grid.applyMask(mask)
            grid.drawFormat(mask)
            return QrCode(version, mask, grid.modules)
        }

        /** Mode, count, data, terminator, and pad bytes up to the version's data capacity. */
        private fun padded(data: ByteArray, version: Int): ByteArray {
            val bits = Bits()
            bits.append(BYTE_MODE, HEADER_BITS)
            bits.append(data.size, countBits(version))
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(version) * 8
            bits.append(0, minOf(TERMINATOR_BITS, capacity - bits.length))
            bits.append(0, (8 - bits.length % 8) % 8)
            val bytes = bits.toBytes()
            return ByteArray(capacity / 8) { i -> if (i < bytes.size) bytes[i] else PAD[(i - bytes.size) % 2].toByte() }
        }

        /** [data] split into the version's blocks, each with its error correction, then interleaved. */
        private fun interleaved(version: Int, data: ByteArray): ByteArray {
            val blockCount = BLOCKS[version]
            val eccLength = ECC_PER_BLOCK[version]
            val raw = rawDataModules(version) / 8
            val shortBlocks = blockCount - raw % blockCount
            val shortLength = raw / blockCount
            val divisor = reedSolomonDivisor(eccLength)
            var offset = 0
            val blocks = (0 until blockCount).map { i ->
                val length = shortLength - eccLength + if (i < shortBlocks) 0 else 1
                val chunk = data.copyOfRange(offset, offset + length).also { offset += length }
                val block = chunk.copyOf(shortLength + 1)
                reedSolomonRemainder(chunk, divisor).copyInto(block, block.size - eccLength)
                block
            }
            val out = ArrayList<Byte>(raw)
            for (i in 0..shortLength) {
                blocks.forEachIndexed { j, block -> if (i != shortLength - eccLength || j >= shortBlocks) out += block[i] }
            }
            return out.toByteArray()
        }

        private fun countBits(version: Int): Int = if (version < 10) 8 else 16

        private fun dataCodewords(version: Int): Int = rawDataModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        /** The modules left for data and error correction once the function patterns are drawn. */
        private fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree).also { it[degree - 1] = 1 }
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMultiply(root, 2)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                result.copyInto(result, 0, 1)
                result[result.size - 1] = 0
                divisor.forEachIndexed { i, d -> result[i] = (result[i].toInt() xor gfMultiply(d.toInt() and 0xFF, factor)).toByte() }
            }
            return result
        }

        /** [x] times [y] in GF(2⁸), modulo x⁸ + x⁴ + x³ + x² + 1. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private const val MASKS = 8
        private const val BYTE_MODE = 0b0100
        private const val HEADER_BITS = 4
        private const val TERMINATOR_BITS = 4
        private val PAD = intArrayOf(0xEC, 0x11)

        /** Level M, by version (index 0 unused): error-correction codewords per block, and the number of blocks. */
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    }

    /** The standard's penalty for this symbol: lower scans better. */
    private fun penalty(): Int {
        var result = 0
        val lines = modules.indices.map { y -> BooleanArray(size) { x -> modules[y][x] } } +
            modules.indices.map { x -> BooleanArray(size) { y -> modules[y][x] } }
        lines.forEach { result += linePenalty(it) }
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += PENALTY_BLOCK
            }
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        result += ((kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * PENALTY_BALANCE
        return result
    }

    /** Runs of five or more alike, and finder-like 1:1:3:1:1 patterns with light space beside them. */
    private fun linePenalty(line: BooleanArray): Int {
        var result = 0
        var runColor = false
        var runLength = 0
        val history = IntArray(7)
        fun addHistory(length: Int) {
            val adjusted = if (history[0] == 0) length + size else length
            history.copyInto(history, 1, 0, history.size - 1)
            history[0] = adjusted
        }
        fun finderLike(): Int {
            val n = history[1]
            val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
            return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) + (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
        }
        for (module in line) {
            if (module == runColor) {
                runLength++
                if (runLength == 5) result += PENALTY_RUN else if (runLength > 5) result++
            } else {
                addHistory(runLength)
                if (!runColor) result += finderLike() * PENALTY_FINDER
                runColor = module
                runLength = 1
            }
        }
        if (runColor) {
            addHistory(runLength)
            runLength = 0
        }
        addHistory(runLength + size)
        return result + finderLike() * PENALTY_FINDER
    }
}

private const val PENALTY_RUN = 3
private const val PENALTY_BLOCK = 3
private const val PENALTY_FINDER = 40
private const val PENALTY_BALANCE = 10

/** A symbol under construction: its modules, and which of them the function patterns own. */
private class Grid(private val version: Int) {
    val size = version * 4 + 17
    val modules = Array(size) { BooleanArray(size) }
    private val reserved = Array(size) { BooleanArray(size) }

    private fun set(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        reserved[y][x] = true
    }

    fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        val positions = alignmentPositions()
        val last = positions.size - 1
        for (i in positions.indices) {
            for (j in positions.indices) {
                val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                if (!corner) drawAlignment(positions[i], positions[j])
            }
        }
        drawFormat(0)
        drawVersion()
    }

    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                if (x in 0 until size && y in 0 until size) set(x, y, distance != 2 && distance != 4)
            }
        }
    }

    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
    }

    private fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        return IntArray(count) { i -> if (i == 0) 6 else size - 7 - (count - 1 - i) * step }
    }

    /** The format information, level M's bits (00) and [mask], in both of its copies, and the dark module. */
    fun drawFormat(mask: Int) {
        val data = LEVEL_M shl 3 or mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = (data shl 10 or remainder) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var remainder = version
        repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
        val bits = version shl 12 or remainder
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val b = i / 3
            set(a, b, dark)
            set(b, a, dark)
        }
    }

    /** [data] in the standard's zigzag, two columns at a time from the bottom right, around the function patterns. */
    fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!reserved[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !reserved[y][x]) modules[y][x] = !modules[y][x]
            }
        }
    }

    private companion object {
        /** Level M's two format bits. */
        const val LEVEL_M = 0
    }
}

/** Bits appended most significant first. */
private class Bits {
    private val bits = ArrayList<Boolean>()
    val length: Int get() = bits.size

    fun append(value: Int, count: Int) {
        for (i in count - 1 downTo 0) bits += (value ushr i) and 1 != 0
    }

    fun toBytes(): ByteArray = ByteArray(bits.size / 8) { i ->
        (0 until 8).fold(0) { acc, j -> acc shl 1 or if (bits[i * 8 + j]) 1 else 0 }.toByte()
    }
}
