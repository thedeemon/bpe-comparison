import std.stdio, std.typecons, std.algorithm : min;

__gshared ushort[] data;

void loadData(string fname) {
    auto file = File(fname, "rb");
    auto buf = new ubyte[2000000];
    while (!file.eof) {
        auto chunk = file.rawRead(buf);
        foreach (x; chunk) {
            data ~= cast(ushort) x;
        }
    }
}

struct WalkIter {
    int pos;
    this(int dummy) { pos = 0; skipHoles(); }

    void skipHoles() {
        while(pos < data.length && data[pos] == 0)
            pos++;
    }

    Nullable!int next() {
        if (pos >= data.length) return Nullable!int.init;
        auto p = pos;
        pos++;
        skipHoles();
        return nullable(p);
    }
}

struct PairWalker {
    WalkIter walker;
    Nullable!int p1, p2;

    this(int dummy) {
        walker = WalkIter(0);
        p1 = walker.next();
        p2 = walker.next();
    }

    Nullable!(Tuple!(int,int)) next() {
        if (p1.isNull || p2.isNull)
            return typeof(return).init;
        auto a = p1.get, b = p2.get;
        p1 = p2;
        p2 = walker.next();
        return nullable(tuple(a,b));
    }

    void afterReplace(int replacePos, ref Nullable!int leftPos, ref Nullable!int rightPos) {
        p1 = p2;
        p2 = walker.next();
        if (replacePos > 0) {
            auto i = replacePos - 1;
            while(data[i]==0 && (i > 0)) i--;
            if (data[i] != 0) leftPos = nullable(i);
        }
        rightPos = p1;
    }
}

int cmp(int a, int b) { return a - b; }

struct VPair {
    ushort value1, value2;

    int opCmp(ref const VPair x) {
        if (value1 == x.value1) return cmp(value2, x.value2);
        return cmp(value1, x.value1);
    }

    size_t toHash() const @safe pure nothrow {
        size_t x = value1;
        return (x << 16) + value2;
    }
}

int[VPair] calcPairHisto() {
    int[VPair] h;
    auto pairs = PairWalker(0);
    while(true) {
        auto pn = pairs.next();
        if (pn.isNull) break;
        auto p = pn.get;
        h[VPair(data[p[0]], data[p[1]])]++;
    }
    return h;
}

VPair mostFreqVal(int[VPair] h, out int maxN, out int total) {
    VPair minTop;
    maxN = 0; total = 0;
    foreach(k,n; h) {
        total += n;
        if (n > maxN) {
            maxN = n; minTop = k;
        } else
        if (n == maxN && k < minTop) {
            minTop = k;
        }
    }
    return minTop;
}

void replacePair(VPair oldValuePair, ushort newVal, ref int[VPair] _ph) {
    auto ph = _ph;
    auto pairs = PairWalker(0);
    while(true) {
        auto pn = pairs.next();
        if (pn.isNull) break;
        auto indexPair = pn.get;
        auto vp = VPair(data[indexPair[0]], data[indexPair[1]]);
        if (vp == oldValuePair) {
            data[indexPair[0]] = newVal;
            data[indexPair[1]] = 0;
            Nullable!int prevIdxOpt, nextIdxOpt;
            pairs.afterReplace(indexPair[0], prevIdxOpt, nextIdxOpt);
            if (!prevIdxOpt.isNull) {
                auto leftVal = data[prevIdxOpt.get];
                ph[VPair(leftVal, vp.value1)]--;
                ph[VPair(leftVal, newVal)]++;
            }
            if (!nextIdxOpt.isNull) {
                auto rightVal = data[nextIdxOpt.get];
                ph[VPair(vp.value2, rightVal)]--;
                ph[VPair(newVal, rightVal)]++;
            }
        }
    }
}

ushort nextValue = 256;
ubyte[][ushort] thesaurus;

bool oneStep(int step, ref int[VPair] ph) {
    int n, total;
    auto vp = mostFreqVal(ph, n, total);
    if (n==1) return true;
    if (total < data.length * 0.707) {
        ushort[] compacted;
        auto iter = WalkIter(0);
        compacted.reserve(total + 1);
        while(true) {
            auto pn = iter.next();
            if (pn.isNull) break;
            compacted ~= data[pn.get];
        }
        data = compacted;
    }
    if (step % 100 == 0)
        writefln("Step %d: n=%d %s -> %d", step, n, vp, nextValue);
    replacePair(vp, nextValue, ph);
    ph[vp] = 0;
    thesaurus[nextValue] = thesaurus[vp.value1] ~ thesaurus[vp.value2];
    nextValue++;
    return false;
}

void saveTokens(string fname) {
    auto f = File(fname ~ ".dtok", "wb");
    auto chunkSize = 1000000;
    ushort[] buffer;
    buffer.reserve(chunkSize);
    auto w = WalkIter(0);
    int[ushort] h;
    while(true) {
        auto pn = w.next();
        if (pn.isNull) break;
        buffer ~= data[pn.get];
        h[data[pn.get]]++;
        if (buffer.length >= chunkSize) {
            f.rawWrite(buffer);
            buffer.length = 0;
        }
    }
    if (buffer.length > 0)
        f.rawWrite(buffer);
}

void main(string[] argv) {
    string fname = argv.length > 1 ? argv[1] : "enw3";
    loadData(fname);

    foreach(ubyte x; 0..256) thesaurus[x] = [x];

    auto ph = calcPairHisto();
    foreach(i; 1..65001) {
        if (oneStep(i, ph)) break;
        if (i % 1000 == 0)
            saveTokens(fname);
    }
    saveTokens(fname);
}
