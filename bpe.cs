class Program {
    static ushort[] data;

    static void LoadData(string fname) {
        using (FileStream fs = new FileStream(fname, FileMode.Open, FileAccess.Read)) {
            data = new ushort[fs.Length];
            byte[] buffer = new byte[2000000];
            int bytesRead;
            int index = 0;

            while ((bytesRead = fs.Read(buffer, 0, buffer.Length)) > 0) {
                for (int i = 0; i < bytesRead; i++)
                    data[index++] = (ushort)buffer[i];
            }
        }
    }

    struct WalkIter {
        public int pos = 0;

        public WalkIter() { SkipHoles(); }

        private void SkipHoles() {
            while (pos < data.Length && data[pos] == 0)
                pos++;
        }

        public int? Next() {
            if (pos >= data.Length) return null;
            int p = pos;
            pos++;
            SkipHoles();
            return p;
        }
    }

    struct PairWalker {
        private WalkIter walker = new WalkIter();
        private int? p1;
        private int? p2;

        public PairWalker() {
            p1 = walker.Next();
            p2 = walker.Next();
        }

        public (int, int)? Next() {
            if (p1.HasValue && p2.HasValue) {
                var result = (p1.Value, p2.Value);
                p1 = p2;
                p2 = walker.Next();
                return result;
            }
            return null;
        }

        public (int?, int?) AfterReplace(int replacePos) {
            p1 = p2;
            p2 = walker.Next();
            int? prevIdx = null;
            if (replacePos > 0) {
                int i = replacePos - 1;
                while (data[i] == 0 && i > 0) i--;
                if (data[i] != 0) prevIdx = i;
            }
            return (prevIdx, p1);
        }
    }

    struct VPair {
        public ushort Value1 { get; }
        public ushort Value2 { get; }

        public VPair(ushort value1, ushort value2) {
            Value1 = value1;
            Value2 = value2;
        }

        public static bool operator ==(VPair lhs, VPair rhs) {
            return lhs.Value1 == rhs.Value1 && lhs.Value2 == rhs.Value2;
        }

        public static bool operator !=(VPair lhs, VPair rhs) { return !(lhs == rhs); }

        public override int GetHashCode() { return HashCode.Combine(Value1, Value2); }

        public bool LessThan(VPair a) {
            if (Value1 == a.Value1) return Value2 < a.Value2;
            return Value1 < a.Value1;
        }
    }

    static Dictionary<VPair, int> CalcPairHisto() {
        var h = new Dictionary<VPair, int>();
        var pairs = new PairWalker();
        while (true) {
            var p = pairs.Next();
            if (!p.HasValue) break;
            var vp = new VPair(data[p.Value.Item1], data[p.Value.Item2]);
            if (h.ContainsKey(vp)) h[vp]++;
            else h[vp] = 1;
        }
        return h;
    }

    static (VPair, int, int) MostFreqVal(Dictionary<VPair, int> h) {
        var minTop = new VPair(0, 0);
        int maxN = 0;
        int total = 0;
        foreach (var kvp in h) {
            total += kvp.Value;
            if (kvp.Value > maxN || (kvp.Value == maxN && kvp.Key.LessThan(minTop))) {
                maxN = kvp.Value;
                minTop = kvp.Key;
            }
        }
        return (minTop, maxN, total);
    }

    static void ReplacePair(VPair oldValuePair, ushort newVal, Dictionary<VPair, int> ph) {
        var pairs = new PairWalker();
        while (true) {
            var indexPair = pairs.Next();
            if (!indexPair.HasValue) break;
            var vp = new VPair(data[indexPair.Value.Item1], data[indexPair.Value.Item2]);
            if (vp == oldValuePair) {
                data[indexPair.Value.Item1] = newVal;
                data[indexPair.Value.Item2] = 0;
                var (prevIdxOpt, nextIdxOpt) = pairs.AfterReplace(indexPair.Value.Item1);
                if (prevIdxOpt.HasValue) {
                    int prevIdx = prevIdxOpt.Value;
                    ushort leftVal = data[prevIdx];
                    var leftOldPair = new VPair(leftVal, oldValuePair.Value1);
                    var leftNewPair = new VPair(leftVal, newVal);
                    ph[leftOldPair]--;
                    if (ph.ContainsKey(leftNewPair)) ph[leftNewPair]++;
                    else ph[leftNewPair] = 1;
                }
                if (nextIdxOpt.HasValue) {
                    int nextIdx = nextIdxOpt.Value;
                    ushort rightVal = data[nextIdx];
                    var rightOldPair = new VPair(oldValuePair.Value2, rightVal);
                    var rightNewPair = new VPair(newVal, rightVal);
                    ph[rightOldPair]--;
                    if (ph.ContainsKey(rightNewPair)) ph[rightNewPair]++;
                    else ph[rightNewPair] = 1;
                }
            }
        }
    }

    static ushort nextValue = 256;
    static Dictionary<ushort, List<byte>> thesaurus = new Dictionary<ushort, List<byte>>();

    static bool OneStep(int step, Dictionary<VPair, int> ph) {
        var (vp, n, total) = MostFreqVal(ph);
        if (n == 1) return true;
        if (total < data.Length * 0.707) {
            int newSize = 0;
            for (int i = 0; i < data.Length; i++)
                if (data[i] != 0)
                    data[newSize++] = data[i];
            Array.Resize(ref data, newSize);
        }
        if (step % 100 == 0)
            Console.WriteLine($"Step {step}: n={n} {vp.Value1},{vp.Value2} -> {nextValue}");
        ReplacePair(vp, nextValue, ph);
        ph[vp] = 0;
        thesaurus[nextValue] = thesaurus[vp.Value1].Concat(thesaurus[vp.Value2]).ToList();
        nextValue++;
        return false;
    }

    static void SaveTokens(string fname) {
        const int chunkSize = 1000000;
        string filePath = $"{fname}.cstok";
        using (BinaryWriter writer = new BinaryWriter(File.Open(filePath, FileMode.Create))) {
            ushort[] buffer = new ushort[chunkSize];
            int bufferIndex = 0;
            var w = new WalkIter();
            var h = new Dictionary<ushort, int>();

            while (true) {
                int? pos = w.Next();
                if (!pos.HasValue) break;
                buffer[bufferIndex++] = data[pos.Value];
                if (!h.ContainsKey(data[pos.Value])) h[data[pos.Value]] = 0;
                h[data[pos.Value]]++;
                if (bufferIndex >= chunkSize) {
                    for (int i = 0; i < chunkSize; i++)
                        writer.Write(buffer[i]);
                    bufferIndex = 0;
                }
            }

            if (bufferIndex > 0)
                for (int i = 0; i < bufferIndex; i++)
                    writer.Write(buffer[i]);
        }
    }

    static void Main(string[] args) {
        string fname = args.Length > 0 ? args[0] : "enw3";
        for (int x = 0; x <= 255; x++)
            thesaurus[(ushort)x] = new List<byte> { (byte)x };

        LoadData(fname);
        Console.WriteLine(data.Length);
        var ph = CalcPairHisto();

        for (int i = 1; i <= 65000; i++) {
            if (OneStep(i, ph)) break;
            if (i % 1000 == 0)
                SaveTokens(fname);
        }
        SaveTokens(fname);
    }
}
