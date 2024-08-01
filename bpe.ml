let fname = if Array.length Sys.argv > 1 then Sys.argv.(1) else "enw3"
let next_value = ref 256
let thesaurus : (int, Bytes.t) Hashtbl.t = Hashtbl.create 256;;

let load_data () =
  let ic = open_in_bin fname in
  let file_size = in_channel_length ic in
  let buffer = Bytes.create file_size in
  really_input ic buffer 0 file_size;
  close_in ic;
  Array.init (Bytes.length buffer) (fun i -> Char.code (Bytes.get buffer i));;

let data = ref (load_data ());;

module WalkIter = struct
  type t = { mutable pos : int }

  let rec skip_holes t =
    if t.pos < Array.length !data && !data.(t.pos) = 0 then begin
      t.pos <- t.pos + 1;
      skip_holes t
    end

  let create () =
    let w = { pos = 0 } in
    skip_holes w; w

  let next t =
    if t.pos >= Array.length !data then None
    else begin
      let p = t.pos in
      t.pos <- t.pos + 1;
      skip_holes t;
      Some p
    end
end

module PairWalker = struct
  type t = {
    mutable walker : WalkIter.t;
    mutable p1 : int option;
    mutable p2 : int option;
  }

  let create () =
    let walker = WalkIter.create () in
    let p1 = WalkIter.next walker in
    let p2 = WalkIter.next walker in
    { walker; p1; p2 }

  let next t =
    match t.p1, t.p2 with
    | Some a, Some b ->
        t.p1 <- t.p2;
        t.p2 <- WalkIter.next t.walker;
        Some (a, b)
    | _ -> None

  let after_replace t replace_pos =
    t.p1 <- t.p2;
    t.p2 <- WalkIter.next t.walker;
    let prev_idx =
      if replace_pos > 0 then
        let rec find_prev i =
          if i < 0 then None
          else if !data.(i) <> 0 then Some i
          else find_prev (i - 1)
        in
        find_prev (replace_pos - 1)
      else None
    in
    (prev_idx, t.p1)
end

module VPair = struct
  type t = int * int

  let compare (a1, a2) (b1, b2) =
    match compare a1 b1 with
    | 0 -> compare a2 b2
    | c -> c

  let equal (a1, a2) (b1, b2) = a1=b1 && a2=b2
  let hash (v1, v2) = (Int.shift_left v1 16) + v2
end

module VPairTbl = Hashtbl.Make(VPair)

let update_vt tbl key delta =
  match VPairTbl.find_opt tbl key with
  | Some v -> VPairTbl.replace tbl key (v + delta)
  | None ->   VPairTbl.add tbl key delta

let calc_pair_histo () =
  let h = VPairTbl.create 30000 in
  let pairs = PairWalker.create () in
  let rec loop () =
    match PairWalker.next pairs with
    | Some (p1, p2) ->
        let pair = (!data.(p1), !data.(p2)) in
        update_vt h pair 1;
        loop ()
    | None -> h
  in
  loop ();;

let most_freq_vals h =
  let minTop = ref (0,0) in
  let maxN = ref 0 in
  let total = ref 0 in
  VPairTbl.iter (fun k n ->
    total := !total + n;
    if n > !maxN then (maxN := n; minTop := k)
    else if n = !maxN && VPair.compare k !minTop < 0 then minTop := k
  ) h;
  (!minTop, !maxN, !total);;

let replace_pair old_value_pair new_val ph =
  let pairs = PairWalker.create () in
  let rec loop () =
    match PairWalker.next pairs with
    | Some (i1, i2) ->
        let vp = (!data.(i1), !data.(i2)) in
        if vp = old_value_pair then begin
          !data.(i1) <- new_val;
          !data.(i2) <- 0;
          let prev_idx, next_idx = PairWalker.after_replace pairs i1 in
          (match prev_idx with
          | Some prev_i ->
              let left_val = !data.(prev_i) in
              update_vt ph (left_val, fst vp) (-1);
              update_vt ph (left_val, new_val) 1
          | None -> ());
          (match next_idx with
          | Some next_i ->
              let right_val = !data.(next_i) in
              update_vt ph (snd vp, right_val) (-1);
              update_vt ph (new_val, right_val) 1
          | None -> ())
        end;
        loop ()
    | None -> ()
  in
  loop ();;

let compact xs =
  Array.to_seq xs |> Seq.filter (fun x -> x <> 0) |> Array.of_seq;;

let one_step step ph =
  let vp, n, total = most_freq_vals ph in
  if n = 1 then true else begin
    if float_of_int total < float_of_int (Array.length !data) *. 0.707 then
      data := compact !data;
    if step mod 100 = 0 then
      (Printf.printf "Step %d: n=%d (%d,%d) -> %d\n" step n (fst vp) (snd vp) !next_value;
      flush stdout);
    replace_pair vp !next_value ph;
    VPairTbl.replace ph vp 0;
    Hashtbl.add thesaurus !next_value (Bytes.cat (Hashtbl.find thesaurus (fst vp)) (Hashtbl.find thesaurus (snd vp)));
    incr next_value;
    false
  end;;

let save_tokens () =
  let dat = compact !data in
  let file_path = fname ^ ".otok" in
  let oc = open_out_bin file_path in
  let buffer = Bytes.create (Array.length dat * 2) in
  let h = Hashtbl.create 256 in
  dat |> Array.iteri (fun i x ->
            Bytes.set_uint16_le buffer (i*2) x;
            match Hashtbl.find_opt h x with
            | Some v -> Hashtbl.replace h x (v + 1)
            | None -> Hashtbl.add h x 1);
  output_bytes oc buffer;
  close_out oc;;

for x = 0 to 255 do
  Hashtbl.add thesaurus x (Bytes.make 1 (char_of_int x))
done;

let ph = calc_pair_histo () in

let rec loop i =
  if i > 65000 then ()
  else if one_step i ph then ()
  else begin
    if i mod 1000 = 0 then save_tokens ();
    loop (i + 1)
  end
in
loop 1;
save_tokens ()
