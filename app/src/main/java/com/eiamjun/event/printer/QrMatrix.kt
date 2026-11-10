package com.eiamjun.event.printer

/**
 * A QR encoder — byte mode, error-correction level M, versions 1–13.
 *
 * A direct port of `web/shared/qr.js`, kept line-for-line with it so the two cannot drift;
 * `QrMatrixTest` pins the Kotlin against matrices the JavaScript produced.
 *
 * **Why the app needs one at all.** Printing doesn't: `GS ( k` hands the payload to the printer
 * and its firmware draws the code. This is for the bill shown *on screen* after คิดเงิน, where
 * there is no firmware to delegate to and a customer may rather scan the till's screen than take
 * the paper.
 *
 * Level M is what Thai QR payment signage uses, and version 13 holds 334 bytes against the ~124 a
 * ถุงเงิน payload needs — room to spare without carrying the whole specification.
 */
object QrMatrix {

    /** [total codewords, ec per block, group 1 (blocks, data cw), group 2] — level M. */
    private val VERSIONS = arrayOf(
        intArrayOf(26, 10, 1, 16, 0, 0),
        intArrayOf(44, 16, 1, 28, 0, 0),
        intArrayOf(70, 26, 1, 44, 0, 0),
        intArrayOf(100, 18, 2, 32, 0, 0),
        intArrayOf(134, 24, 2, 43, 0, 0),
        intArrayOf(172, 16, 4, 27, 0, 0),
        intArrayOf(196, 18, 4, 31, 0, 0),
        intArrayOf(242, 22, 2, 38, 2, 39),
        intArrayOf(292, 22, 3, 36, 2, 37),
        intArrayOf(346, 26, 4, 43, 1, 44),
        intArrayOf(404, 30, 1, 50, 4, 51),
        intArrayOf(466, 22, 6, 36, 2, 37),
        intArrayOf(532, 22, 8, 37, 1, 38)
    )

    private val ALIGNMENT = arrayOf(
        intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50), intArrayOf(6, 30, 54), intArrayOf(6, 32, 58), intArrayOf(6, 34, 62)
    )

    /** BCH-encoded and XOR-masked format bits for level M, masks 0–7, straight from the spec. */
    private val FORMAT_BITS =
        intArrayOf(0x5412, 0x5125, 0x5e7c, 0x5b4b, 0x45f9, 0x40ce, 0x4f97, 0x4aa0)

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /**
     * The generator polynomial, **highest power first** — [1, 216, 194, …] for degree 10.
     *
     * The order is easy to get backwards and the symptom is a code that looks plausible and scans
     * as nothing; the JavaScript had exactly that bug once. Reversed here so it matches the
     * spec's tables and can be checked by eye.
     */
    private fun generator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        repeat(degree) { i ->
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor mul(poly[j], EXP[i])
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly.reversedArray()
    }

    private fun ecCodewords(data: List<Int>, count: Int): IntArray {
        val gen = generator(count)
        val out = IntArray(count)
        for (byte in data) {
            val factor = byte xor out[0]
            for (i in 0 until count - 1) out[i] = out[i + 1]
            out[count - 1] = 0
            for (i in 0 until count) out[i] = out[i] xor mul(gen[i + 1], factor)
        }
        return out
    }

    private fun pickVersion(byteLength: Int): Int {
        for (v in VERSIONS.indices) {
            val (total, ecPerBlock) = VERSIONS[v][0] to VERSIONS[v][1]
            val blocks = VERSIONS[v][2] + VERSIONS[v][4]
            val capacity = total - ecPerBlock * blocks
            val header = 4 + if (v + 1 >= 10) 16 else 8
            if (byteLength * 8 + header <= capacity * 8) return v + 1
        }
        error("payload too long for this encoder")
    }

    private fun dataCodewords(text: String, version: Int): List<Int> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val bits = StringBuilder()
        fun push(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bits.append((value shr i) and 1)
        }

        push(0b0100, 4) // byte mode
        push(bytes.size, if (version >= 10) 16 else 8)
        for (byte in bytes) push(byte.toInt() and 0xff, 8)

        val spec = VERSIONS[version - 1]
        val count = spec[0] - spec[1] * (spec[2] + spec[4])
        val capacity = count * 8

        var terminator = 0
        while (terminator < 4 && bits.length < capacity) {
            bits.append('0')
            terminator++
        }
        while (bits.length % 8 != 0) bits.append('0')

        val words = mutableListOf<Int>()
        for (i in bits.indices step 8) words += bits.substring(i, i + 8).toInt(2)
        val pad = intArrayOf(0xec, 0x11)
        var i = 0
        while (words.size < count) {
            words += pad[i % 2]
            i++
        }
        return words
    }

    /** Blocks, EC appended, then interleaved — the order the matrix is filled in. */
    private fun interleave(words: List<Int>, version: Int): List<Int> {
        val spec = VERSIONS[version - 1]
        val ecPerBlock = spec[1]
        val blocks = mutableListOf<Pair<List<Int>, IntArray>>()
        var at = 0
        for ((blockCount, blockSize) in listOf(spec[2] to spec[3], spec[4] to spec[5])) {
            repeat(blockCount) {
                val data = words.subList(at, at + blockSize).toList()
                at += blockSize
                blocks += data to ecCodewords(data, ecPerBlock)
            }
        }

        val out = mutableListOf<Int>()
        val longest = blocks.maxOf { it.first.size }
        for (i in 0 until longest) {
            for ((data, _) in blocks) if (i < data.size) out += data[i]
        }
        for (i in 0 until ecPerBlock) {
            for ((_, ec) in blocks) out += ec[i]
        }
        return out
    }

    private class Grid(val size: Int) {
        val modules = Array(size) { IntArray(size) }
        val reserved = Array(size) { BooleanArray(size) }
    }

    private fun placeFinder(g: Grid, row: Int, col: Int) {
        for (r in -1..7) for (c in -1..7) {
            val y = row + r
            val x = col + c
            if (y !in 0 until g.size || x !in 0 until g.size) continue
            val ring = (r in 0..6 && (c == 0 || c == 6)) || (c in 0..6 && (r == 0 || r == 6))
            val core = r in 2..4 && c in 2..4
            g.modules[y][x] = if (ring || core) 1 else 0
            g.reserved[y][x] = true
        }
    }

    private fun placeAlignment(g: Grid, version: Int) {
        val centres = ALIGNMENT[version - 1]
        for (row in centres) for (col in centres) {
            if ((row == 6 && col == 6) || (row == 6 && col == g.size - 7) ||
                (row == g.size - 7 && col == 6)
            ) continue
            for (r in -2..2) for (c in -2..2) {
                g.modules[row + r][col + c] =
                    if (maxOf(kotlin.math.abs(r), kotlin.math.abs(c)) != 1) 1 else 0
                g.reserved[row + r][col + c] = true
            }
        }
    }

    private fun placeTiming(g: Grid) {
        for (i in 8 until g.size - 8) {
            val bit = if (i % 2 == 0) 1 else 0
            g.modules[6][i] = bit
            g.modules[i][6] = bit
            g.reserved[6][i] = true
            g.reserved[i][6] = true
        }
    }

    private fun reserveFormat(g: Grid) {
        for (i in 0 until 9) {
            g.reserved[8][i] = true
            g.reserved[i][8] = true
        }
        for (i in 0 until 8) {
            g.reserved[8][g.size - 1 - i] = true
            g.reserved[g.size - 1 - i][8] = true
        }
        g.modules[g.size - 8][8] = 1 // the module that is always dark
        g.reserved[g.size - 8][8] = true
    }

    /**
     * The 18-bit version block, for versions 7 and up — two 3×6 patches beside the top-right and
     * bottom-left finders.
     *
     * Only versions 7+ carry it, which is why versions 1–6 scanned perfectly while everything
     * larger read as nothing: a decoder that cannot find this block on a code big enough to need it
     * gives up before it looks at a single data module. The shop's ถุงเงิน payload is version 8.
     *
     * The 6 version bits are followed by a 12-bit BCH(18,6) remainder, generator 0x1F25.
     */
    private fun placeVersion(g: Grid, version: Int) {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1f25) }
        val bits = ((version shl 12) or rem) and 0x3ffff

        for (i in 0 until 18) {
            val bit = (bits shr i) and 1
            val a = g.size - 11 + (i % 3)
            val b = i / 3
            g.modules[b][a] = bit
            g.modules[a][b] = bit
            g.reserved[b][a] = true
            g.reserved[a][b] = true
        }
    }

    private fun placeData(g: Grid, words: List<Int>) {
        var bitIndex = 0
        fun nextBit(): Int {
            val byte = words.getOrNull(bitIndex shr 3) ?: 0
            val bit = (byte shr (7 - (bitIndex and 7))) and 1
            bitIndex++
            return bit
        }

        var upward = true
        var right = g.size - 1
        while (right > 0) {
            if (right == 6) right--
            for (step in 0 until g.size) {
                val row = if (upward) g.size - 1 - step else step
                for (col in intArrayOf(right, right - 1)) {
                    if (g.reserved[row][col]) continue
                    g.modules[row][col] = nextBit()
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private fun masked(r: Int, c: Int, mask: Int): Boolean = when (mask) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> (r / 2 + c / 3) % 2 == 0
        5 -> (r * c) % 2 + (r * c) % 3 == 0
        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
    }

    private fun applyMask(g: Grid, mask: Int): Grid {
        val out = Grid(g.size)
        for (r in 0 until g.size) for (c in 0 until g.size) {
            out.reserved[r][c] = g.reserved[r][c]
            out.modules[r][c] =
                if (g.reserved[r][c]) g.modules[r][c]
                else g.modules[r][c] xor (if (masked(r, c, mask)) 1 else 0)
        }
        return out
    }

    /**
     * The 15 format bits, twice: once around the top-left finder, once split between the other two.
     *
     * **Row and column are easy to swap here and the result still looks like a QR code.** The spec
     * states these positions as (column, row) while everything else here is `modules[row][col]`;
     * writing them straight across transposed the lot, and a scanner reads the format first — it
     * cannot find the mask without it. The codes came out plausible and scanned as nothing.
     */
    private fun placeFormat(g: Grid, mask: Int) {
        val bits = FORMAT_BITS[mask]
        for (i in 0 until 15) {
            val bit = (bits shr i) and 1
            // Around the top-left finder: down column 8, then left along row 8.
            when {
                i < 6 -> g.modules[i][8] = bit
                i == 6 -> g.modules[7][8] = bit
                i == 7 -> g.modules[8][8] = bit
                i == 8 -> g.modules[8][7] = bit
                else -> g.modules[8][14 - i] = bit
            }
            // The second copy: along row 8 from the right, then up column 8 from the bottom.
            if (i < 8) g.modules[8][g.size - 1 - i] = bit
            else g.modules[g.size - 15 + i][8] = bit
        }
    }

    /** The spec's four penalty rules — lower is easier to scan. */
    private fun penalty(g: Grid): Int {
        var score = 0
        val size = g.size
        val m = g.modules

        for (pass in 0..1) {
            for (a in 0 until size) {
                var last = -1
                var length = 0
                for (b in 0 until size) {
                    val value = if (pass == 0) m[a][b] else m[b][a]
                    if (value == last) {
                        length++
                        if (length == 5) score += 3 else if (length > 5) score += 1
                    } else {
                        last = value
                        length = 1
                    }
                }
            }
        }

        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            val v = m[r][c]
            if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3
        }

        val pattern = intArrayOf(1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0)
        for (a in 0 until size) {
            for (b in 0..size - pattern.size) {
                if (pattern.indices.all { m[a][b + it] == pattern[it] }) score += 40
                if (pattern.indices.all { m[b + it][a] == pattern[it] }) score += 40
            }
        }

        var dark = 0
        for (row in m) for (v in row) dark += v
        score += kotlin.math.abs(dark * 100 / (size * size) - 50) / 5 * 10
        return score
    }

    /** @return one row per line, `true` where a module is dark. */
    fun encode(text: String): Array<BooleanArray> {
        val version = pickVersion(text.toByteArray(Charsets.UTF_8).size)
        val words = interleave(dataCodewords(text, version), version)

        val base = Grid(version * 4 + 17)
        placeFinder(base, 0, 0)
        placeFinder(base, 0, base.size - 7)
        placeFinder(base, base.size - 7, 0)
        placeAlignment(base, version)
        placeTiming(base)
        reserveFormat(base)
        placeVersion(base, version)
        placeData(base, words)

        var best: Grid? = null
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val candidate = applyMask(base, mask)
            placeFormat(candidate, mask)
            val score = penalty(candidate)
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        val grid = checkNotNull(best)
        return Array(grid.size) { r -> BooleanArray(grid.size) { c -> grid.modules[r][c] == 1 } }
    }
}
