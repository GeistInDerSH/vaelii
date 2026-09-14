;; SPDX-License-Identifier: SSPL-1.0
;; Copyright © 2026 Vaelii LLC and the Vaelii contributors.
(ns vaelii.impl.recovery
  "Build the in-memory JTMS and taxonomy for the persistent stores — the store-to-belief
  `recover`, which installs a belief image (`vaelii.impl.belief-image`) or rebuilds from
  the records and writes one.

  It sits just below `vaelii.core`, above `settle`, so the two callers that recover a store
  both reach it downward and neither reaches up: `vaelii.core` exposes it as the public
  `recover`, and `vaelii.impl.io.import` recovers the records a dump just landed
  (docs/namespaces.md, \"The layering\").  `recover` orchestrates the layers below —
  `rebuild-tms` over the JTMS, `special/rebuild-taxonomy`, the roster rebuilds in `kb`, and
  the closing `settle` — so the top of that orchestration lives here rather than inside any
  one of them.  The contract `recover` holds to is on `vaelii.core/recover`; docs/taxonomy.md
  and docs/nmtms.md carry the mechanism."
  (:require [taoensso.trove :as trove]
            [vaelii.impl.belief-image :as belief-image]
            [vaelii.impl.capabilities :as cap]
            [vaelii.impl.chain :as chain]
            [vaelii.impl.jtms :as jtms]
            [vaelii.impl.kb :as kb]
            [vaelii.impl.protocols :as p]
            [vaelii.impl.settle :as settle]
            [vaelii.impl.settle-phases :as phases]
            [vaelii.impl.special :as special]
            [vaelii.impl.taxonomy :as tax]))

(defn- recovered-supersessions
  "Every stored sentex the rebuilt equality closure displaces, as `refresh-supersessions`
  wants it.

  Recovery cannot read supersession back — it is derived from the closure, and recovery
  lands with the map empty (a fresh network holds none), exactly as it lands unblocked.
  Left that way, *both* spellings of every merged fact would be believed, which is a
  worse state than the merge simply being forgotten.  So the displaced sentexes are
  nominated once here and `supersession-map` filters them down to the ones whose twin
  is genuinely stored.

  Two sources, matching the two ways `rewrite-term` displaces a sentence: a **symbol
  merge** (walk the equality classes for every member's sentexes) and a **schematic
  rewrite** (a stored sentex the rule's LHS head reaches whose normal form differs).
  `supersession-map` re-derives the actual displacement — `rewrite-term` normalizes both
  — so this only has to name candidates.

  It names them by **class membership alone**, without asking whether the global
  election displaces the term.  Displacement is the *reader's*, and the global answer is
  not a superset of the scoped ones: a term can be the head of its whole class and still
  be retired inside a context whose visible edges elect someone else, when the
  `rewriteOf` that made it preferred is one that context cannot see.  Filtering here
  on the global read would drop exactly those, and recovery would come back believing
  both spellings."
  [kb]
  (concat
   (for [[a _] (tax/equality-edges (:taxonomy kb))
         t     (tax/equiv-class (:taxonomy kb) a)
         sx    (kb/find-sentexes kb t)
         :when (kb/rewritable-sentex? kb sx)]
     [(:id sx) {}])
   (for [{:keys [lhs]} (tax/rewrite-rules (:taxonomy kb))
         sx (kb/find-sentexes kb (first lhs))
         :when (kb/rewritable-sentex? kb sx)]
     [(:id sx) {}])))

(defn- rebuild-tms
  "Rebuild the network from the store: a node per stored sentex, a premise per rostered
  handle, and a justification per stored justification.  Belief is the composition of the
  region relabels the adds run, not a separate whole-graph pass.

  **No whole-graph relabel closes this.**  `add-justification` relabels its consequence's
  affected region as it lands, and a region relabel over the affected closure is equal to a
  global one (`jtms/relabel-region*`); premises are marked before any justification, so each
  add already reads its antecedents' final belief.  The region relabels therefore compose to
  the fixpoint a whole-graph `jtms/relabel` computes, and a global pass on top of them only
  recomputes what is already settled — measured at a third of `rebuild-tms` on a disk corpus
  (`scale-100m.md`, the recover decomposition's step 4).

  **And no reset of blocking or supersession either — recovery lands unblocked because it
  starts fresh.**  A network opened for recovery is empty, blocked and superseded sets
  included, so the region relabels above already label unblocked.  Neither is stored
  (docs/nmtms.md), so a rebuild cannot read either back; it need not clear them because the
  two are re-derived **wholesale** rather than merged — `recheck-every-exception` queues
  every exception-bearing rule and the settle *replaces* the blocked set (`jtms/set-blocked`),
  and `refresh-supersessions` replaces the supersession map — so not even a `core/reindex`
  over a live network can carry a stale one past the settle.

  **A justification naming a sentex this store does not hold is left out**, and this is
  the one path that can meet one.  Everywhere else a justification is built by a firing,
  whose antecedents are records the caller has in hand; here they are numbers off a
  store, and a store can hold a justification whose records are gone — `delete-sentex!`
  is on the protocol, and another dialect's loader is under no obligation to be
  consistent.  `add-justification` does not refuse one: the reference representation
  grows a phantom node for the missing datum and the dense one is not specified there
  (`vaelii.impl.dense-jtms`), and a justification *concluding* the phantom makes it IN —
  so the KB comes back believing a handle it cannot show anyone, and everything derived
  from it.  Skipped and counted instead, which is the policy `io.import` takes at the
  other end of the same store.

  A rule-handle informant is checked with the antecedents (`jtms/rests-on`): the network
  treats it as an implicit antecedent and lists the justification under its node, so a
  justification whose rule is gone is left out as one whose fact is gone would be."
  [kb]
  (let [tms     (:tms kb)
        rec     (:records kb)
        ;; `sentex-ids` is *the live handle set* by the RecordStore contract, and
        ;; `premise-ids` a subset of it — every backend's `mark-premise` guards on the
        ;; record existing before rostering the handle (memory.clj, disk record-store).
        ;; So neither loop re-reads the whole record to prove it is there: the node loop
        ;; trusts the set, and the premise loop tests membership in it (O(1), no fetch)
        ;; rather than fetching a frame per premise only to check for nil.  A fetch here
        ;; costs ~1 s of a 313k `recover` on disk and hours over a network store, all of
        ;; it to re-derive a fact the enumerator already answered.  A **justification** is
        ;; the one thing a store can hold over a sentex it does not (a `delete-sentex!`, a
        ;; foreign loader under no consistency obligation) — that loop keeps `stored?`.
        live    (p/sentex-ids rec)
        ;; The roster is checked first and the fetch is the fallback, not the test: a
        ;; store rosters a handle only once the record is stored, so membership in `live`
        ;; **is** storedness and the fetch below it can only confirm what the set already
        ;; said.  It stays for the handle the set does not name — the case this loop keeps
        ;; `stored?` for at all — where nil is the answer and a fetch is the only way to
        ;; it.  On a store whose fetch is a round trip that ordering is the difference
        ;; between one read of the roster and a trip per antecedent of every justification.
        stored? (fn [h] (or (not (integer? h))
                            (contains? live h)
                            (some? (p/get-sentex rec h))))
        skipped (volatile! 0)]
    ;; The replay is `settle-phases`' `:belief` centre for a `recover`: `add-justification`
    ;; relabels each consequence's region as it lands, and the region relabels compose to
    ;; the fixpoint (no closing whole-graph pass and no chaining), so this is the belief
    ;; work a partition-relabel candidate would divide.  Off a timing run, one `nil?` check.
    (phases/with-phase :belief
      (doseq [id live]
        (jtms/ensure-node tms id 0))
      (doseq [id (p/premise-ids rec) :when (contains? live id)]
        (jtms/add-premise tms id (p/premise-strength rec id)))
      ;; Every stored justification is fetched here, so a store that can warm many at one
      ;; cost is told a chunk ahead — ungated, as in `reindex`: this walk reads every handle
      ;; it is given.  Nil for a store without the capability, and then this is
      ;; `(p/justification-ids rec)`.
      (doseq [id (cap/hinting (cap/justification-prefetcher rec) cap/recovery-hint-chunk
                              (p/justification-ids rec))
              :let [d (p/get-justification rec id)] :when d]
        (if (and (stored? (:consequence d)) (every? stored? (jtms/rests-on d)))
          (jtms/add-justification tms d)
          (vswap! skipped inc))))
    (when (pos? (long @skipped))
      (trove/log! {:level :warn :id ::justifications-unrooted
                   :msg  (str @skipped " stored justifications name a sentex this store"
                              " does not hold and are left out of the network")
                   :data {:skipped @skipped}}))
    tms))

(defn- recover-from-records
  "Rebuild the in-memory JTMS and taxonomy from the persistent stores — what `recover`
  runs whenever it installs no belief image.

  What the taxonomy ends up holding is a **composition**, and the contract is the whole
  of it rather than either half.  The JTMS is rebuilt first, so there is belief to read.
  The taxonomy then replays every **stored** special-predicate sentex rather than the
  believed ones — `:support` must record every asserting sentex, or a disbelieved
  supporter would be lost and clearing its defeat could never revive the entry
  (docs/taxonomy.md) — so that replay over-reads by construction, and the reconcile
  against belief immediately after it is what narrows the caches to what the KB entails.
  Belief is settled last."
  [kb]
  ;; The scoped closure memo (`tax/*scoped-memo-budget*`) is sized for steady-state, whose
  ;; hot working set is a few recently-touched contexts.  A cold rebuild is the opposite:
  ;; it reads the whole corpus from every context at once — OpenCyc induces 561 vissets by
  ;; the budget's own census — so the default 128 flushes and re-walks `specs` closures
  ;; forever, which the clash pass then pays per membership.  Widen it for the rebuild so
  ;; the whole context set stays memoised; this is pure cache size (docstring: "a heap, not
  ;; a wrong answer"), and the cap only bounds retention, so the memory is the working set
  ;; either way — the 561 closures the walk computes regardless, kept instead of redone.
  (binding [tax/*scoped-memo-budget* (max (long tax/*scoped-memo-budget*) 8192)]
    (rebuild-tms kb)
    ;; The rebuild replays every stored `genl` / `genlCx` edge, so it is a bulk load
    ;; and pays what one pays: repairing the depth potential per edge costs that edge's
    ;; descendants.  Defer it and repair once, exactly as `with-deferred-settle` does —
    ;; and repair *here* rather than leaning on the settle below, so the intervening
    ;; rebuilds never read a loose relation.  The reconcile shares that one repair, which
    ;; is why it sits inside the same deferral: dropping an edge can dissolve a component.
    ;; `*defer-cycle-scc?*` extends the same deferral to a cycle-closing edge, which
    ;; `activate` otherwise repairs eagerly per edge (O(V+E) each) for a `placement-rep`
    ;; reader no recovery step runs before `restore-depths`; the single repair below
    ;; computes `:scc` for the whole replay instead (see the var).
    (binding [tax/*defer-depths?*    true
              tax/*defer-cycle-scc?* true]
      (special/rebuild-taxonomy kb)
      ;; Now narrow the replayed caches to belief, and **unconditionally**.  The
      ;; region-scoped arm of `refresh-beliefs` reconciles what a settle moved, and the
      ;; unsupported edge moves nothing: a record carrying no premise mark and no
      ;; justification is OUT from the moment `rebuild-tms` makes its node, so no defeat,
      ;; block or supersession ever names it and no region ever reaches it — while the
      ;; replay has already made it answer `genls`.  (The *defeated* edge is narrowed
      ;; either way, since its opposition is an event the settle reacts to.)  Recovery is
      ;; exactly the caller holding no region that the `nil` arm exists for, and it costs
      ;; one belief lookup per stored declaration — what the replay above just paid.
      ;; Before the settle rather than after it, so everything the settle reads — nogoods,
      ;; placement, exception queries — reads a taxonomy that already agrees with belief;
      ;; the settle's own reconcile then keeps the two together across whatever it moves.
      (tax/refresh-beliefs (:taxonomy kb) #(jtms/in? (:tms kb) %)))
    (tax/restore-depths (:taxonomy kb))
    ;; Nothing about an exception is stored, so blocking cannot be read back: recovery lands
    ;; unblocked (a fresh network holds no blocks, and the settle below re-derives them) and the
    ;; window in between believes an excepted conclusion.  Queue every exception-bearing rule so the settle
    ;; below re-evaluates and withdraws them.  This is recovery, not a store mutation,
    ;; so it is a deliberate explicit trigger rather than the choke-point extension point: no
    ;; sentence arrived or left — the whole in-memory blocking state did.
    (special/recheck-every-exception kb)
    ;; ...and the same for supersession, which is derived from the equality closure and
    ;; is likewise not readable back from the store.  Seeded before the settle, since
    ;; `refresh-supersessions` only re-examines the entries it already holds.
    (special/refresh-supersessions kb (recovered-supersessions kb))
    ;; the P/¬P coincidence set is derived from storage and no store holds it, so rebuild
    ;; it before the settle below reads it (`settle/negation-nogoods`)
    (kb/rebuild-opposed! kb)
    ;; ...and the visibility roster, for the same reason and one more: a **fork** rebuilds
    ;; its belief over the merged view rather than inheriting it (`fork`), so without this
    ;; a fork would answer its base's excepts off a roster of its own that nothing filled.
    ;; Before the settle for the same reason too — `justification-excepted?` reads it.
    (kb/rebuild-excepted! kb)
    ;; ...and the two rule rosters, third of the same kind: nothing above replays rule
    ;; *indexing*, which is where they are bumped, so a KB that did not build them as
    ;; the rules arrived has none.  Before the settle, which reads them for the
    ;; visibility seeds.
    (kb/rebuild-rule-roster! kb)
    ;; ...and the argument-preservation roster, fourth of the same kind: it is what
    ;; `settle/preserving-nogoods` reads instead of the index, and a KB that came up
    ;; without it would report no inherited clash until a declaration next moved.
    (kb/rebuild-preserving! kb)
    ;; The first cache reconcile ran before the visibility roster existed, so it
    ;; could narrow only against JTMS belief. Re-run through the common transition
    ;; boundary now that recovery can also answer which declarations are excepted.
    (special/reconcile-belief-change kb)
    ;; ...and the settle that finishes the rebuild is told it *is* one, so the exposure
    ;; pass stays out of it: what it reports is what a change newly made jointly visible,
    ;; and a restore changes nothing (`settle/*rebuilding?*`).
    (binding [settle/*rebuilding?* true]
      (settle/settle kb)
      ;; The **refusal** record is the other in-memory state no store holds: a firing
      ;; refused at derive time left no justification, so replaying the stored ones cannot
      ;; put it back, and a KB restarted with refusals standing would answer a later
      ;; release differently from one that never restarted.  Re-firing the rules that can
      ;; refuse re-records what they refuse, and it runs after the settle above because a
      ;; refusal is a claim about what the KB *believes*.  A re-fire that placed something
      ;; the narrowed re-chain had not owes a second settle.
      (let [{:keys [derived]} (chain/rerecord-refusals! kb)]
        (when (pos? (long (or derived 0))) (settle/settle kb))))
    kb))

(defn recover
  "The implementation of `vaelii.core/recover` — build the in-memory JTMS and taxonomy for
  the persistent stores.  The public contract, and when a caller runs it, are on
  `vaelii.core/recover`; the mechanism is here, in `vaelii.impl.belief-image` and in
  docs/taxonomy.md.

  A `:disk-snapshot` KB whose belief image still describes its records, its source and
  its policies installs the image and recovers nothing (`belief-image/install!`).  Every
  other KB, and that one whenever the image is declined, recovers from the records
  (`recover-from-records`) and then writes an image for the next open
  (`belief-image/save!`, a no-op for a KB that takes none).  Both paths register the
  close-time refresh (`belief-image/register-close!`)."
  [kb]
  (let [t0 (System/nanoTime)
        {:keys [belief reason source]} (belief-image/install! kb)
        source (if (= :installed belief)
                 (do (trove/log! {:level :info :id ::belief-image-installed
                                  :msg (format "installed the belief image for %s in %.0f ms"
                                               (:snapshot-dir kb)
                                               (/ (- (System/nanoTime) t0) 1e6))})
                     source)
                 (do (when (belief-image/applies? kb)
                       (trove/log! {:level :info :id ::belief-image-declined
                                    :msg (str "belief image for " (:snapshot-dir kb)
                                              " not installed (" (name reason)
                                              ") — recovering from the records")}))
                     (recover-from-records kb)
                     (or (:source (belief-image/save! kb)) source)))]
    (belief-image/register-close! kb source)
    ;; Belief now exists over whatever the store holds, so the write entry points stop refusing
    ;; on that count.  Only that count: a **derived** index is not rebuilt here — `recover`
    ;; reads it rather than writing it — so a KB recovered over one still mints a second
    ;; handle per assert, and `reindex` is the call that clears the other half.
    (kb/note-hazards! kb {:no-belief false})
    kb))

(defn recover-with-image
  "`recover`, trying the belief image in `dir` first — an export dump's, which
  `vaelii.impl.io.import` lands the records of.  `records` is the content fingerprint of
  the records the import landed, `{:sentexes fp :justifications fp}`, in the form the
  dump's manifest carries.  Returns `{:belief :installed}` or `{:belief :recovered :reason
  r}`, `r` the reason `belief-image/install-from!` gave for declining the image."
  [kb dir records]
  (let [{:keys [belief reason source]} (belief-image/install-from! kb dir (constantly records))]
    (if (= :installed belief)
      (do (belief-image/register-close! kb source)
          (kb/note-hazards! kb {:no-belief false})
          {:belief :installed})
      (do (recover kb)
          {:belief :recovered :reason reason}))))

(defn register-fresh-store!
  "Register the belief image's close-time save for `kb` when it opened over an empty
  store, and return `kb`.  Such a KB builds its belief assert by assert and never runs
  `recover`, so without this its first image would wait for the next open's recover.  A
  KB opened over a populated store registers inside `recover` instead, and one opened
  with `:recover? false` over a populated store registers nowhere: it holds no belief to
  write.  `belief-image/save!` refuses a KB whose network does not cover its records, so
  a loader that fills this KB and skips the recover leaves nothing to write."
  [kb]
  (when (and (belief-image/applies? kb) (nil? (cap/some-sentex-id (:records kb))))
    (belief-image/register-close! kb nil))
  kb)
