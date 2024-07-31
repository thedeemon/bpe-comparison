#include <iostream>
#include <fstream>
#include <vector>
#include <unordered_map>
#include <algorithm>
#include <optional>
#include <tuple>

std::vector<unsigned short> data;

void loadData(const std::string& fname) {
    std::ifstream file(fname, std::ios::binary);
    std::vector<unsigned char> buf(2000000);
    while (file) {
        file.read(reinterpret_cast<char*>(buf.data()), buf.size());
        std::streamsize count = file.gcount();
        for (std::streamsize i = 0; i < count; ++i) {
            data.push_back(static_cast<unsigned short>(buf[i]));
        }
    }
}

class WalkIter {
public:
    WalkIter() : pos(0) { skipHoles(); }

    void skipHoles() {
        while (pos < data.size() && data[pos] == 0)
            ++pos;
    }

    std::optional<int> next() {
        if (pos >= data.size()) return std::nullopt;
        int p = pos;
        ++pos;
        skipHoles();
        return p;
    }

private:
    size_t pos;
};

class PairWalker {
public:
    PairWalker() : walker(), p1(walker.next()), p2(walker.next()) {}

    std::optional<std::pair<int, int>> next() {
        if (!p1 || !p2) return std::nullopt;
        int a = *p1, b = *p2;
        p1 = p2;
        p2 = walker.next();
        return std::make_pair(a, b);
    }

    void afterReplace(int replacePos, std::optional<int>& leftPos, std::optional<int>& rightPos) {
        p1 = p2;
        p2 = walker.next();
        if (replacePos > 0) {
            int i = replacePos - 1;
            while (data[i] == 0 && (i > 0)) --i;
            if (data[i] != 0) leftPos = i;
        }
        rightPos = p1;
    }

private:
    WalkIter walker;
    std::optional<int> p1, p2;
};

struct VPair {
    unsigned short value1, value2;

    bool operator<(const VPair& x) const {
        if (value1 == x.value1) return value2 < x.value2;
        return value1 < x.value1;
    }

    bool operator==(const VPair& x) const {
        return value1 == x.value1 && value2 == x.value2;
    }
};

namespace std {
    template<>
    struct hash<VPair> {
        size_t operator()(const VPair& vp) const {
            return (static_cast<size_t>(vp.value1) << 16) | vp.value2;
        }
    };
}

std::unordered_map<VPair, int> calcPairHisto() {
    std::unordered_map<VPair, int> h;
    PairWalker pairs;
    while (auto pn = pairs.next()) {
        auto [p1, p2] = *pn;
        ++h[VPair{data[p1], data[p2]}];
    }
    return h;
}

VPair mostFreqVal(const std::unordered_map<VPair, int>& h, int& maxN, int& total) {
    VPair minTop;
    maxN = 0; total = 0;
    for (const auto& [k, n] : h) {
        total += n;
        if (n > maxN || (n == maxN && k < minTop)) {
            maxN = n;
            minTop = k;
        }
    }
    return minTop;
}

void replacePair(VPair oldValuePair, unsigned short newVal, std::unordered_map<VPair, int>& ph) {
    PairWalker pairs;
    while (auto pn = pairs.next()) {
        auto [idx1, idx2] = *pn;
        VPair vp{data[idx1], data[idx2]};
        if (vp == oldValuePair) {
            data[idx1] = newVal;
            data[idx2] = 0;
            std::optional<int> prevIdxOpt, nextIdxOpt;
            pairs.afterReplace(idx1, prevIdxOpt, nextIdxOpt);
            if (prevIdxOpt) {
                auto leftVal = data[*prevIdxOpt];
                --ph[VPair{leftVal, vp.value1}];
                ++ph[VPair{leftVal, newVal}];
            }
            if (nextIdxOpt) {
                auto rightVal = data[*nextIdxOpt];
                --ph[VPair{vp.value2, rightVal}];
                ++ph[VPair{newVal, rightVal}];
            }
        }
    }
}

unsigned short nextValue = 256;
std::unordered_map<unsigned short, std::vector<unsigned char>> thesaurus;

bool oneStep(int step, std::unordered_map<VPair, int>& ph) {
    int n, total;
    auto vp = mostFreqVal(ph, n, total);
    if (n == 1) return true;
    if (total < data.size() * 0.707) {
        auto new_end = std::remove(data.begin(), data.end(), 0);
        data.erase(new_end, data.end());
    }
    if (step % 100 == 0)
        std::cout << "Step " << step << ": n=" << n << " (" << vp.value1 << "," << vp.value2 << ") -> " << nextValue << std::endl;
    replacePair(vp, nextValue, ph);
    ph[vp] = 0;
    thesaurus[nextValue] = thesaurus[vp.value1];
    thesaurus[nextValue].insert(thesaurus[nextValue].end(), thesaurus[vp.value2].begin(), thesaurus[vp.value2].end());
    ++nextValue;
    return false;
}

void saveTokens(const std::string& fname) {
    std::ofstream f(fname + ".ctok", std::ios::binary);
    const size_t chunkSize = 1000000;
    std::vector<unsigned short> buffer;
    buffer.reserve(chunkSize);
    WalkIter w;
    std::unordered_map<unsigned short, int> h;
    while (auto pn = w.next()) {
        buffer.push_back(data[*pn]);
        ++h[data[*pn]];
        if (buffer.size() >= chunkSize) {
            f.write(reinterpret_cast<const char*>(buffer.data()), buffer.size() * sizeof(unsigned short));
            buffer.clear();
        }
    }
    if (!buffer.empty())
        f.write(reinterpret_cast<const char*>(buffer.data()), buffer.size() * sizeof(unsigned short));
}

int main(int argc, char* argv[]) {
    std::string fname = (argc > 1) ? argv[1] : "enw3";
    loadData(fname);

    for (unsigned char x = 0; x < 255; ++x) thesaurus[x] = {x};

    auto ph = calcPairHisto();
    for (int i = 1; i <= 65000; ++i) {
        if (oneStep(i, ph)) break;
        if (i % 1000 == 0)
            saveTokens(fname);
    }
    saveTokens(fname);
    return 0;
}
