from __future__ import annotations

from collections import defaultdict
from array import array
from dataclasses import dataclass
from pathlib import Path
import sys
from typing import DefaultDict


data: list[int] = []
fname = sys.argv[1] if len(sys.argv) > 1 else "enw3"
next_value = 256
thesaurus: dict[int, bytes] = {x: bytes((x,)) for x in range(256)}


def load_data() -> None:
    with open(fname, "rb") as f:
        while chunk := f.read(2_000_000):
            data.extend(chunk)


class WalkIter:
    def __init__(self) -> None:
        self.pos = 0
        self._skip_holes()

    def _skip_holes(self) -> None:
        while self.pos < len(data) and data[self.pos] == 0:
            self.pos += 1

    def __iter__(self) -> WalkIter:
        return self

    def __next__(self) -> int:
        if self.pos >= len(data):
            raise StopIteration
        pos = self.pos
        self.pos += 1
        self._skip_holes()
        return pos


class PairWalker:
    def __init__(self) -> None:
        self.walker = WalkIter()
        self.p1 = next(self.walker, None)
        self.p2 = next(self.walker, None)

    def __iter__(self) -> PairWalker:
        return self

    def __next__(self) -> tuple[int, int]:
        if self.p1 is None or self.p2 is None:
            raise StopIteration
        pair = (self.p1, self.p2)
        self.p1 = self.p2
        self.p2 = next(self.walker, None)
        return pair

    def after_replace(self, replace_pos: int) -> tuple[int | None, int | None]:
        self.p1 = self.p2
        self.p2 = next(self.walker, None)

        prev_idx = None
        if replace_pos > 0:
            i = replace_pos - 1
            while data[i] == 0 and i > 0:
                i -= 1
            if data[i] != 0:
                prev_idx = i

        return prev_idx, self.p1


@dataclass(frozen=True, order=True, slots=True)
class VPair:
    value1: int
    value2: int


def calc_pair_histo() -> DefaultDict[VPair, int]:
    hist: DefaultDict[VPair, int] = defaultdict(int)
    for index_pair in PairWalker():
        hist[VPair(data[index_pair[0]], data[index_pair[1]])] += 1
    return hist


def most_freq_vals(hist: dict[VPair, int]) -> tuple[VPair, int, int]:
    min_top: VPair | None = None
    max_n = 0
    total = 0

    for pair, n in hist.items():
        total += n
        if n > max_n:
            max_n = n
            min_top = pair
        elif n == max_n:
            min_top = min(pair, min_top or pair)

    if min_top is None:
        raise ValueError("no pairs in input")
    return min_top, max_n, total


def replace_pair(old_value_pair: VPair, new_val: int, pair_hist: DefaultDict[VPair, int]) -> None:
    pairs = PairWalker()
    for index_pair in pairs:
        vp = VPair(data[index_pair[0]], data[index_pair[1]])
        if vp != old_value_pair:
            continue

        data[index_pair[0]] = new_val
        data[index_pair[1]] = 0
        prev_idx, next_idx = pairs.after_replace(index_pair[0])

        if prev_idx is not None:
            left_val = data[prev_idx]
            pair_hist[VPair(left_val, vp.value1)] -= 1
            pair_hist[VPair(left_val, new_val)] += 1

        if next_idx is not None:
            right_val = data[next_idx]
            pair_hist[VPair(vp.value2, right_val)] -= 1
            pair_hist[VPair(new_val, right_val)] += 1


def compact_data() -> None:
    global data
    data = [value for value in data if value]


def one_step(step: int, pair_hist: DefaultDict[VPair, int]) -> bool:
    global next_value

    vp, n, total = most_freq_vals(pair_hist)
    if n == 1:
        return True

    if total < len(data) * 0.707:
        compact_data()

    if step % 100 == 0:
        print(f"Step {step}: n={n} {vp} -> {next_value}")

    replace_pair(vp, next_value, pair_hist)
    pair_hist[vp] = 0
    thesaurus[next_value] = thesaurus[vp.value1] + thesaurus[vp.value2]
    next_value += 1
    return False


def save_tokens() -> None:
    output_path = Path(f"{fname}.tok")
    hist: DefaultDict[int, int] = defaultdict(int)
    chunk_size = 1_000_000
    buffer: array[int] = array("H")

    with output_path.open("wb") as f:
        for pos in WalkIter():
            value = data[pos]
            buffer.append(value)
            hist[value] += 1

            if len(buffer) >= chunk_size:
                write_uint16_le(f, buffer)
                buffer.clear()

        if buffer:
            write_uint16_le(f, buffer)


def write_uint16_le(file, values: array[int]) -> None:
    if sys.byteorder == "little":
        file.write(values.tobytes())
        return

    little_endian_values = array("H", values)
    little_endian_values.byteswap()
    file.write(little_endian_values.tobytes())


def main() -> None:
    load_data()
    pair_hist = calc_pair_histo()

    for step in range(1, 65_001):
        if one_step(step, pair_hist):
            break
        if step % 1000 == 0:
            save_tokens()

    save_tokens()


if __name__ == "__main__":
    main()
