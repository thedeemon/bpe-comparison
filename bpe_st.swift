import Foundation

var data : [UInt16] = []
let fname = if CommandLine.argc > 1 { CommandLine.arguments[1] } else { "enw3" }

func loadData() throws {
    let fileURL = URL(fileURLWithPath: fname)
    let fileHandle = try FileHandle(forReadingFrom: fileURL)
    defer { fileHandle.closeFile() }
    while true {
        if let chunk = try fileHandle.read(upToCount: 2000000) {
            for x in chunk { data.append(UInt16(x)) }
        } else { break }
    }
}

struct WalkIter : IteratorProtocol {
    var pos : Int

    init() { pos = 0; skipHoles() }

    mutating func skipHoles() {
        while pos < data.count && data[pos] == 0 {
            pos += 1
        }
    }

    mutating func next() -> Int? {
        if pos >= data.count { return nil }
        let p = pos
        pos += 1
        skipHoles()
        return p
    }
}

struct PairWalker {
    var walker : WalkIter
    var p1, p2: Int?

    init() {
        walker = WalkIter()
        p1 = walker.next()
        p2 = walker.next()
    }

    mutating func next() -> (Int,Int)? {
        if let a = p1, let b = p2 {
            p1 = p2
            p2 = walker.next()
            return (a,b)
        } else { return nil }
    }

    mutating func afterReplace(_ replacePos : Int) -> (Int?, Int?) {
        // after a pair was replaced, p1 points to 0 value, p2 to next alive
        p1 = p2
        p2 = walker.next()
        var prevIdx : Int?
        if replacePos > 0 {
            var i = replacePos - 1
            while data[i]==0 && i > 0 { i -= 1 }
            if data[i] != 0 { prevIdx = i }
        }
        return (prevIdx, p1)
    }
}

struct VPair: Hashable, Comparable {
    let value1: UInt16
    let value2: UInt16

    init(_ value1: UInt16, _ value2: UInt16) {
        self.value1 = value1
        self.value2 = value2
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(value1)
        hasher.combine(value2)
    }

    static func == (lhs: VPair, rhs: VPair) -> Bool {
        return lhs.value1 == rhs.value1 && lhs.value2 == rhs.value2
    }

    static func < (lhs: VPair, rhs: VPair) -> Bool {
        if lhs.value1 != rhs.value1 {
            return lhs.value1 < rhs.value1
        }
        return lhs.value2 < rhs.value2
    }
}

func calcPairHisto() -> [VPair : Int] {
    var h : [VPair : Int] = [:]
    var pairs = PairWalker()
    while let p = pairs.next() {
        h[VPair(data[p.0], data[p.1]), default: 0] += 1
    }
    return h
}

func mostFreqVals<K: Comparable>(_ h : [K: Int]) -> (K,Int,Int) {
    var minTop : K?
    var maxN = 0, total = 0
    for (k,n) in h {
        total += n
        if n > maxN {
            maxN = n
            minTop = k
        } else
        if n == maxN {
            minTop = min(minTop ?? k, k)
        }
    }
    return (minTop!, maxN, total)
}

func replacePair(_ oldValuePair : VPair, _ newVal : UInt16, _ ph: inout [VPair : Int]) {
    var pairs = PairWalker()
    while let indexPair = pairs.next() {
        let vp = VPair(data[indexPair.0], data[indexPair.1])
        if vp == oldValuePair {
            data[indexPair.0] = newVal
            data[indexPair.1] = 0
            let (prevIdxOpt, nextIdxOpt) = pairs.afterReplace(indexPair.0)
            if let prevIdx = prevIdxOpt {
                let leftVal = data[prevIdx]
                ph[VPair(leftVal, vp.value1)]! -= 1
                let newLeftPair = VPair(leftVal, newVal)
                ph[newLeftPair, default: 0] += 1
            }
            if let nextIdx = nextIdxOpt {
                let rightVal = data[nextIdx]
                ph[VPair(vp.value2, rightVal)]! -= 1
                let newRightPair = VPair(newVal, rightVal)
                ph[newRightPair, default: 0] += 1
            }
        }
    }
}

var nextValue : UInt16 = 256
var thesaurus : [UInt16 : [UInt8]] = [:]

func oneStep(_ step : Int, _ ph: inout [VPair : Int]) -> Bool {
    let (vp, n, total) = mostFreqVals(ph)
    if n == 1 { return true }
    if Double(total) < Double(data.count) * 0.707 {
        var compacted : [UInt16] = []
        var iter = WalkIter()
        compacted.reserveCapacity(total+1)
        while let pos = iter.next() {
            compacted.append(data[pos])
        }
        data = compacted
    }
    if (step % 100 == 0) {
        print("Step \(step): n=\(n) \(vp) -> \(nextValue)")
    }
    replacePair(vp, nextValue, &ph)
    ph[vp] = 0
    thesaurus[nextValue] = thesaurus[vp.value1]! + thesaurus[vp.value2]!
    nextValue += 1
    return false
}

func saveTokens() throws {
    let chunkSize = 1000000
    let fileManager = FileManager.default
    let filePath = "\(fname).tok"

    if !fileManager.fileExists(atPath: filePath) {
        fileManager.createFile(atPath: filePath, contents: nil, attributes: nil)
    }

    let fileHandle = try FileHandle(forUpdating: URL(fileURLWithPath: filePath))
    defer { try? fileHandle.close() }
    var buffer = [UInt16]()
    buffer.reserveCapacity(chunkSize)
    var w = WalkIter()
    fileHandle.truncateFile(atOffset: 0)

    var h : [UInt16 : Int] = [:]
    while let pos = w.next() {
        buffer.append(data[pos])
        h[data[pos], default: 0] += 1
        if buffer.count >= chunkSize {
            fileHandle.write(Data(bytes: buffer, count: buffer.count * 2))
            buffer.removeAll(keepingCapacity: true)
        }
    }

    if !buffer.isEmpty {
        fileHandle.write(Data(bytes: buffer, count: buffer.count * 2))
    }

    // let tsPath = "\(fname)-words.txt"
    // if !fileManager.fileExists(atPath: tsPath) {
    //     fileManager.createFile(atPath: tsPath, contents: nil, attributes: nil)
    // }
    // let fh = try FileHandle(forUpdating: URL(fileURLWithPath: tsPath))
    // defer { try? fh.close() }
    // fh.truncateFile(atOffset: 0)
    // let toks = thesaurus.keys.sorted(by: {t1, t2 in h[t1, default: 0] > h[t2, default: 0] })
    // for t in toks {
    //     try fh.write(contentsOf: "N=\(h[t, default: 0]) \t tok=\(t): \t ".data(using: .utf8)!)
    //     try fh.write(contentsOf: thesaurus[t]!)
    //     try fh.write(contentsOf: [10])
    // }
}

for x in 0...255 {
    thesaurus[UInt16(x)] = [UInt8(x)]
}

try loadData()
var ph = calcPairHisto()

for i in 1...65000 {
    if oneStep(i, &ph) { break }
    if i % 1000 == 0 {
        do { try saveTokens() } catch { print("\(error)")}
    }
}
try saveTokens()
