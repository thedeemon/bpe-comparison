import java.io.File
import java.io.RandomAccessFile
import java.io.FileInputStream
import kotlin.math.min

var data: UShortArray = UShortArray(0)

fun loadData(fname: String) {
    val file = File(fname)
    val arraySize = file.length().toInt()

    data = UShortArray(arraySize)
    var index = 0

    FileInputStream(file).use { fis ->
        val buffer = ByteArray(2000000)
        var bytesRead: Int

        while (fis.read(buffer).also { bytesRead = it } != -1) {
            for (i in 0 until bytesRead) {
                data[index++] = buffer[i].toUByte().toUShort()
            }
        }
    }
}

class WalkIter {
    var pos = 0

    init {
        skipHoles()
    }

    private fun skipHoles() {
        while (pos < data.size && data[pos] == 0.toUShort()) {
            pos++
        }
    }

    fun next(): Int? {
        if (pos >= data.size) return null
        val p = pos
        pos++
        skipHoles()
        return p
    }
}

class PairWalker {
    private var walker = WalkIter()
    private var p1: Int? = walker.next()
    private var p2: Int? = walker.next()

    fun next(): Pair<Int, Int>? {
        val a = p1
        val b = p2
        if (a != null && b != null) {
            p1 = p2
            p2 = walker.next()
            return Pair(a, b)
        }
        return null
    }

    fun afterReplace(replacePos: Int): Pair<Int?, Int?> {
        p1 = p2
        p2 = walker.next()
        var prevIdx: Int? = null
        if (replacePos > 0) {
            var i = replacePos - 1
            while (data[i] == 0.toUShort() && i > 0) { i-- }
            if (data[i] != 0.toUShort()) { prevIdx = i }
        }
        return Pair(prevIdx, p1)
    }
}

data class VPair(val value1: UShort, val value2: UShort) : Comparable<VPair> {
    override fun compareTo(other: VPair): Int {
        return when {
            value1 != other.value1 -> value1.compareTo(other.value1)
            else -> value2.compareTo(other.value2)
        }
    }
}

fun calcPairHisto(): MutableMap<VPair, Int> {
    val h = mutableMapOf<VPair, Int>()
    val pairs = PairWalker()
    while (true) {
        val p = pairs.next() ?: break
        val vp = VPair(data[p.first], data[p.second])
        h[vp] = h.getOrDefault(vp, 0) + 1
    }
    return h
}

fun mostFreqVal(h: MutableMap<VPair, Int>): Triple<VPair, Int, Int> {
    var minTop = VPair(0.toUShort(), 0.toUShort())
    var maxN = 0
    var total = 0
    for ((k, n) in h) {
        total += n
        if (n > maxN) {
            maxN = n
            minTop = k
        } else if (n == maxN && k < minTop) {
            minTop = k
            // minTop = minTop?.let { if (it < k) it else k } ?: k
        }
    }
    return Triple(minTop, maxN, total)
}

fun replacePair(oldValuePair: VPair, newVal: UShort, ph: MutableMap<VPair, Int>) {
    val pairs = PairWalker()
    while (true) {
        val indexPair = pairs.next() ?: break
        val vp = VPair(data[indexPair.first], data[indexPair.second])
        if (vp == oldValuePair) {
            data[indexPair.first] = newVal
            data[indexPair.second] = 0.toUShort()
            val (prevIdxOpt, nextIdxOpt) = pairs.afterReplace(indexPair.first)
            prevIdxOpt?.let { prevIdx ->
                val leftVal = data[prevIdx]
                ph.compute(VPair(leftVal, vp.value1)) { _, v -> (v ?: 0) - 1 }
                ph.compute(VPair(leftVal, newVal)) { _, v -> (v ?: 0) + 1 }
            }
            nextIdxOpt?.let { nextIdx ->
                val rightVal = data[nextIdx]
                ph.compute(VPair(vp.value2, rightVal) ) { _, v -> (v ?: 0) - 1 }
                ph.compute(VPair(newVal, rightVal)) { _, v -> (v ?: 0) + 1 }
            }
        }
    }
}

var nextValue: UShort = 256.toUShort()
var thesaurus: MutableMap<UShort, List<UByte>> = mutableMapOf()

fun oneStep(step: Int, ph: MutableMap<VPair, Int>): Boolean {
    val (vp, n, total) = mostFreqVal(ph)
    if (n == 1) return true
    if (total.toDouble() < data.size * 0.707) {
        var newSize = 0
        for (i in data.indices) {
            if (data[i] != 0.toUShort()) {
                data[newSize++] = data[i]
            }
        }
        data = data.copyOf(newSize)
    }
    if (step % 100 == 0) {
        println("Step $step: n=$n $vp -> $nextValue")
    }
    replacePair(vp, nextValue, ph)
    ph[vp] = 0
    thesaurus[nextValue] = thesaurus[vp.value1]!! + thesaurus[vp.value2]!!
    nextValue++
    return false
}

fun saveTokens(fname: String) {
    val chunkSize = 1000000
    val filePath = "$fname.ktok"
    val file = RandomAccessFile(filePath, "rw")
    file.setLength(0)

    val buffer = UShortArray(chunkSize)
    var bufferIndex = 0
    val w = WalkIter()

    val h = mutableMapOf<UShort, Int>()
    while (true) {
        val pos = w.next() ?: break
        buffer[bufferIndex++] = data[pos]
        h[data[pos]] = h.getOrDefault(data[pos], 0) + 1
        if (bufferIndex >= chunkSize) {
            file.write(buffer.foldIndexed(ByteArray(chunkSize * 2)) { i, acc, us ->
                acc[i * 2] = us.toByte()
                acc[i * 2 + 1] = (us.toInt() shr 8).toByte()
                acc
            })
            bufferIndex = 0
        }
    }

    if (bufferIndex > 0) {
        file.write(buffer.slice(0 until bufferIndex).foldIndexed(ByteArray(bufferIndex * 2)) { i, acc, us ->
            acc[i * 2] = us.toByte()
            acc[i * 2 + 1] = (us.toInt() shr 8).toByte()
            acc
        })
    }

    file.close()
}

fun main(args: Array<String>) {
    val fname = if (args.isNotEmpty()) args[0] else "enw3"
    for (x in 0..255) {
        thesaurus[x.toUShort()] = listOf(x.toUByte())
    }

    loadData(fname)
    println(data.size)
    var ph = calcPairHisto()

    for (i in 1..65000) {
        if (oneStep(i, ph)) break
        if (i % 1000 == 0) {
            saveTokens(fname)
        }
    }
    saveTokens(fname)
}
