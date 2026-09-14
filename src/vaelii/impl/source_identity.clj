;; SPDX-License-Identifier: SSPL-1.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.impl.source-identity
  "The **source identity**: a digest of the engine source that derives belief.  A belief
  image is stamped with it, and an open installs the image only when its own source
  identity is equal, so an image is never installed by code that would derive a different
  belief from the same records.

  ## Which files

  The digest covers the transitive closure of `roots` over three kinds of edge, each read
  from the source files themselves rather than from the loaded namespaces:

  - the `ns` form's `:require`, `:use` and `:import` clauses — an imported deftype or
    defrecord class names the namespace that defines it;
  - every fully-qualified symbol under a `quote` in code.  These are the edges no `ns`
    form states: the `requiring-resolve` targets and the fixed symbol tables that name
    them (`vaelii.core`'s calculi, reasoners and solvers, `vaelii.impl.imperative`'s `do/`
    handlers, `vaelii.impl.wiring`'s three entry points).

  The walk follows `vaelii.core` and `vaelii.impl.*` and nothing else.  Code from outside
  those prefixes — a registered prover or evaluatable, a foreign plugin — is not in the
  digest; `vaelii.impl.belief-image` refuses to write or install an image for a KB that
  runs any.

  ## What is hashed

  Each file is read as forms, and the forms are hashed with four things removed: comments,
  `(comment …)` blocks, docstrings, and the reader's position metadata.  An edit that
  changes only prose therefore leaves the digest unchanged.  An edit to code, or to
  metadata the compiler reads (`^:dynamic`, `^:const`, a type hint), changes it.

  The reader mints a fresh name on every read for two kinds of symbol: a syntax-quote
  auto-gensym (`x#`) and an anonymous-fn argument (`%`).  Both are renumbered in order of
  first appearance within their top-level form, so two reads of one file hash alike.
  Aliases, classes and vars are read through a `*reader-resolver*` that resolves each to
  itself, so a file reads the same way whether or not its namespace is loaded.

  ## A namespace with no source

  The Clojars jar, the uberjar and a source checkout all carry the `.clj` files, so every
  namespace is normally read as forms.  A namespace on the classpath only as compiled
  classes contributes the digest of the code it loads from instead: the whole jar its
  `__init` class sits in, or every class file of the namespace in a class directory.  A
  namespace compiles to one class per fn, so no single class file covers its code.  The
  jar digest changes with any rebuild, which is conservative: an image is discarded more
  often, never installed under different code.

  ## Libraries

  Each third-party library the closure requires or imports contributes the file name of
  the jar its code loads from (`nippy-3.5.0.jar`), and the file name carries the version.
  A library loaded from a directory rather than a jar contributes its namespace or class
  name.  JDK classes contribute nothing.

  ## The parse memo

  The parse of a file — its forms digest and its edges — is memoized per file, in the
  `:source-parses` cache.  Every call takes each file's stat, its modification time and
  length.  A file whose stat is unchanged since its last read is not read again; any other
  file is read and its bytes hashed, and parsed only when the SHA-256 of its bytes
  changed.  An edit that leaves both the modification time and the length unchanged can
  land only within the file system's timestamp resolution of the read before it, so a
  file modified within `stat-window-ms` before its read is read again at the next call.
  The digest therefore describes the files as they stand at the call, and an edit made at
  a REPL between two calls reaches it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [vaelii.impl.caches :as caches])
  (:import [java.io File PushbackReader StringReader]
           [java.net URL]
           [java.nio.file Files]
           [java.security MessageDigest]))

(def roots
  "The namespaces the closure starts from: recovery, which derives belief on open, and the
  public API, whose write entry points derive it between opens."
  '[vaelii.core vaelii.impl.recovery])

(defn- in-scope?
  "Is `ns-sym` a namespace the digest follows?"
  [ns-sym]
  (let [s (str ns-sym)]
    (or (= s "vaelii.core") (str/starts-with? s "vaelii.impl."))))

(defn- ns-path ^String [ns-sym]
  (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")))

(defn- source-url
  "The classpath URL of `ns-sym`'s source file, or nil when none is on the classpath."
  ^URL [ns-sym]
  (let [p (ns-path ns-sym)]
    (or (io/resource (str p ".clj")) (io/resource (str p ".cljc")))))

;; ---- reading ----------------------------------------------------------------

(def ^:private resolver
  (reify clojure.lang.LispReader$Resolver
    (currentNS [_] 'vaelii.source)
    (resolveClass [_ s] s)
    (resolveAlias [_ s] s)
    (resolveVar [_ s] s)))

(defn read-forms
  "The top-level forms of the source `text`, without `(comment …)` blocks.  Reads with
  `*read-eval*` off and the self-resolving `*reader-resolver*`."
  [^String text]
  (with-open [r (PushbackReader. (StringReader. text))]
    (binding [*read-eval* false
              *reader-resolver* resolver]
      (into []
            (comp (take-while #(not= ::eof %))
                  (remove #(and (seq? %) (= 'comment (first %)))))
            (repeatedly #(read {:eof ::eof :read-cond :allow} r))))))

;; ---- the forms digest -------------------------------------------------------

(def ^:private dropped-meta
  "The reader's position keys, plus `:doc`."
  #{:line :column :end-line :end-column :file :source :doc})

(def ^:private doc-heads
  "The forms whose third element is a docstring when more elements follow it."
  '#{ns defn defn- defmacro defmulti defprotocol definterface defonce})

(defn- strip-doc
  "`form` without its docstring, when `form` is a def-like form that carries one.  A
  `defprotocol` also loses the docstring trailing each method signature."
  [form]
  (if-not (and (seq? form) (symbol? (first form)))
    form
    (let [h (first form)
          v (vec form)
          v (cond
              (and (doc-heads h) (> (count v) 3) (string? (v 2))) (into (subvec v 0 2) (subvec v 3))
              (and (= 'def h) (= 4 (count v)) (string? (v 2)))    [(v 0) (v 1) (v 3)]
              :else                                             v)
          v (if (= 'defprotocol h)
              (mapv (fn [x] (if (and (seq? x) (vector? (second x)) (string? (last x)))
                              (apply list (butlast x))
                              x))
                    v)
              v)]
      (with-meta (apply list v) (meta form)))))

(def ^:private minted
  "A symbol the reader mints per read: a syntax-quote auto-gensym or an anonymous-fn
  argument."
  #"^(.*?)(__\d+__auto__|p\d+__\d+#|rest__\d+#)$")

(defn- renumber
  "`form` with each reader-minted symbol replaced by its order of first appearance.  The
  replacement keeps no part of the minted name: an anonymous-fn argument inside a
  syntax-quote is minted twice (`p1__1235__1236__auto__`), so any prefix of the name can
  still hold a counter."
  [form]
  (let [seen (volatile! {})]
    (walk/postwalk
     (fn [x]
       (if (and (symbol? x) (nil? (namespace x)) (re-matches minted (name x)))
         (or (get @seen x)
             (let [s (symbol (str "minted__" (count @seen)))]
               (vswap! seen assoc x s)
               s))
         x))
     form)))

(defn- canon
  "`form` as plain data: docstrings removed, and each object's metadata, less the keys in
  `dropped-meta`, written out as `[:meta m x]` so the printer includes it."
  [form]
  (walk/postwalk
   (fn [x]
     (let [x (strip-doc x)
           m (when (instance? clojure.lang.IObj x) (apply dissoc (meta x) dropped-meta))]
       (if (seq m) [:meta m (with-meta x nil)] x)))
   (renumber form)))

(defn- sha256 ^MessageDigest [] (MessageDigest/getInstance "SHA-256"))

(defn- hex ^String [^bytes b]
  (let [sb (StringBuilder.)]
    (doseq [x b] (.append sb (format "%02x" (bit-and 0xff (long x)))))
    (str sb)))

(defn- forms-bytes
  "The digest of already-read `forms`, as bytes."
  ^bytes [forms]
  (let [md (sha256)
        s  (binding [*print-meta* false *print-length* nil *print-level* nil
                     *print-namespace-maps* false *print-dup* false]
             (pr-str (mapv canon forms)))]
    (.update md (.getBytes ^String s "UTF-8"))
    (.digest md)))

(defn forms-digest
  "The hex digest of the source `text` with its comments, docstrings and reader positions
  removed."
  ^String [^String text]
  (hex (forms-bytes (read-forms text))))

;; ---- the closure ------------------------------------------------------------

(defn- libspec-namespaces
  "The namespaces a `:require` / `:use` libspec names: a bare symbol, `[a.b :as x]`, or a
  prefix list `[a b [c :as x]]`."
  [spec]
  (cond
    (symbol? spec) [spec]
    (and (sequential? spec) (symbol? (first spec)))
    (let [[head & more] spec]
      (if (or (empty? more) (keyword? (first more)))
        [head]
        (for [m more
              :let [s (if (sequential? m) (first m) m)]
              :when (symbol? s)]
          (symbol (str head "." s)))))
    :else []))

(defn- import-classes
  "The class names an `:import` spec names: `a.b.C`, or `[a.b C D]` / `(a.b C D)`."
  [spec]
  (cond
    (symbol? spec)     [(str spec)]
    (sequential? spec) (let [[pkg & cs] spec] (map #(str pkg "." %) cs))
    :else              []))

(defn- ns-clauses [forms]
  (when-let [nsf (first (filter #(and (seq? %) (= 'ns (first %))) forms))]
    (filter seq? nsf)))

(defn- class-namespace
  "The in-scope namespace that defines class `cls`, when one does: a deftype or defrecord
  class lives in the munged package of its namespace."
  [^String cls]
  (let [i (.lastIndexOf cls ".")]
    (when (pos? i)
      (let [n (symbol (str/replace (subs cls 0 i) "_" "-"))]
        (when (and (in-scope? n) (source-url n)) n)))))

(defn- quoted-namespaces
  "The in-scope namespaces named by a fully-qualified symbol under a `quote` in `forms`."
  [forms]
  (let [acc (volatile! #{})]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x) (= 'quote (first x)))
         (walk/postwalk
          (fn [y]
            (when (and (symbol? y) (namespace y) (in-scope? (symbol (namespace y))))
              (vswap! acc conj (symbol (namespace y))))
            y)
          (second x)))
       x)
     forms)
    @acc))

(defn- edges
  "`{:reqs [ns …] :classes [cls …] :quoted #{ns …}}` — the namespaces `forms` requires,
  the classes it imports, and the in-scope namespaces a quoted symbol in it names.  Read
  from the forms alone, with no classpath lookup, so the parse memo can hold it."
  [forms]
  (let [clauses (ns-clauses forms)]
    {:reqs    (vec (for [c clauses :when (#{:require :use} (first c))
                         spec (rest c) n (libspec-namespaces spec)] n))
     :classes (vec (for [c clauses :when (= :import (first c))
                         spec (rest c) cls (import-classes spec)] cls))
     :quoted  (quoted-namespaces forms)}))

(defn- references
  "`{:namespaces #{…} :libraries #{…}}` — the in-scope namespaces a file's `edges` reach
  and the external namespaces and classes it requires or imports.  Resolves each imported
  class against the classpath at the call."
  [{:keys [reqs classes quoted]}]
  (let [by-cls (map (fn [c] [c (class-namespace c)]) classes)]
    {:namespaces (into quoted
                       (concat (filter in-scope? reqs) (keep second by-cls)))
     :libraries  (into #{}
                       (concat (map (fn [n] [:ns n]) (remove in-scope? reqs))
                               (keep (fn [[c n]] (when-not n [:class c])) by-cls)))}))

(defn- container-bytes
  "The digest of the compiled code class `url` loads from.  A namespace compiles to one
  class per fn beside its `__init` class, so no single class file covers its code: for a
  jar URL this is the whole jar, and for a directory URL every class file of namespace
  `stem` (`stem__init.class`, `stem$….class`) in name order."
  ^bytes [^URL url ^String stem]
  (let [md (sha256)]
    (case (.getProtocol url)
      "jar"  (let [p (.getPath url)
                   f (io/file (.toURI (URL. (subs p 0 (.indexOf p "!")))))]
               (.update md (Files/readAllBytes (.toPath f))))
      "file" (let [d (.getParentFile (io/file (.toURI url)))]
               (doseq [^File f (sort-by #(.getName ^File %) (.listFiles d))
                       :let [nm (.getName f)]
                       :when (or (= nm (str stem "__init.class"))
                                 (str/starts-with? nm (str stem "$")))]
                 (.update md (.getBytes nm "UTF-8"))
                 (.update md (Files/readAllBytes (.toPath f))))))
    (.digest md)))

(defn- compiled-bytes
  "For a namespace with no source on the classpath, the digest of the compiled code it
  loads from, or nil when no compiled class names it either.  `cache` is a volatile map
  shared across one walk, so a closure of namespaces from one jar reads the jar once."
  [ns-sym cache]
  (let [p (ns-path ns-sym)]
    (when-let [^URL u (io/resource (str p "__init.class"))]
      (let [stem (subs p (inc (.lastIndexOf p "/")))
            k    (if (= "jar" (.getProtocol u))
                   (let [path (.getPath u)] (subs path 0 (.indexOf path "!")))
                   (str u))]
        (or (get @cache k)
            (let [b (container-bytes u stem)] (vswap! cache assoc k b) b))))))

;; ---- the parse memo ---------------------------------------------------------

(def ^:private parse-memo-limit
  "Parsed files the memo holds before it is cleared wholesale.  The closure is 111
  namespaces today, so the bound leaves room for the versions a REPL session's edits add."
  1024)

(def ^:private stat-window-ms
  "How long after a file's modification time a read must happen for the file's stat to
  stand in for its bytes at the next call.  A file system records a modification time at a
  resolution of up to 2 s, so an edit landing within 2 s of the read before it can leave
  both the time and the length unchanged."
  2000)

;; `{url-string {:stat [modified length] :read-at ms :sha hex :bytes forms-digest
;; :edges edges}}`, one entry per source file.
(def ^:private parsed (atom {}))

(defn- url-bytes ^bytes [^URL url]
  (with-open [in (.openStream url)] (.readAllBytes in)))

(defn- file-stat
  "`[modified-ms length]` of the file `url` loads from — the jar itself for a `jar:` URL —
  or nil for any other protocol."
  [^URL url]
  (when-let [^File f (case (.getProtocol url)
                       "file" (io/file (.toURI url))
                       "jar"  (let [p (.getPath url)]
                                (io/file (.toURI (URL. (subs p 0 (.indexOf p "!"))))))
                       nil)]
    [(.lastModified f) (.length f)]))

(defn- parse-source
  "`{:bytes forms-digest :edges edges …}` for the source file at `url`.

  The memo entry stands without a read when the file's stat equals the stat taken before
  the entry's read, and the file's modification time is more than `stat-window-ms` older
  than that read.  Otherwise this reads the bytes and hashes them, and parses the forms
  only when the SHA-256 differs from the entry's."
  [^URL url]
  (let [k  (str url)
        st (file-stat url)
        e  (get @parsed k)]
    (if (and e st (= st (:stat e))
             (< (long (first st)) (- (long (:read-at e)) (long stat-window-ms))))
      e
      (let [read-at (System/currentTimeMillis)
            raw     (url-bytes url)
            sha     (hex (.digest (doto (sha256) (.update raw))))
            e       (assoc (if (= sha (:sha e))
                             e
                             (let [forms (read-forms (String. raw "UTF-8"))]
                               {:sha sha :bytes (forms-bytes forms) :edges (edges forms)}))
                           :stat st :read-at read-at)]
        (when (and (not (contains? @parsed k))
                   (>= (count @parsed) (long (caches/limit-of :source-parses parse-memo-limit))))
          (reset! parsed {}))
        (swap! parsed assoc k e)
        e))))

(caches/register-cache
 {:cache    :source-parses
  :label    "Source parses"
  :scope    :process
  :unit     "source files"
  :limit    (caches/limit-thunk :source-parses parse-memo-limit)
  :counters nil
  :note     (str "Each engine source file's forms digest and edges, keyed on the SHA-256 of "
                 "its bytes, so the source identity parses only a file whose bytes it has "
                 "not parsed before. Past the limit it is cleared wholesale.")
  :read     (fn [_] {:entries (count @parsed)})
  :trim     (fn [_ target] (caches/trim-map! parsed target))})

;; ---- the walk ---------------------------------------------------------------

(defn- walk-closure
  "`{ns-sym {:bytes digest :libraries #{…}}}` for every namespace the closure reaches.  A
  namespace with source contributes its forms digest and its edges; one with only compiled
  code contributes `compiled-bytes` and no edges, since the jar's digest already covers
  every namespace compiled into it."
  []
  (let [cache (volatile! {})]
    (loop [out {} todo (vec roots)]
      (if-let [n (peek todo)]
        (let [todo (pop todo)]
          (cond
            (contains? out n)
            (recur out todo)

            (source-url n)
            (let [{:keys [bytes edges]} (parse-source (source-url n))
                  {:keys [namespaces libraries]} (references edges)]
              (recur (assoc out n {:bytes bytes :libraries libraries})
                     (into todo (remove #(contains? out %)) namespaces)))

            :else
            (recur (if-let [b (compiled-bytes n cache)]
                     (assoc out n {:bytes b :libraries #{}})
                     out)
                   todo)))
        out))))

(defn closure
  "The sorted set of namespaces whose source the digest covers."
  []
  (into (sorted-set) (keys (walk-closure))))

;; ---- libraries --------------------------------------------------------------

(defn- library-url ^URL [[kind nm]]
  (case kind
    :ns    (let [p (ns-path nm)]
             (or (io/resource (str p ".clj")) (io/resource (str p "__init.class"))
                 (io/resource (str p ".cljc"))))
    :class (io/resource (str (str/replace nm "." "/") ".class"))))

(defn- library-name
  "The jar file name `lib` loads from, its own name when it loads from a directory, or nil
  for a JDK class and for a name nothing on the classpath provides."
  [[_ nm :as lib]]
  (when-let [u (library-url lib)]
    (case (.getProtocol u)
      "jar"  (let [p (.getPath u) bang (.indexOf p "!")]
               (subs p (inc (.lastIndexOf p "/" (int bang))) bang))
      "file" (str nm)
      nil)))

;; ---- the identity -----------------------------------------------------------

(defn source-identity
  "`{:digest hex :namespaces n :libraries [jar …]}` for the source on the classpath now.
  `:digest` covers the forms of every namespace in `closure`, each under its name, and the
  sorted library names."
  []
  (let [c    (walk-closure)
        libs (into (sorted-set) (keep library-name) (mapcat :libraries (vals c)))
        md   (sha256)]
    (doseq [[n {:keys [bytes]}] (sort-by key c)]
      (.update md (.getBytes (str n) "UTF-8"))
      (.update md ^bytes bytes))
    (doseq [l libs]
      (.update md (.getBytes (str l) "UTF-8")))
    {:digest     (hex (.digest md))
     :namespaces (count c)
     :libraries  (vec libs)}))
