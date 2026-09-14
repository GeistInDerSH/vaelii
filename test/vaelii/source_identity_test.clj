;; SPDX-License-Identifier: SSPL-1.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.source-identity-test
  "The source identity (`vaelii.impl.source-identity`): the digest a belief image is
  stamped with.  An edit to prose leaves it unchanged, an edit to code or to compiled
  metadata changes it, two reads of one file agree, and the closure it covers reaches the
  namespaces recovery runs — including the ones only a quoted symbol names."
  (:require [clojure.test :refer [deftest is testing]]
            [vaelii.impl.source-identity :as si]))

(def ^:private base
  "(ns demo.x
     \"The namespace docstring.\"
     (:require [clojure.string :as str]))

   ;; a comment
   (defn f
     \"The docstring.\"
     [x]
     (inc x))

   (defprotocol P
     \"Protocol doc.\"
     (m [this] \"Method doc.\"))

   (def c \"Doc of c.\" 3)")

(deftest prose-edits-leave-the-digest-unchanged
  (let [d (si/forms-digest base)]
    (testing "comments, docstrings, blank lines and (comment …) blocks"
      (is (= d (si/forms-digest
                (str "\n\n;; a new comment\n"
                     (-> base
                         (.replace "The docstring." "A different docstring, longer.")
                         (.replace "The namespace docstring." "Reworded.")
                         (.replace "Protocol doc." "Reworded protocol doc.")
                         (.replace "Method doc." "Reworded method doc.")
                         (.replace "Doc of c." "Reworded doc of c."))
                     "\n(comment (f 1) (f 2))\n")))))
    (testing "a form moved down by inserted lines keeps its digest"
      (is (= d (si/forms-digest (.replace base "(defn f" "\n\n\n(defn f")))))))

(deftest code-and-compiled-metadata-edits-change-the-digest
  (let [d (si/forms-digest base)]
    (is (not= d (si/forms-digest (.replace base "(inc x)" "(dec x)"))) "a body edit")
    (is (not= d (si/forms-digest (.replace base "(def c \"Doc of c.\" 3)" "(def c \"Doc of c.\" 4)")))
        "a def's value")
    (is (not= d (si/forms-digest (.replace base "(def c" "(def ^:dynamic c")))
        "metadata the compiler reads")
    (is (not= d (si/forms-digest (.replace base "[x]" "[^long x]"))) "a type hint")
    (is (not= d (si/forms-digest (.replace base "(def c \"Doc of c.\" 3)" "(def c \"Doc of c.\")")))
        "a def whose only string is its value keeps the string")))

(deftest reader-minted-names-hash-alike-across-reads
  (testing "auto-gensyms and anonymous-fn arguments are renumbered in order of appearance"
    (is (= (si/forms-digest "(defmacro m [x] `(let [y# ~x] (map #(+ % y#) [1 2])))")
           (si/forms-digest "(defmacro m [x] `(let [w# ~x] (map #(+ % w#) [1 2])))"))
        "two spellings of one auto-gensym"))
  (testing "while the order of two minted names still counts"
    (is (not= (si/forms-digest "(defmacro m [] `(let [a# 1 b# 2] [a# b#]))")
              (si/forms-digest "(defmacro m [] `(let [a# 1 b# 2] [b# a#]))")))))

(deftest the-closure-reaches-what-recovery-runs
  (let [c (si/closure)]
    (is (contains? c 'vaelii.impl.recovery))
    (is (contains? c 'vaelii.impl.settle))
    (is (contains? c 'vaelii.core) "reached through wiring's quoted entry points")
    (is (contains? c 'vaelii.impl.dense-jtms)
        "named only by kb's quoted requiring-resolve and by imports")
    (is (not (contains? c 'vaelii.impl.seal))
        "a namespace that requires the belief image and that nothing on the path requires")
    (is (every? #(or (= 'vaelii.core %) (.startsWith (str %) "vaelii.impl.")) c)
        "the walk never leaves the engine")))

(deftest a-namespace-with-only-compiled-code-contributes-its-container
  (testing "clojure.core's __init class loads from the clojure jar"
    (let [a (#'si/compiled-bytes 'clojure.core (volatile! {}))
          b (#'si/compiled-bytes 'clojure.core (volatile! {}))]
      (is (some? a))
      (is (java.util.Arrays/equals ^bytes a ^bytes b))))
  (is (nil? (#'si/compiled-bytes 'vaelii.no-such-namespace (volatile! {})))
      "a namespace with neither source nor compiled code contributes nothing"))

(deftest the-identity-is-stable-and-names-its-libraries
  (let [a (si/source-identity) b (si/source-identity)]
    (is (= a b))
    (is (= (:namespaces a) (count (si/closure))))
    (is (some #(.startsWith ^String % "nippy-") (:libraries a)) "nippy's jar, with its version")
    (is (some #(.startsWith ^String % "clojure-") (:libraries a)) "clojure's jar")))
