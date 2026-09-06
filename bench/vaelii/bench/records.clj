;; SPDX-License-Identifier: SSPL-1.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.bench.records
  "Measure the **record** side before building it, the way the index side was
  measured.

  Phase 4 proposes three things on the `:disk` record store, and each rests on a number
  this harness produces:

    1. **int-id record bodies** — freeze a sentence as dictionary ids rather than symbol
       names.  The claim to test is the *frozen-bytes shrink*: a per-record nippy frame
       repeats every predicate/individual name in full, and nippy compresses per frame,
       so cross-frame repetition is never recovered.  Measured three ways — as-is, an
       `int[]` prefix encoding, and a varint `byte[]` — against real records.
    2. **batched `get-many`** — a query fetches K candidate handles one at a time, each
       its own lock + slot read + seek + thaw.  Measured: per-handle vs one batched pass
       over the same handles, offset-sorted.
    3. **an LRU hot-record cache** — measured at a Zipfian access skew, which is what a
       real query stream looks like (a few predicates and individuals dominate).

  Plus the RAM context Phase 2 left open: how much of the columnar index's shared token
  dictionary is *record*-shared, i.e. what int-id bodies would actually release.

  Run: `lein bench-records [sample-n]`  (default 200000 uniform real records)."
  (:require [taoensso.nippy :as nippy]
            [vaelii.bench.postings :as postings]
            [vaelii.bench.survey :as survey]
            [vaelii.bench.util :as u]
            [vaelii.impl.disk.files :as files]
            [vaelii.impl.disk.record-store :as drs]
            [vaelii.impl.disk.tokens :as dtok]
            [vaelii.impl.kb :as kb]
            [vaelii.impl.protocols :as p]
            [vaelii.impl.tokens :as tok])
  (:import [java.util ArrayList]))

;; ---- the int-id body encoding -------------------------------------------
;; A sentence is a nested s-expression of symbols/numbers/keywords.  Encode it in prefix
;; order into a flat int sequence: every atom is a dictionary id (>= 0), and structure is
;; carried by four negative control codes.  This is what a dense record body stores; the
;; boundary decodes it back to the identical sentence.

(def ^:private OPEN   -1)
(def ^:private CLOSE  -2)
(def ^:private VOPEN  -3)
(def ^:private VCLOSE -4)

(defn- enc! [^ArrayList out dict x]
  (cond
    (vector? x)     (do (.add out (int VOPEN))
                        (doseq [e x] (enc! out dict e))
                        (.add out (int VCLOSE)))
    (sequential? x) (do (.add out (int OPEN))
                        (doseq [e x] (enc! out dict e))
                        (.add out (int CLOSE)))
    (map? x)        (do (.add out (int VOPEN))                ; varmap: flatten k/v pairs
                        (doseq [[k v] x] (enc! out dict k) (enc! out dict v))
                        (.add out (int VCLOSE)))
    :else           (.add out (int (tok/intern-token! dict x)))))

(defn- encode ^ints [dict x]
  (let [out (ArrayList.)]
    (enc! out dict x)
    (let [n (.size out) a (int-array n)]
      (dotimes [i n] (aset a i (int (.get out i))))
      a)))

(defn- zigzag ^long [^long v] (bit-xor (bit-shift-left v 1) (bit-shift-right v 63)))

(defn- varint-bytes
  "The same int sequence as a varint `byte[]` — ids are small and dense (they count up
  from 0), so most tokens fit in one or two bytes where the `int[]` always spends four."
  ^bytes [^ints a]
  (let [out (java.io.ByteArrayOutputStream. (* 2 (alength a)))]
    (dotimes [i (alength a)]
      (loop [v (zigzag (aget a i))]
        (if (zero? (bit-and v (bit-not 0x7f)))
          (.write out (int v))
          (do (.write out (int (bit-or (bit-and v 0x7f) 0x80)))
              (recur (unsigned-bit-shift-right v 7))))))
    (.toByteArray out)))

(def ^:private body-keys [:sentence :context :antecedent :consequent :varmap])

(defn- dense-twin
  "`rec` with every s-expression field replaced by its encoded body — the form a dense
  record store would freeze.  `enc` turns the int sequence into the stored value."
  [dict enc rec]
  (reduce (fn [r k]
            (if (contains? rec k) (assoc r k (enc (encode dict (get rec k)))) r))
          rec body-keys))

;; ---- measurement --------------------------------------------------------

(defn- mb [b] (/ (double b) 1048576.0))

(defn- frozen-bytes ^long [xs]
  (reduce (fn [^long acc x] (+ acc (alength ^bytes (nippy/freeze x)))) 0 xs))

(defn- measure-frozen [recs]
  (let [n        (count recs)
        as-is    (frozen-bytes recs)
        dict     (tok/token-dict)
        ints     (mapv #(dense-twin dict identity %) recs)
        int-b    (frozen-bytes ints)
        varint   (mapv #(dense-twin dict varint-bytes %) recs)
        var-b    (frozen-bytes varint)
        dict-ram (postings/retained [dict])]
    (println "\n══ Phase 4.1: record frozen bytes — symbols vs int-id bodies ══")
    (printf  "  %-34s %10s %10s %8s\n" "frozen form" "MB" "B/record" "shrink")
    (println (str "  " (apply str (repeat 66 \-))))
    (printf  "  %-34s %10.1f %10.1f %8s\n" "as-is (symbol names per frame)"
             (mb as-is) (/ (double as-is) n) "—")
    (printf  "  %-34s %10.1f %10.1f %7.2f×\n" "int[] prefix encoding"
             (mb int-b) (/ (double int-b) n) (/ (double as-is) int-b))
    (printf  "  %-34s %10.1f %10.1f %7.2f×\n" "varint byte[] encoding"
             (mb var-b) (/ (double var-b) n) (/ (double as-is) var-b))
    (printf  "  dictionary: %,d tokens, %.1f MB RAM (the durable ground truth the ids decode through)\n"
             (tok/token-count dict) (mb dict-ram))
    ;; attribution: a nippy frame spends bytes on the record's *scaffolding* (the record
    ;; type tag and every field name, per frame) as well as on the sentence.  Only the
    ;; second is what an int-id body can shrink, so measure the split before believing
    ;; any body encoding can move the total.
    (let [gutted (mapv #(reduce (fn [r k] (if (contains? r k) (assoc r k nil) r)) % body-keys) recs)
          scaf   (frozen-bytes gutted)]
      (printf "  ─ of which scaffolding (record tag + field names, per frame): %.1f MB (%.0f B/record, %.0f%%)\n"
              (mb scaf) (/ (double scaf) n) (* 100.0 (/ (double scaf) as-is)))
      (printf "  ─ of which the s-expression bodies:                          %.1f MB (%.0f B/record, %.0f%%)\n"
              (mb (- as-is scaf)) (/ (double (- as-is scaf)) n)
              (* 100.0 (/ (double (- as-is scaf)) as-is)))
      (printf "  → a body encoding can only address the second: best case %.1f MB → %.1f MB (%.2f×)\n"
              (mb as-is) (mb (+ scaf (- var-b scaf))) (/ (double as-is) var-b)))
    {:as-is as-is :int int-b :varint var-b :dict-tokens (tok/token-count dict)}))

(defn- measure-fetch-attribution
  "Where the ~7 µs of a warm `get-sentex` goes.  Batching can only amortize the lock and
  the seek; a cache is the only thing that can skip the thaw."
  [store ids]
  (let [k     (:sentexes (:kinds store))
        probe (vec (take 40000 ids))
        t     (fn [f] (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6)))
        slots (mapv #(files/read-slot (:idx k) %) probe)
        raw   (mapv (fn [s] (let [^java.io.RandomAccessFile log (:log k)]
                              (.seek log (long (:offset s)))
                              (let [n (.readInt log) bs (byte-array n)]
                                (.readFully log bs) bs)))
                    slots)
        n     (count probe)
        t-slot (t #(doseq [id probe] (files/read-slot (:idx k) id)))
        t-read (t #(doseq [s slots] (files/read-record-sized (:log k) (:offset s) (:length s))))
        t-thaw (t #(doseq [bs raw] (nippy/thaw ^bytes bs)))
        t-full (t #(doseq [id probe] (p/get-sentex store id)))
        us    (fn [x] (* 1000.0 (/ (double x) n)))]
    (println "\n══ Phase 4.2b: where a warm fetch's time goes (µs/record) ══")
    (printf  "  slot read (one positional read of 24 B)  %6.2f µs  %4.0f%%\n" (us t-slot) (* 100.0 (/ t-slot t-full)))
    (printf  "  frame read + thaw (one sized read)       %6.2f µs  %4.0f%%\n" (us t-read) (* 100.0 (/ t-read t-full)))
    (printf  "  ─ of which nippy thaw                    %6.2f µs  %4.0f%%\n" (us t-thaw) (* 100.0 (/ t-thaw t-full)))
    (printf  "  ── full get-sentex                       %6.2f µs\n" (us t-full))
    (println "  batching can amortize the reads only; a hot cache skips all of it.")
    ;; --- the slot-read shapes, measured against each other -----------------
    ;; A slot read is 24 bytes, and how it is spelled dominates a warm fetch.  These
    ;; rows keep the comparison the build decision rests on: `.length` + `.seek` +
    ;; four primitive reads on an *unbuffered* RAF (six syscalls, and 52% of a warm
    ;; fetch), a `readFully` into a 24-byte buffer (two), and the positional channel
    ;; read `files/read-slot` uses (one, and it never moves the shared file pointer).
    (let [^java.io.RandomAccessFile idx (:idx k)
          ^java.io.RandomAccessFile log (:log k)
          ich   (.getChannel idx)
          lch   (.getChannel log)
          buf   (byte-array 24)
          bb    (java.nio.ByteBuffer/wrap buf)
          nbb   (java.nio.ByteBuffer/allocate 24)
          t-1sys (t #(doseq [id probe]
                       (.seek idx (* 24 (long id)))
                       (.readFully idx buf)
                       (.getLong bb 0) (.getLong bb 8)))
          t-pos  (t #(doseq [id probe]
                       (.clear nbb)
                       (.read ich nbb (* 24 (long id)))
                       (.getLong nbb 0) (.getLong nbb 8)))
          t-frame1 (t #(doseq [s slots]
                         (let [len (+ 4 (long (:length s)))
                               fb  (java.nio.ByteBuffer/allocate len)]
                           (.read lch fb (long (:offset s))))))]
      (printf "  ── slot via one readFully(24)            %6.2f µs\n" (us t-1sys))
      (printf "  ── slot via positional channel read      %6.2f µs   (what shipped)\n" (us t-pos))
      (printf "  ── frame payload via one positional read %6.2f µs   (what shipped, minus the thaw)\n"
              (us t-frame1)))))

(defn- measure-frame-codec
  "The other half of the frozen-byte question the attribution raises: 56%% of a frame is
  *scaffolding* — nippy writes the record's type tag and every field name into every
  single frame.  A **positional** frame (a plain vector, fields by position) pays
  that once in the code instead of once per record, and needs no dictionary at all."
  [recs]
  (let [n     (count recs)
        as-is (frozen-bytes recs)
        rule? #(some? (:antecedent %))
        posv  (mapv (fn [r]
                      (if (rule? r)
                        [1 (:sentence r) (:context r) (:truth r) (:strength r)
                         (:antecedent r) (:consequent r) (:varmap r) (:direction r)
                         (:defeasible r) (:assumption r)]
                        [0 (:sentence r) (:context r) (:truth r) (:strength r)]))
                    recs)
        pos-b (frozen-bytes posv)
        dict  (tok/token-dict)
        both  (mapv (fn [v] (mapv #(if (or (sequential? %) (map? %))
                                     (varint-bytes (encode dict %)) %) v))
                    posv)
        both-b (frozen-bytes both)]
    (println "\n══ Phase 4.1b: the frame's scaffolding — a positional codec ══")
    (printf  "  %-38s %8.1f MB %8.1f B/rec %7s\n" "record frames (type tag + field names)"
             (mb as-is) (/ (double as-is) n) "—")
    (printf  "  %-38s %8.1f MB %8.1f B/rec %6.2f×\n" "positional frames (fields by position)"
             (mb pos-b) (/ (double pos-b) n) (/ (double as-is) pos-b))
    (printf  "  %-38s %8.1f MB %8.1f B/rec %6.2f×\n" "positional + varint int-id bodies"
             (mb both-b) (/ (double both-b) n) (/ (double as-is) both-b))
    (println "  the positional half needs no dictionary and no decode step; the body half needs both.")))

;; ---- fetch cost ---------------------------------------------------------

(defn- scratch-dir
  ([] (scratch-dir ""))
  ([suffix]
   (let [d (str (System/getProperty "java.io.tmpdir") "/vaelii-records" suffix)]
     (doseq [f (reverse (file-seq (java.io.File. d)))] (.delete ^java.io.File f))
     d)))

(defn- build-disk-store
  "A scratch store holding `recs`, with the hot cache **off** — the per-fetch numbers
  below are the honest cost of reaching disk, not a partly-cached average."
  [recs]
  (let [dir   (scratch-dir)
        store (drs/open-record-store dir {:cache-capacity 0})]
    (p/clear-records! store)
    ;; `assoc :id nil`, never `dissoc :id` — dissoc'ing a defrecord's own field returns a
    ;; plain map, and the store would then write map frames the codec has no shape for
    (let [t0  (System/nanoTime)
          ids (mapv (fn [r] (p/put-sentex store (assoc r :id nil))) recs)
          ld  (/ (- (System/nanoTime) t0) 1e6)]
      (drs/fsync store)
      [store dir (vec ids) ld])))

(defn- ms [^long t0] (/ (- (System/nanoTime) t0) 1e6))

(defn- fetch-per-handle [store ids]
  (let [t0 (System/nanoTime)
        n  (reduce (fn [^long acc id] (if (p/get-sentex store id) (inc acc) acc)) 0 ids)]
    [(ms t0) n]))

(defn- batch-prototype
  "The `get-many` shape, prototyped here so the measurement decides whether to build it:
  one lock acquisition for the whole batch, and the handles read in **offset order** so
  the RAF walks the log forward instead of seeking back and forth."
  [store ids]
  (let [k (:sentexes (:kinds store))]
    ;; `k` is read out of the store, so `(:lock k)` is the store's lock and is shared by
    ;; every caller — the local binding is what the linter sees, not what it locks on.
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (:lock k)
      (let [slots (keep (fn [id]
                          (when-let [s (files/read-slot (:idx k) id)]
                            (when-not (:tombstone? s) [(:offset s) id])))
                        ids)]
        (persistent!
         (reduce (fn [m [off id]]
                   (if-let [r (files/read-record (:log k) off)] (assoc! m id r) m))
                 (transient {}) (sort-by first slots)))))))

(defn- fetch-batched [store ids]
  (let [t0 (System/nanoTime)
        n  (reduce (fn [^long acc batch] (+ acc (count (batch-prototype store batch))))
                   0 (partition-all 64 ids))]
    [(ms t0) n]))

(defn- measure-fetch [recs frozen]
  (let [[store dir ids load-ms] (build-disk-store recs)
        ;; the end-to-end number the isolated frozen-byte rows only predict: what the
        ;; sentex log actually weighs once the codec has written every frame
        log-b (files/log-length (:log (:sentexes (:kinds store))))
        raw-b (+ (long (:as-is frozen)) (* 4 (count recs)))]  ; the record frames + prefixes
    (println "\n══ Phase 4.1c: the sentexes log as written ══")
    (printf  "  record frames would be   %8.1f MB (%.1f B/record, incl. the 4-byte prefix)\n"
             (mb raw-b) (/ (double raw-b) (count recs)))
    (printf  "  positional frames are    %8.1f MB (%.1f B/record)   %.2f×\n"
             (mb log-b) (/ (double log-b) (count recs)) (/ (double raw-b) log-b))
    (let [rng   (java.util.Random. 20260724)
          k     (min 100000 (count ids))
          ;; uniform draw — the cold-ish case (no locality to exploit)
          unif  (vec (repeatedly k #(nth ids (.nextInt rng (count ids)))))
          ;; Zipfian draw — a real query stream (hot predicates/individuals)
          cum   (u/zipf-cumulative (count ids) 1.1)
          zipf  (vec (repeatedly k #(nth ids (u/zipf-sample cum rng))))]
      (dotimes [_ 2] (fetch-per-handle store (take 5000 unif)))  ; warm the page cache + JIT
      (let [[p1 n1] (fetch-per-handle store unif)
            [b1 n2] (fetch-batched    store unif)
            [p2 _]  (fetch-per-handle store zipf)
            [b2 _]  (fetch-batched    store zipf)]
        (println "\n══ Phase 4.2: disk record fetch — per-handle vs batched ══")
        (printf  "  %,d fetches over %,d records (page cache warm)\n" k (count ids))
        (printf  "  %-28s %10s %12s %8s\n" "access pattern" "per-handle" "batched(64)" "speedup")
        (println (str "  " (apply str (repeat 62 \-))))
        (printf  "  %-28s %8.0f ms %10.0f ms %7.2f×\n" "uniform" p1 b1 (/ p1 b1))
        (printf  "  %-28s %8.0f ms %10.0f ms %7.2f×\n" "zipfian (s=1.1)" p2 b2 (/ p2 b2))
        (printf  "  (%,d / %,d records found — parity check)\n" n1 n2)
        ;; LRU: what fraction of a zipfian stream a bounded hot cache would serve
        (doseq [cap [1000 10000 100000]]
          (let [lru  (java.util.LinkedHashMap. 16 0.75 true)
                hits (reduce (fn [^long h id]
                               (if (.get lru id)
                                 (inc h)
                                 (do (.put lru id Boolean/TRUE)
                                     (when (> (.size lru) (int cap))
                                       (.remove lru (.next (.iterator (.keySet lru)))))
                                     h)))
                             0 zipf)]
            (printf "  LRU cap %,8d → %5.1f%% hit rate on the zipfian stream\n"
                    cap (* 100.0 (/ (double hits) k)))))
        (measure-fetch-attribution store unif)
        ;; and the cache as it actually ships: a second store over the same files, with
        ;; the per-kind LRU on.  Same handle stream, so the delta is the cache alone.
        (doseq [cap [1000 10000 65536]]
          (let [hot (drs/open-record-store dir {:cache-capacity cap})]
            (fetch-per-handle hot (take 5000 zipf))              ; warm the LRU
            (let [[z _] (fetch-per-handle hot zipf)]
              (printf "  hot cache cap %,6d → zipfian stream %6.0f ms (%.2f× the uncached %.0f ms)\n"
                      cap z (/ p2 z) p2))
            (drs/close! hot)))
        (drs/close! store)
        [log-b load-ms]))))

;; ---- tokenized bodies, as the store actually writes them ----------------

(defn- measure-tokenized
  "The same records through a store with `:tokenize? true`: the log it writes, the
  dictionary it needs to decode them, and what a fetch costs once a body has to be
  varint-decoded rather than thawed whole."
  [recs plain-log-b plain-load-ms]
  (let [dir   (scratch-dir "-tok")
        store (drs/open-record-store dir {:cache-capacity 0 :tokenize? true})
        _     (p/clear-records! store)
        t0    (System/nanoTime)
        ids   (mapv (fn [r] (p/put-sentex store (assoc r :id nil))) recs)
        load  (ms t0)
        _     (drs/fsync store)
        log-b (files/log-length (:log (:sentexes (:kinds store))))
        dic-b (files/log-length (:log (:dict store)))
        n     (count recs)
        rng   (java.util.Random. 20260724)
        probe (vec (repeatedly (min 100000 n) #(nth ids (.nextInt rng (count ids)))))]
    (dotimes [_ 2] (fetch-per-handle store (take 5000 probe)))
    (let [[t _] (fetch-per-handle store probe)]
      (println "\n══ Phase 4.1d: tokenized bodies (opt-in) ══")
      (printf  "  positional log   %8.1f MB (%.1f B/record)\n" (mb plain-log-b) (/ (double plain-log-b) n))
      (printf  "  tokenized log    %8.1f MB (%.1f B/record)   %.2f×\n"
               (mb log-b) (/ (double log-b) n) (/ (double plain-log-b) log-b))
      (printf  "  + its dictionary %8.1f MB (%,d tokens, written once — not per frame)\n"
               (mb dic-b) (dtok/token-count (:dict store)))
      (printf  "  → records on disk%8.1f MB → %.1f MB   %.2f× all in\n"
               (mb plain-log-b) (mb (+ log-b dic-b)) (/ (double plain-log-b) (+ log-b dic-b)))
      (printf  "  uncached fetch over %,d handles: %.0f ms (%.2f µs/record)\n"
               (count probe) t (* 1000.0 (/ t (count probe))))
      (printf  "  load: %.1f s vs %.1f s positional (%.0f%% — the encode, not fsyncs: the\n"
               (/ load 1000.0) (/ (double plain-load-ms) 1000.0)
               (* 100.0 (dec (/ load (double plain-load-ms)))))
      (println "        dictionary is ordered by fsync rather than fsynced per token)")
      ;; the caveat that decides how to read the dictionary row: a UNIFORM sample of a
      ;; corpus deliberately breaks locality, so it sees each term about once and the
      ;; dictionary is corpus-sized while the records are sample-sized.  On the whole
      ;; store the same vocabulary amortizes over ~56× more records.
      (printf  "  NOTE: %,d tokens over %,d sampled records is %.2f tokens/record — a uniform\n"
               (dtok/token-count (:dict store)) n
               (/ (double (dtok/token-count (:dict store))) n))
      (println "        sample has almost no vocabulary reuse, so the dictionary row here is a")
      (println "        worst case; the log row is the one that carries over."))
    (drs/close! store)))

;; ---- RAM context --------------------------------------------------------

(defn- measure-ram [recs]
  (let [k (kb/open-kb {:backend :memory :space 22 :recover? false}
                      (fn [_] nil) (fn [_] nil))]
    (p/clear-records! (:records k)) (p/clear-index! (:index k))
    (doseq [r recs] (try (kb/create-sentex k (:sentence r) (:context r)) (catch Exception _ nil)))
    (let [recs-b (postings/retained [(:records k)])
          n      (count (p/sentex-ids (:records k)))
          dict   (tok/token-dict)
          _      (doseq [r recs] (encode dict (:sentence r)) (encode dict (:context r)))
          toks-b (postings/retained (into [] (remove nil?)
                                          (map #(tok/id-token dict %) (range (tok/token-count dict)))))]
      (println "\n══ Phase 4.3: record RAM (the :memory backend) — what int-id bodies would release ══")
      (printf  "  record store           %8.1f MB   (%,d sentexes, %.0f B/record)\n"
               (mb recs-b) n (/ (double recs-b) (max 1 n)))
      (printf  "  the token objects in them %6.1f MB   (%,d distinct — shared with the columnar dictionary)\n"
               (mb toks-b) (tok/token-count dict))
      (printf  "  → structure (list cells + record headers) %.1f MB is the rest; interning cannot touch it,\n"
               (mb (- recs-b toks-b)))
      (println "    an int[] body can."))))

(defn -main [& args]
  (let [n     (or (some-> (first args) Long/parseLong) 200000)
        dir   (or (second args) survey/default-dir)
        _     (survey/ensure-store! dir n)
        recs  (survey/uniform-records dir n)]
    (printf  "vaelii Phase-4 record measurement — %,d real records (uniform sample)\n" (count recs))
    (println "Density (jol retained heap) and frozen bytes are TRUSTED; wall-clock is indicative.")
    (let [frozen (measure-frozen recs)]
      (measure-frame-codec recs)
      (apply measure-tokenized recs (measure-fetch recs frozen)))
    (measure-ram recs)
    (shutdown-agents)))
