import java.io.*;
import java.util.*;

public class bpe {
    private static char[] data;

    private static void loadData(String fname) throws IOException {
        File file = new File(fname);
        int arraySize = (int) file.length();

        data = new char[arraySize];
        int index = 0;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[2000000];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    data[index++] = (char) (buffer[i] & 0xFF);
                }
            }
        }
    }

    private static class WalkIter {
        private int pos = 0;

        WalkIter() {
            skipHoles();
        }

        private void skipHoles() {
            while (pos < data.length && data[pos] == 0) {
                pos++;
            }
        }

        Integer next() {
            if (pos >= data.length) return null;
            int p = pos;
            pos++;
            skipHoles();
            return p;
        }
    }

    private static class PairWalker {
        private WalkIter walker = new WalkIter();
        private Integer p1 = walker.next();
        private Integer p2 = walker.next();

        IntPair next() {
            Integer a = p1;
            Integer b = p2;
            if (a != null && b != null) {
                p1 = p2;
                p2 = walker.next();
                return new IntPair(a, b);
            }
            return null;
        }

        IntPair afterReplace(int replacePos) {
            p1 = p2;
            p2 = walker.next();
            Integer prevIdx = null;
            if (replacePos > 0) {
                int i = replacePos - 1;
                while (data[i] == 0 && i > 0) { i--; }
                if (data[i] != 0) { prevIdx = i; }
            }
            return new IntPair(prevIdx, p1);
        }
    }

    private static class VPair implements Comparable<VPair> {
        char value1;
        char value2;

        VPair(char value1, char value2) {
            this.value1 = value1;
            this.value2 = value2;
        }

        @Override
        public int compareTo(VPair other) {
            if (value1 != other.value1) {
                return Character.compare(value1, other.value1);
            }
            return Character.compare(value2, other.value2);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VPair vPair = (VPair) o;
            return value1 == vPair.value1 && value2 == vPair.value2;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value1, value2);
        }
    }

    private static Map<VPair, Integer> calcPairHisto() {
        Map<VPair, Integer> h = new HashMap<>();
        PairWalker pairs = new PairWalker();
        IntPair p;
        while ((p = pairs.next()) != null) {
            VPair vp = new VPair(data[p.key], data[p.value]);
            h.put(vp, h.getOrDefault(vp, 0) + 1);
        }
        return h;
    }

    private static record IntPair(Integer key, Integer value) {}
    private static record Triple(VPair first, Integer second, Integer third) {}

    private static Triple mostFreqVal(Map<VPair, Integer> h) {
        VPair minTop = new VPair((char) 0, (char) 0);
        int maxN = 0;
        int total = 0;
        for (Map.Entry<VPair, Integer> entry : h.entrySet()) {
            VPair k = entry.getKey();
            int n = entry.getValue();
            total += n;
            if (n > maxN) {
                maxN = n;
                minTop = k;
            } else if (n == maxN && k.compareTo(minTop) < 0) {
                minTop = k;
            }
        }
        return new Triple(minTop, maxN, total);
    }

    private static void replacePair(VPair oldValuePair, char newVal, Map<VPair, Integer> ph) {
        PairWalker pairs = new PairWalker();
        IntPair indexPair;
        while ((indexPair = pairs.next()) != null) {
            VPair vp = new VPair(data[indexPair.key], data[indexPair.value]);
            if (vp.equals(oldValuePair)) {
                data[indexPair.key] = newVal;
                data[indexPair.value] = 0;
                IntPair afterReplace = pairs.afterReplace(indexPair.key);
                Integer prevIdxOpt = afterReplace.key;
                Integer nextIdxOpt = afterReplace.value;
                if (prevIdxOpt != null) {
                    char leftVal = data[prevIdxOpt];
                    ph.compute(new VPair(leftVal, vp.value1), (k, v) -> (v == null ? 0 : v) - 1);
                    ph.compute(new VPair(leftVal, newVal), (k, v) -> (v == null ? 0 : v) + 1);
                }
                if (nextIdxOpt != null) {
                    char rightVal = data[nextIdxOpt];
                    ph.compute(new VPair(vp.value2, rightVal), (k, v) -> (v == null ? 0 : v) - 1);
                    ph.compute(new VPair(newVal, rightVal), (k, v) -> (v == null ? 0 : v) + 1);
                }
            }
        }
    }

    private static char nextValue = 256;
    private static Map<Character, List<Byte>> thesaurus = new HashMap<>();

    private static boolean oneStep(int step, Map<VPair, Integer> ph) {
        Triple result = mostFreqVal(ph);
        VPair vp = result.first;
        int n = result.second;
        int total = result.third;
        if (n == 1) return true;
        if ((double) total < data.length * 0.707) {
            int newSize = 0;
            for (char c : data) {
                if (c != 0) {
                    data[newSize++] = c;
                }
            }
            data = Arrays.copyOf(data, newSize);
        }
        if (step % 100 == 0) {
            System.out.println("Step " + step + ": n=" + n + " " + (int)vp.value1 + ","
                + (int)vp.value2 + " -> " + (int)nextValue);
        }
        replacePair(vp, nextValue, ph);
        ph.put(vp, 0);
        List<Byte> newThesaurus = new ArrayList<>(thesaurus.get((char) vp.value1));
        newThesaurus.addAll(thesaurus.get((char) vp.value2));
        thesaurus.put(nextValue, newThesaurus);
        nextValue++;
        return false;
    }

    private static void saveTokens(String fname) throws IOException {
        int chunkSize = 1000000;
        String filePath = fname + ".jtok";
        try (RandomAccessFile file = new RandomAccessFile(filePath, "rw")) {
            file.setLength(0);

            char[] buffer = new char[chunkSize];
            int bufferIndex = 0;
            WalkIter w = new WalkIter();

            Map<Character, Integer> h = new HashMap<>();
            Integer pos;
            while ((pos = w.next()) != null) {
                buffer[bufferIndex++] = data[pos];
                h.put(data[pos], h.getOrDefault(data[pos], 0) + 1);
                if (bufferIndex >= chunkSize) {
                    writeBuffer(file, buffer, bufferIndex);
                    bufferIndex = 0;
                }
            }

            if (bufferIndex > 0) {
                writeBuffer(file, buffer, bufferIndex);
            }
        }
    }

    private static void writeBuffer(RandomAccessFile file, char[] buffer, int length) throws IOException {
        byte[] byteBuffer = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            byteBuffer[i * 2] = (byte) buffer[i];
            byteBuffer[i * 2 + 1] = (byte) (buffer[i] >> 8);
        }
        file.write(byteBuffer);
    }

    public static void main(String[] args) throws IOException {
        String fname = args.length > 0 ? args[0] : "enw3";
        for (int x = 0; x <= 255; x++) {
            thesaurus.put((char) x, Collections.singletonList((byte) x));
        }

        loadData(fname);
        System.out.println(data.length);
        Map<VPair, Integer> ph = calcPairHisto();

        for (int i = 1; i <= 65000; i++) {
            if (oneStep(i, ph)) break;
            if (i % 1000 == 0) {
                saveTokens(fname);
            }
        }
        saveTokens(fname);
    }
}
