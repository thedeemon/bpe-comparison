const fs = require('fs');

let data = [];
const fname = process.argv.length > 2 ? process.argv[2] : "enw3";

function loadData() {
    return new Promise((resolve, reject) => {
        const readStream = fs.createReadStream(fname);
        readStream.on('data', (chunk) => {
            for (let x of chunk) {
                data.push(x);
            }
        });
        readStream.on('end', resolve);
        readStream.on('error', reject);
    });
}

class WalkIter {
    constructor() {
        this.pos = 0;
        this.skipHoles();
    }

    skipHoles() {
        while (this.pos < data.length && data[this.pos] === 0) {
            this.pos++;
        }
    }

    next() {
        if (this.pos >= data.length) return null;
        const p = this.pos;
        this.pos++;
        this.skipHoles();
        return p;
    }
}

class PairWalker {
    constructor() {
        this.walker = new WalkIter();
        this.p1 = this.walker.next();
        this.p2 = this.walker.next();
    }

    next() {
        if (this.p1 !== null && this.p2 !== null) {
            const result = [this.p1, this.p2];
            this.p1 = this.p2;
            this.p2 = this.walker.next();
            return result;
        } else {
            return null;
        }
    }

    afterReplace(replacePos) {
        this.p1 = this.p2;
        this.p2 = this.walker.next();
        let prevIdx = null;
        if (replacePos > 0) {
            let i = replacePos - 1;
            while (data[i] === 0 && i > 0) i--;
            if (data[i] !== 0) prevIdx = i;
        }
        return [prevIdx, this.p1];
    }
}

class VPair {
    constructor(value1, value2) {
        this.value1 = value1;
        this.value2 = value2;
    }

    toString() {
        return `${this.value1},${this.value2}`;
    }

    static fromString(str) {
        const [value1, value2] = str.split(',').map(Number);
        return new VPair(value1, value2);
    }

    lessThan(b) {
        if (this.value1 !== b.value1) {
            return this.value1 < b.value1;
        }
        return this.value2 < b.value2;
    }

}

function calcPairHisto() {
    const h = new Map();
    const pairs = new PairWalker();
    let p;
    while ((p = pairs.next()) !== null) {
        const key = new VPair(data[p[0]], data[p[1]]).toString();
        h.set(key, (h.get(key) || 0) + 1);
    }
    return h;
}

function mostFreqVals(h) {
    let minTop = null;
    let maxN = 0;
    let total = 0;
    for (const [k, n] of h.entries()) {
        total += n;
        if (n > maxN) {
            maxN = n;
            minTop = k;
        } else if (n === maxN && n > 0) {
            let kpair = VPair.fromString(k);
            let minTopPair = VPair.fromString(minTop);
            minTop = kpair.lessThan(minTopPair) ? k : minTop;
        }
    }
    return [VPair.fromString(minTop), maxN, total];
}

function replacePair(oldValuePair, newVal, ph) {
    const pairs = new PairWalker();
    let indexPair;
    while ((indexPair = pairs.next()) !== null) {
        const vp = new VPair(data[indexPair[0]], data[indexPair[1]]);
        if (vp.value1 === oldValuePair.value1 && vp.value2 === oldValuePair.value2) {
            data[indexPair[0]] = newVal;
            data[indexPair[1]] = 0;
            const [prevIdxOpt, nextIdxOpt] = pairs.afterReplace(indexPair[0]);
            if (prevIdxOpt !== null) {
                const leftVal = data[prevIdxOpt];
                ph.set(`${leftVal},${vp.value1}`, ph.get(`${leftVal},${vp.value1}`) - 1);
                const newLeftPair = `${leftVal},${newVal}`;
                ph.set(newLeftPair, (ph.get(newLeftPair) || 0) + 1);
            }
            if (nextIdxOpt !== null) {
                const rightVal = data[nextIdxOpt];
                ph.set(`${vp.value2},${rightVal}`, ph.get(`${vp.value2},${rightVal}`) - 1);
                const newRightPair = `${newVal},${rightVal}`;
                ph.set(newRightPair, (ph.get(newRightPair) || 0) + 1);
            }
        }
    }
}

let nextValue = 256;
const thesaurus = new Map();

function oneStep(step, ph) {
    const [vp, n, total] = mostFreqVals(ph);
    if (n === 1) return true;
    if (total < data.length * 0.707) {
        const compacted = [];
        const iter = new WalkIter();
        let pos;
        while ((pos = iter.next()) !== null) {
            compacted.push(data[pos]);
        }
        data = compacted;
    }
    if (step % 100 === 0) {
        console.log(`Step ${step}: n=${n} ${vp.toString()} -> ${nextValue}`);
    }
    replacePair(vp, nextValue, ph);
    ph.set(vp.toString(), 0);
    thesaurus.set(nextValue, [...thesaurus.get(vp.value1), ...thesaurus.get(vp.value2)]);
    nextValue++;
    return false;
}

function saveTokens() {
    const chunkSize = 1000000;
    const filePath = `${fname}.jstok`;
    const writeStream = fs.createWriteStream(filePath);
    const buffer = Buffer.alloc(chunkSize * 2);
    let bufferPos = 0;
    const w = new WalkIter();
    const h = new Map();

    let pos;
    while ((pos = w.next()) !== null) {
        buffer.writeUInt16LE(data[pos], bufferPos);
        bufferPos += 2;
        h.set(data[pos], (h.get(data[pos]) || 0) + 1);
        if (bufferPos >= chunkSize * 2) {
            writeStream.write(buffer.slice(0, bufferPos));
            bufferPos = 0;
        }
    }

    if (bufferPos > 0) {
        writeStream.write(buffer.slice(0, bufferPos));
    }

    writeStream.end();
}

async function main() {
    for (let x = 0; x <= 255; x++) {
        thesaurus.set(x, [x]);
    }

    await loadData();
    let ph = calcPairHisto();

    for (let i = 1; i <= 65000; i++) {
        if (oneStep(i, ph)) break;
        if (i % 1000 === 0) {
            try {
                await saveTokens();
            } catch (error) {
                console.error(error);
            }
        }
    }
    await saveTokens();
}

main().catch(console.error);
