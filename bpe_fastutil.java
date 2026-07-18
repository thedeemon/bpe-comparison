import java.io.*;
import java.util.*;
import it.unimi.dsi.fastutil.chars.Char2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

// javac -cp fastutil.jar bpe_fastutil.java
// jar cvfm bpej.jar MANIFEST.MF bpe_fastutil.class *.class

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

        int next() {
            if (pos >= data.length) return -1;
            int p = pos;
            pos++;
            skipHoles();
            return p;
        }
    }

    private static class PairWalker {
        private WalkIter walker = new WalkIter();
        private int p1 = walker.next();
        private int p2 = walker.next();
        int key = -1;
        int value = -1;
        int prev = -1;
        int next = -1;

        boolean next() {
            if (p1 != -1 && p2 != -1) {
                key = p1;
                value = p2;
                p1 = p2;
                p2 = walker.next();
                return true;
            }
            return false;
        }

        void afterReplace(int replacePos) {
            p1 = p2;
            p2 = walker.next();
            prev = -1;
            if (replacePos > 0) {
                int i = replacePos - 1;
                while (data[i] == 0 && i > 0) { i--; }
                if (data[i] != 0) { prev = i; }
            }
            next = p1;
        }
    }

    private static int pairKey(char value1, char value2) {
        return (value1 << 16) | value2;
    }

    private static char pairKeyFirst(int key) {
        return (char) (key >>> 16);
    }

    private static char pairKeySecond(int key) {
        return (char) key;
    }

    private static Int2IntOpenHashMap calcPairHisto() {
        Int2IntOpenHashMap h = new Int2IntOpenHashMap();
        h.defaultReturnValue(0);
        PairWalker pairs = new PairWalker();
        while (pairs.next()) {
            h.addTo(pairKey(data[pairs.key], data[pairs.value]), 1);
        }
        return h;
    }

    private static record Triple(int first, int second, int third) {}

    private static Triple mostFreqVal(Int2IntOpenHashMap h) {
        int minTop = pairKey((char) 0, (char) 0);
        int maxN = 0;
        int total = 0;
        for (Int2IntMap.Entry entry : h.int2IntEntrySet()) {
            int k = entry.getIntKey();
            int n = entry.getIntValue();
            total += n;
            if (n > maxN) {
                maxN = n;
                minTop = k;
            } else if (n == maxN && Integer.compareUnsigned(k, minTop) < 0) {
                minTop = k;
            }
        }
        return new Triple(minTop, maxN, total);
    }

    private static void replacePair(int oldValuePair, char newVal, Int2IntOpenHashMap ph) {
        PairWalker pairs = new PairWalker();
        while (pairs.next()) {
            int key = pairs.key;
            int value = pairs.value;
            char value1 = data[key];
            char value2 = data[value];
            int vp = pairKey(value1, value2);
            if (vp == oldValuePair) {
                data[key] = newVal;
                data[value] = 0;
                pairs.afterReplace(key);
                if (pairs.prev != -1) {
                    char leftVal = data[pairs.prev];
                    ph.addTo(pairKey(leftVal, value1), -1);
                    ph.addTo(pairKey(leftVal, newVal), 1);
                }
                if (pairs.next != -1) {
                    char rightVal = data[pairs.next];
                    ph.addTo(pairKey(value2, rightVal), -1);
                    ph.addTo(pairKey(newVal, rightVal), 1);
                }
            }
        }
    }

    private static char nextValue = 256;
    private static Map<Character, List<Byte>> thesaurus = new HashMap<>();

    private static boolean oneStep(int step, Int2IntOpenHashMap ph) {
        Triple result = mostFreqVal(ph);
        int vp = result.first;
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
            System.out.println("Step " + step + ": n=" + n + " " + (int)pairKeyFirst(vp) + ","
                + (int)pairKeySecond(vp) + " -> " + (int)nextValue);
        }
        replacePair(vp, nextValue, ph);
        ph.put(vp, 0);
        List<Byte> newThesaurus = new ArrayList<>(thesaurus.get(pairKeyFirst(vp)));
        newThesaurus.addAll(thesaurus.get(pairKeySecond(vp)));
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

            Char2IntOpenHashMap h = new Char2IntOpenHashMap();
            h.defaultReturnValue(0);
            int pos;
            while ((pos = w.next()) != -1) {
                buffer[bufferIndex++] = data[pos];
                h.addTo(data[pos], 1);
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
        Int2IntOpenHashMap ph = calcPairHisto();

        for (int i = 1; i <= 65000; i++) {
            if (oneStep(i, ph)) break;
            if (i % 1000 == 0) {
                saveTokens(fname);
            }
        }
        saveTokens(fname);
    }
}
