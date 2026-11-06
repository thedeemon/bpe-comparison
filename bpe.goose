import std; import os;
import dictionary;

var data : u16[>..] = [];

fn loadData(fname: u8[:]) {
    var srcbytes : u8[>..] = [];
    guard read_file(fname, srcbytes) else { print("couldn't read the file"); return from main; }
    for x in srcbytes { data.push(x); }
}

struct Walker { pos : u32 }

fn skipHoles(w : Walker&) {
    while w.pos < data.len && data[w.pos]==0 {
        w.pos += 1;
    }
}

fn nextPos(w : Walker&) -> u32, bool {
    if w.pos >= data.len { return 0, false; }
    let p = w.pos;
    w.pos += 1;
    w.skipHoles();
    return p, true;
}

fn newWalker() -> Walker {
    let w = Walker { pos : 0 };
    w.skipHoles();
    return w;
}

struct PairWalker {
    walker : Walker,
    p1 : u32,
    p2 : u32,
    ok : bool
}

fn newPairWalker() -> PairWalker {
    let w = newWalker();
    let p1, ok1 = w.nextPos();
    let p2, ok2 = w.nextPos();
    return PairWalker { walker: w, p1: p1, p2: p2, ok: ok1 && ok2 };
}

fn next(pw : PairWalker&) -> u32, u32, bool {
    if pw.ok {
        let a = pw.p1;
        let b = pw.p2;
        pw.p1 = pw.p2;
        pw.p2, pw.ok = nextPos(pw.walker);
        return a,b,true;
    }
    return 0,0,false;
}

fn afterReplace(pw: PairWalker&, replacePos:u32) -> u32,bool,u32,bool {
    pw.p1 = pw.p2;
    let ok1 = pw.ok;
    pw.p2, pw.ok = pw.walker.nextPos();
    if replacePos > 0 {
        var i = replacePos - 1;
        while data[i]==0 && i>0 { i -= 1; }
        if data[i] != 0 { return i,true, pw.p1,ok1; }
    }
    return 0,false, pw.p1,ok1;
}

struct VPair {
    value1 : u16,
    value2 : u16
}

fn hash(vp: VPair) -> u64 {
    let h = hash_combine(vp.value1, hash(vp.value2));
    return h;
}

fn lessThan(lhs: VPair, rhs:VPair) -> bool {
    if lhs.value1 != rhs.value1 {
        return lhs.value1 < rhs.value1;
    }
    return lhs.value2 < rhs.value2;
}

fn calcPairHisto() -> dictionary<VPair, u32> {
    let h = dictionary<VPair, u32>{};
    let pairs = newPairWalker();
    loop {
        let p1, p2, ok = pairs.next();
        if ok {
            h.update(VPair{value1: data[p1], value2: data[p2]}, 0) { it += 1; };
        } else { break; }
    }
    return h;
}

fn mostFreqVals(h: dictionary<VPair, u32>&) -> VPair, u32, u32 {
    var minTop = VPair{value1:65535, value2:65535};
    var maxN:u32 = 0; var total:u32 = 0;
    h.each() { k, n =>
        total += n;
        if n > maxN {
            maxN = n;
            minTop = k;
        } else
        if n == maxN {
            if k.lessThan(minTop) {
                minTop = k;
            }
        }
    };
    return minTop, maxN, total;
}

fn replacePair(oldValuePair: VPair, newVal: u16, ph: dictionary<VPair, u32>&) {
    let pairs = newPairWalker();
    loop {
        let p1,p2,ok = pairs.next();
        if !ok { break; }
        let vp = VPair{value1:data[p1], value2:data[p2]};
        if vp == oldValuePair {
            data[p1] = newVal;
            data[p2] = 0;
            let prevIdx,ok1, nextIdx,ok2 = afterReplace(pairs, p1);
            if ok1 {
                let leftVal = data[prevIdx];
                ph.update(VPair{value1: leftVal, value2: vp.value1}, 0) { it -= 1; };
                let newLeftPair = VPair{value1:leftVal, value2:newVal};
                ph.update(newLeftPair, 0) { it += 1; };
            }
            if ok2 {
                let rightVal = data[nextIdx];
                ph.update(VPair{value1:vp.value2, value2: rightVal}, 0) { it -= 1; };
                let newRightPair = VPair{value1: newVal, value2:rightVal};
                ph.update(newRightPair, 0) { it += 1; };
            }
        }
    }
}

var nextValue : u16 = 256;

fn oneStep(step: i64, ph: dictionary<VPair, u32>&) -> bool {
    let vp, n, total = mostFreqVals(ph);
    if n == 1 { return true; }
    if total < data.len * 0.707 {
        var w = newWalker();
        var i = 0;
        loop {
            let pos, ok = w.nextPos();
            if !ok { break; }
            data[i] = data[pos];
            i += 1;
        }
        data.resize(i);
    }
    if step % 100 == 0 {
        print("Step ", step, " n=",n, " ", vp, " -> ", nextValue);
    }
    replacePair(vp, nextValue, ph);
    ph.insert(vp, 0);
    nextValue += 1;
    return false;
}

fn saveTokens(outfname: u8[:]) {
    let w = newWalker();
    var out : u8[>..] = [];
    loop {
        let pos, ok = w.nextPos();
        let v = data[pos];
        if ok {
            out.push(v as! u8);
            out.push((v >> 8) as! u8);
        } else { break; }
    }
    write_file(outfname, out);
}

fn main() {
    let argv = args();
    let fname = if argv.len > 1 { argv[1] } else { "enw3" };
    loadData(fname);
    var ph = calcPairHisto();
    for i in 1..65000 {
        if oneStep(i, ph) { break; }
    }
    saveTokens(concat(fname, ".gtok"));
}
