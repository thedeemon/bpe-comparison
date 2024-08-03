package main

import (
    "bufio"
    "encoding/binary"
    "fmt"
    "io"
    "os"
)

var data []uint16
var nextValue uint16 = 256
var thesaurus = make(map[uint16][]byte)

func loadData(fname string) {
    file, err := os.Open(fname)
    if err != nil {
        panic(err)
    }
    defer file.Close()

    info, err := file.Stat()
    if err != nil {
        panic(err)
    }

    data = make([]uint16, info.Size())
    buffer := make([]byte, 2000000)
    reader := bufio.NewReader(file)
    index := 0

    for {
        n, err := reader.Read(buffer)
        if err != nil && err != io.EOF {
            panic(err)
        }
        if n == 0 {
            break
        }

        for i := 0; i < n; i++ {
            data[index] = uint16(buffer[i])
            index++
        }
    }
}

type walkIter struct {
    pos int
}

func newWalkIter() *walkIter {
    w := &walkIter{}
    w.skipHoles()
    return w
}

func (w *walkIter) skipHoles() {
    for w.pos < len(data) && data[w.pos] == 0 {
        w.pos++
    }
}

func (w *walkIter) next() (int, bool) {
    if w.pos >= len(data) {
        return 0, false
    }
    p := w.pos
    w.pos++
    w.skipHoles()
    return p, true
}

type pairWalker struct {
    walker *walkIter
    p1, p2 int
    hasP1, hasP2 bool
}

func newPairWalker() *pairWalker {
    pw := &pairWalker{walker: newWalkIter()}
    pw.p1, pw.hasP1 = pw.walker.next()
    pw.p2, pw.hasP2 = pw.walker.next()
    return pw
}

func (pw *pairWalker) next() (int, int, bool) {
    if pw.hasP1 && pw.hasP2 {
        result := [2]int{pw.p1, pw.p2}
        pw.p1, pw.hasP1 = pw.p2, pw.hasP2
        pw.p2, pw.hasP2 = pw.walker.next()
        return result[0], result[1], true
    }
    return 0, 0, false
}

func (pw *pairWalker) afterReplace(replacePos int) (int, int, bool, bool) {
    pw.p1, pw.hasP1 = pw.p2, pw.hasP2
    pw.p2, pw.hasP2 = pw.walker.next()
    prevIdx := -1
    hasPrevIdx := false
    if replacePos > 0 {
        i := replacePos - 1
        for data[i] == 0 && i > 0 {
            i--
        }
        if data[i] != 0 {
            prevIdx = i
            hasPrevIdx = true
        }
    }
    return prevIdx, pw.p1, hasPrevIdx, pw.hasP1
}

type vPair struct {
    value1, value2 uint16
}

func (vp vPair) lessThan(a vPair) bool {
    if vp.value1 == a.value1 {
        return vp.value2 < a.value2
    }
    return vp.value1 < a.value1
}

func calcPairHisto() map[vPair]int {
    h := make(map[vPair]int)
    pairs := newPairWalker()
    for {
        p1, p2, ok := pairs.next()
        if !ok {
            break
        }
        vp := vPair{data[p1], data[p2]}
        h[vp]++
    }
    return h
}

func mostFreqVal(h map[vPair]int) (vPair, int, int) {
    minTop := vPair{0, 0}
    maxN := 0
    total := 0
    for vp, count := range h {
        total += count
        if count > maxN || (count == maxN && vp.lessThan(minTop)) {
            maxN = count
            minTop = vp
        }
    }
    return minTop, maxN, total
}

func replacePair(oldValuePair vPair, newVal uint16, ph map[vPair]int) {
    pairs := newPairWalker()
    for {
        p1, p2, ok := pairs.next()
        if !ok {
            break
        }
        vp := vPair{data[p1], data[p2]}
        if vp == oldValuePair {
            data[p1] = newVal
            data[p2] = 0
            prevIdx, nextIdx, hasPrevIdx, hasNextIdx := pairs.afterReplace(p1)
            if hasPrevIdx {
                leftVal := data[prevIdx]
                leftOldPair := vPair{leftVal, oldValuePair.value1}
                leftNewPair := vPair{leftVal, newVal}
                ph[leftOldPair]--
                ph[leftNewPair]++
            }
            if hasNextIdx {
                rightVal := data[nextIdx]
                rightOldPair := vPair{oldValuePair.value2, rightVal}
                rightNewPair := vPair{newVal, rightVal}
                ph[rightOldPair]--
                ph[rightNewPair]++
            }
        }
    }
}

func oneStep(step int, ph map[vPair]int) bool {
    vp, n, total := mostFreqVal(ph)
    if n == 1 {
        return true
    }
    if float64(total) < float64(len(data))*0.707 {
        newSize := 0
        for i := 0; i < len(data); i++ {
            if data[i] != 0 {
                data[newSize] = data[i]
                newSize++
            }
        }
        data = data[:newSize]
    }
    if step%100 == 0 {
        fmt.Printf("Step %d: n=%d %d,%d -> %d\n", step, n, vp.value1, vp.value2, nextValue)
    }
    replacePair(vp, nextValue, ph)
    ph[vp] = 0
    thesaurus[nextValue] = append(thesaurus[vp.value1], thesaurus[vp.value2]...)
    nextValue++
    return false
}

func saveTokens(fname string) {
    const chunkSize = 1000000
    filePath := fmt.Sprintf("%s.gtok", fname)
    file, err := os.Create(filePath)
    if err != nil {
        panic(err)
    }
    defer file.Close()

    writer := bufio.NewWriter(file)
    defer writer.Flush()

    buffer := make([]uint16, chunkSize)
    bufferIndex := 0
    w := newWalkIter()
    h := make(map[uint16]int)

    for {
        pos, ok := w.next()
        if !ok {
            break
        }
        buffer[bufferIndex] = data[pos]
        h[data[pos]]++
        bufferIndex++
        if bufferIndex >= chunkSize {
            for i := 0; i < chunkSize; i++ {
                binary.Write(writer, binary.LittleEndian, buffer[i])
            }
            bufferIndex = 0
        }
    }

    if bufferIndex > 0 {
        for i := 0; i < bufferIndex; i++ {
            binary.Write(writer, binary.LittleEndian, buffer[i])
        }
    }
}

func main() {
    fname := "enw3"
    if len(os.Args) > 1 {
        fname = os.Args[1]
    }

    for x := 0; x <= 255; x++ {
        thesaurus[uint16(x)] = []byte{byte(x)}
    }

    loadData(fname)
    fmt.Println(len(data))
    ph := calcPairHisto()

    for i := 1; i <= 65000; i++ {
        if oneStep(i, ph) {
            break
        }
        if i%1000 == 0 {
            saveTokens(fname)
        }
    }
    saveTokens(fname)
}
