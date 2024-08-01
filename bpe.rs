use std::collections::HashMap;
use std::env;
use std::fs::File;
use std::io::{Read, Write};
use std::cmp::Ordering;

fn main() -> std::io::Result<()> {
    let mut data: Vec<u16> = Vec::new();
    let fname = env::args().nth(1).unwrap_or_else(|| "enw3".to_string());

    load_data(&fname, &mut data)?;

    let mut next_value: u16 = 256;
    let mut thesaurus: HashMap<u16, Vec<u8>> = (0..=255).map(|x| (x as u16, vec![x as u8])).collect();

    let mut ph = calc_pair_histo(&mut data);

    for i in 1..=65000 {
        if one_step(i, &mut data, &mut ph, &mut next_value, &mut thesaurus) {
            break;
        }
        if i % 1000 == 0 {
            if let Err(e) = save_tokens(&fname, &data) {
                eprintln!("{}", e);
            }
        }
    }
    save_tokens(&fname, &data)?;

    Ok(())
}

fn load_data(fname: &str, data: &mut Vec<u16>) -> std::io::Result<()> {
    let mut file = File::open(fname)?;
    let mut buffer = [0u8; 2000000];
    loop {
        let bytes_read = file.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        data.extend(buffer[..bytes_read].iter().map(|&x| x as u16));
    }
    Ok(())
}

struct WalkIter {
    pos: usize,
}

impl WalkIter {
    fn new(data: &Vec<u16>) -> Self {
        let mut iter = WalkIter { pos: 0 };
        iter.skip_holes(data);
        iter
    }

    fn skip_holes(&mut self, data: &Vec<u16>) {
        while self.pos < data.len() && data[self.pos] == 0 {
            self.pos += 1;
        }
    }

    fn next(&mut self, data: &Vec<u16>) -> Option<usize> {
        if self.pos >= data.len() {
            None
        } else {
            let p = self.pos;
            self.pos += 1;
            self.skip_holes(data);
            Some(p)
        }
    }
}

struct PairWalker {
    walker: WalkIter,
    p1: Option<usize>,
    p2: Option<usize>,
}

impl PairWalker {
    fn new(data: &Vec<u16>) -> Self {
        let mut walker = WalkIter::new(data);
        let p1 = walker.next(data);
        let p2 = walker.next(data);
        PairWalker { walker, p1, p2 }
    }

    fn next(&mut self, data: &Vec<u16>) -> Option<(usize, usize)> {
        if let (Some(a), Some(b)) = (self.p1, self.p2) {
            self.p1 = self.p2;
            self.p2 = self.walker.next(data);
            Some((a, b))
        } else {
            None
        }
    }

    fn after_replace(&mut self, replace_pos: usize, data: &Vec<u16>) -> (Option<usize>, Option<usize>) {
        self.p1 = self.p2;
        self.p2 = self.walker.next(data);
        let prev_idx = if replace_pos > 0 {
            let mut i = replace_pos - 1;
            while i > 0 && data[i] == 0 {
                i -= 1;
            }
            if data[i] != 0 { Some(i) } else { None }
        } else { None };
        (prev_idx, self.p1)
    }
}

#[derive(Hash, Eq, PartialEq, Clone, Copy, Debug)]
struct VPair {
    value1: u16,
    value2: u16,
}

impl Ord for VPair {
    fn cmp(&self, other: &Self) -> Ordering {
        self.value1.cmp(&other.value1).then(self.value2.cmp(&other.value2))
    }
}

impl PartialOrd for VPair {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

fn calc_pair_histo(data: &Vec<u16>) -> HashMap<VPair, usize> {
    let mut h = HashMap::new();
    let mut pairs = PairWalker::new(data);
    while let Some(p) = pairs.next(data) {
        *h.entry(VPair { value1: data[p.0], value2: data[p.1] }).or_insert(0) += 1;
    }
    h
}

fn most_freq_vals<K: Ord + Copy>(h: &HashMap<K, usize>) -> (K, usize, usize) {
    let mut min_top = None;
    let mut max_n = 0;
    let mut total = 0;
    for (&k, &n) in h.iter() {
        total += n;
        if n > max_n {
            max_n = n;
            min_top = Some(k);
        } else if n == max_n {
            min_top = Some(min_top.map_or(k, |mt| mt.min(k)));
        }
    }
    (min_top.unwrap(), max_n, total)
}

fn replace_pair(data: &mut Vec<u16>, old_value_pair: VPair, new_val: u16, ph: &mut HashMap<VPair, usize>) {
    let mut pairs = PairWalker::new(data);
    while let Some(index_pair) = pairs.next(data) {
        let vp = VPair { value1: data[index_pair.0], value2: data[index_pair.1] };
        if vp == old_value_pair {
            data[index_pair.0] = new_val;
            data[index_pair.1] = 0;
            let (prev_idx_opt, next_idx_opt) = pairs.after_replace(index_pair.0, data);
            if let Some(prev_idx) = prev_idx_opt {
                let left_val = data[prev_idx];
                *ph.get_mut(&VPair { value1: left_val, value2: vp.value1 }).unwrap() -= 1;
                *ph.entry(VPair { value1: left_val, value2: new_val }).or_insert(0) += 1;
            }
            if let Some(next_idx) = next_idx_opt {
                let right_val = data[next_idx];
                *ph.get_mut(&VPair { value1: vp.value2, value2: right_val }).unwrap() -= 1;
                *ph.entry(VPair { value1: new_val, value2: right_val }).or_insert(0) += 1;
            }
        }
    }
}

fn one_step(step: usize, data: &mut Vec<u16>, ph: &mut HashMap<VPair, usize>, next_value: &mut u16, thesaurus: &mut HashMap<u16, Vec<u8>>) -> bool {
    let (vp, n, total) = most_freq_vals(ph);
    if n == 1 {
        return true;
    }
    if (total as f64) < (data.len() as f64) * 0.707 {
        *data = data.iter().filter(|&&x| x != 0).cloned().collect();
    }
    if step % 100 == 0 {
        println!("Step {}: n={} {:?} -> {}", step, n, vp, next_value);
    }
    replace_pair(data, vp, *next_value, ph);
    *ph.get_mut(&vp).unwrap() = 0;
    let mut new_entry = thesaurus[&vp.value1].clone();
    new_entry.extend_from_slice(&thesaurus[&vp.value2]);
    thesaurus.insert(*next_value, new_entry);
    *next_value += 1;
    false
}

fn save_tokens(fname: &str, data: &[u16]) -> std::io::Result<()> {
    let chunk_size = 1000000;
    let file_path = format!("{}.rtok", fname);
    let mut file = File::create(&file_path)?;

    let mut buffer = Vec::with_capacity(chunk_size);
    let mut h = HashMap::new();

    for &value in data.iter().filter(|&&x| x != 0) {
        buffer.push(value);
        *h.entry(value).or_insert(0) += 1;
        if buffer.len() >= chunk_size {
            file.write_all(unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const u8, buffer.len() * 2) })?;
            buffer.clear();
        }
    }

    if !buffer.is_empty() {
        file.write_all(unsafe { std::slice::from_raw_parts(buffer.as_ptr() as *const u8, buffer.len() * 2) })?;
    }

    Ok(())
}
