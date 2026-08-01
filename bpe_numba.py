from __future__ import annotations

from array import array
from pathlib import Path
import sys

import numpy as np

try:
    from numba import njit, types
    from numba.typed import Dict
except ModuleNotFoundError as exc:
    raise SystemExit(
        "bpe_numba.py requires numba. Install a Numba version compatible with "
        "your Python/NumPy environment."
    ) from exc


fname = sys.argv[1] if len(sys.argv) > 1 else "enw3"
UINT16_ARRAY = types.uint16[::1]
PAIR_HIST = types.DictType(types.uint64, types.int64)


@njit(types.uint64(types.uint64, types.uint64))
def pair_key(value1: int, value2: int) -> int:
    return (value1 << 16) | value2


@njit(types.uint64(types.uint64))
def pair_first(key: int) -> int:
    return key >> 16


@njit(types.uint64(types.uint64))
def pair_second(key: int) -> int:
    return key & 0xFFFF


@njit(types.UniTuple(types.int64, 2)(UINT16_ARRAY, types.int64))
def next_alive(data: np.ndarray, pos: int) -> tuple[int, int]:
    while pos < len(data) and data[pos] == 0:
        pos += 1
    if pos >= len(data):
        return -1, pos

    value_pos = pos
    pos += 1
    while pos < len(data) and data[pos] == 0:
        pos += 1
    return value_pos, pos


@njit(types.int64(UINT16_ARRAY, types.int64))
def prev_alive(data: np.ndarray, replace_pos: int) -> int:
    if replace_pos <= 0:
        return -1

    pos = replace_pos - 1
    while data[pos] == 0 and pos > 0:
        pos -= 1
    if data[pos] == 0:
        return -1
    return pos


@njit(PAIR_HIST(UINT16_ARRAY))
def calc_pair_histo(data: np.ndarray) -> Dict:
    hist = Dict.empty(key_type=types.uint64, value_type=types.int64)

    p1, pos = next_alive(data, 0)
    p2, pos = next_alive(data, pos)
    while p1 != -1 and p2 != -1:
        key = pair_key(data[p1], data[p2])
        hist[key] = hist.get(key, 0) + 1
        p1 = p2
        p2, pos = next_alive(data, pos)

    return hist


@njit(types.Tuple((types.uint64, types.int64, types.int64))(PAIR_HIST))
def most_freq_vals(hist: Dict) -> tuple[int, int, int]:
    min_top = 0
    max_n = 0
    total = 0
    found = False

    for key, n in hist.items():
        total += n
        if n > max_n:
            max_n = n
            min_top = key
            found = True
        elif n == max_n and (not found or key < min_top):
            min_top = key
            found = True

    if not found:
        return 0, 0, 0
    return min_top, max_n, total


@njit(types.void(UINT16_ARRAY, types.uint64, types.uint64, PAIR_HIST))
def replace_pair(data: np.ndarray, old_key: int, new_val: int, pair_hist: Dict) -> None:
    old_value1 = pair_first(old_key)
    old_value2 = pair_second(old_key)

    p1, pos = next_alive(data, 0)
    p2, pos = next_alive(data, pos)
    while p1 != -1 and p2 != -1:
        current_key = pair_key(data[p1], data[p2])
        if current_key == old_key:
            replace_pos = p1
            data[p1] = new_val
            data[p2] = 0

            right_idx, pos = next_alive(data, pos)
            prev_idx = prev_alive(data, replace_pos)

            if prev_idx != -1:
                left_val = data[prev_idx]
                left_old_key = pair_key(left_val, old_value1)
                pair_hist[left_old_key] = pair_hist[left_old_key] - 1
                left_new_key = pair_key(left_val, new_val)
                pair_hist[left_new_key] = pair_hist.get(left_new_key, 0) + 1

            if right_idx != -1:
                right_val = data[right_idx]
                right_old_key = pair_key(old_value2, right_val)
                pair_hist[right_old_key] = pair_hist[right_old_key] - 1
                right_new_key = pair_key(new_val, right_val)
                pair_hist[right_new_key] = pair_hist.get(right_new_key, 0) + 1

            p1 = right_idx
            p2, pos = next_alive(data, pos)
        else:
            p1 = p2
            p2, pos = next_alive(data, pos)


@njit(UINT16_ARRAY(UINT16_ARRAY))
def compact_data(data: np.ndarray) -> np.ndarray:
    live_count = 0
    for value in data:
        if value != 0:
            live_count += 1

    compacted = np.empty(live_count, dtype=np.uint16)
    out = 0
    for value in data:
        if value != 0:
            compacted[out] = value
            out += 1
    return compacted


@njit(types.Tuple((UINT16_ARRAY, types.int64))(UINT16_ARRAY, types.int64))
def run_bpe(data: np.ndarray, max_steps: int) -> tuple[np.ndarray, int]:
    next_value = 256
    pair_hist = calc_pair_histo(data)

    for step in range(1, max_steps + 1):
        vp, n, total = most_freq_vals(pair_hist)
        if n == 1 or n == 0:
            return data, next_value

        if total < len(data) * 0.707:
            data = compact_data(data)

        if step % 100 == 0:
            print(
                "Step",
                step,
                ": n=",
                n,
                " VPair(value1: ",
                pair_first(vp),
                ", value2: ",
                pair_second(vp),
                ") -> ",
                next_value,
            )

        replace_pair(data, vp, next_value, pair_hist)
        pair_hist[vp] = 0
        next_value += 1

    return data, next_value


def load_data(path: str) -> np.ndarray:
    return np.fromfile(path, dtype=np.uint8).astype(np.uint16)


def live_tokens(data: np.ndarray) -> array[int]:
    tokens = array("H", (int(value) for value in data if value != 0))
    if sys.byteorder != "little":
        tokens.byteswap()
    return tokens


def save_tokens(path: str, data: np.ndarray) -> None:
    with Path(f"{path}.tok").open("wb") as f:
        live_tokens(data).tofile(f)


def main() -> None:
    data = load_data(fname)
    data, _ = run_bpe(data, 65_000)
    save_tokens(fname, data)


if __name__ == "__main__":
    main()
