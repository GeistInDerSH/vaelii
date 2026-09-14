# Changelog

Notable changes to `vaelii`, newest first. Versions follow
[semantic versioning](https://semver.org/spec/v2.0.0.html); pre-1.0, a **Breaking**
entry raises the minor. What each class means, and why a **Refusal** is patch-eligible,
is [CONTRIBUTING.md §3](CONTRIBUTING.md).

**Releases before 0.19.0 are summarized rather than reproduced.** Each one
keeps its title, its class census and every `*Breaks:*` token, so an upgrade across
several releases is still a grep for the name you call. The full entry prose for a
released version is in this file's git history, at the tag of the release that shipped
it — `git show v0.16.0:CHANGELOG.md`.

## 0.19.1 — 2026-09-14 — "rule readers take the implies form from sentence-of, and the source identity parses only what changed"

- **The NAT teardown, the index fingerprint, the retired-spelling filter, the vantage
  supporter check and the QCN refuted-pair read take a rule's `implies` form from
  `sentence-of`.** 0.19.0 dropped the `sentence` slot from a rule record, and these five
  readers still read the slot, so each read `nil` for every rule. Retracting the last rule
  that names a reified NAT left the constant uncollected, and an index dump's fingerprint
  over rules differed from the digest an earlier release wrote. Each reader now calls
  `sx/sentence-of`, so the fingerprint over rules is again the digest it was before the
  slot went, and an index dump written before 0.19.0 matches. *Class:* **Fix** (a
  regression 0.19.0 shipped). *Migration:* none. [docs/nat.md](docs/nat.md),
  [docs/storage.md](docs/storage.md)

- **The source identity parses a source file only when the file changed.** 0.19.0
  computes the digest on every `import!` and `export!` that carries a belief image and on
  every disk-snapshot open and close, and each computation read, parsed and printed all
  111 engine namespaces: 1.1 s a call. The parse of each file is now memoized in a new
  process cache `:source-parses`. A call takes each file's modification time and length,
  reads and hashes only a file whose stat moved, and parses only a file whose SHA-256
  moved: 9 ms a call after the first. A file modified within 2 s before its last read is
  read again, so the digest still describes the files as they stand at the call. The test
  suite's per-namespace time summed across CI shards rose from 3,482 s to 6,270 s in 0.19.0;
  `relation-properties-test`, which imports a core dump per test, runs in 15.8 s against
  40 s. *Class:* **Fix** (a regression 0.19.0 shipped). *Migration:* none.
  [docs/caches.md](docs/caches.md)

- **The shipped `CxBiology` stores no `(hasCapability ?x travelling)`; the capability
  hierarchy answers it.** A forward rule concluded travelling from flying, which
  `(transitiveInArgInverse hasCapability 2 genl)` with `(genl flying travelling)` already
  answers at retrieval, so every flyer carried a second stored record and justification.
  The rule is removed. `ask?` and `prove` answer `(hasCapability Tweety travelling)` as
  before, and answer nothing while the flight is defeated; `sentexes-matching` and
  `handle-of` find no stored travelling record. The `/demo` walkthrough's travelling row
  shows what `ask?` answers. *Class:* **Fix** (a `sentexes-matching` or `handle-of` read of
  a travelling conclusion returns nothing where it returned the record). *Migration:* read
  a capability the hierarchy reaches with `ask?` or `prove`.
  [docs/inherit.md](docs/inherit.md), [docs/web.md](docs/web.md)

## 0.19.0 — 2026-09-13 — "a belief image in place of recover, and sentex records that no longer restate their sentence"

- **Under `:refuse`, a declaration arriving after the content it convicts decides the
  clash.** A `disjoint`, `disjoint_metatype`, `genl`-edge, `functional`, `asymmetric`,
  `anti_transitive` or `functionalInArg` declaration landing over stored facts it convicts
  now defeats the weaker side, or reports an equal-strength set in `contradictions`, under
  the default `:refuse` policy as under `:arbitrate`. `:refuse` filed such a clash in
  `violations` and kept both sides believed, while a recover of the same records decided
  it, so a KB believed one thing live and another after a restart. A clash only a common
  descendant context sees is still reported, not decided, under `:refuse`.
  *Class:* **Breaking** (a `:refuse` KB whose schema arrives last disbelieves the weaker
  member, and reports the pair in `contradictions` or `conflicts` rather than
  `violations`). *Migration:* read a late-declaration clash off `contradictions` /
  `conflicts` rather than `violations`; state both sides at equal strength to keep both
  believed. [docs/nmtms.md](docs/nmtms.md), [docs/contexts.md](docs/contexts.md)

  *Breaks:* `:refuse`, `violations`

- **A rule map has no `:sentence`; its `:antecedent` and `:consequent` are the rule.** A
  rule record held its whole `(implies (and …) …)` form beside the antecedent vector and
  the consequent it was split into, and about half the engine's rule readers re-split the
  form rather than reading the fields. The record drops the `sentence` slot, and every
  reader takes the fields; `sentence-of` (new, public) builds the canonical `implies`
  form from them, and `readable-sentence` the same form in the author's variable names.
  The daemon serves `sentence-of` as the `:sentence-of` op, which takes no KB.
  The trie key is built from that same form, so an index written before this change reads
  unchanged. A disk frame drops the field
  (new rule tags; older frames decode by reading past it): a rule frame on the plain codec
  is 132–143 B where it was 219–238 B on the three corpora measured, and a `RuleSentex` is
  72 B on the heap where it was 80 B, before counting the `implies` form it no longer
  holds. An export dump's rule frame still carries `:sentence`, so the dump format is
  unchanged. A negated rule `(not (implies …))`, which `assert` already refuses, is now
  refused by the constructor too, since a record with no sentence has no place for the
  sign. *Class:* **Breaking** (`sentex`, `sentexes-matching`, `canonical-sentex` and the
  extent readers return rule maps without `:sentence`, and the daemon's wire records lose
  the key). *Migration:* a caller that read a rule's `(:sentence s)` calls `(sentence-of
  s)`, or `(readable-sentence s)` for display; a caller that split the sentence reads
  `:antecedent` and `:consequent`. [docs/api.md](docs/api.md),
  [docs/storage.md](docs/storage.md)

  *Breaks:* `sentex`, `sentexes-matching`, `canonical-sentex`, `:sentence`

- **A sentex map has no `:polarity` key; a negative literal's `:sentence` is its sign.**
  Every literal carried `:positive` / `:negative` beside a sentence whose head `not`
  already said the same, and every rule carried `:positive`. The records drop the slot,
  and a disk frame drops the field: 10 B off every literal frame on the plain codec (53 to
  91 B before), and no heap change, the 48 B `LiteralSentex` being aligned either way. A
  store written with the polarity field reads unchanged. The index fingerprint and the
  koinii locator digest the same sign keyword as before, so an index dump and a locator
  stay valid. *Class:* **Breaking** (`sentex`, `sentexes-matching`, `canonical-sentex`
  and the extent readers return maps without `:polarity`, and the daemon's wire records
  lose the key). *Migration:* a caller that read `(:polarity s)` tests the sentence
  instead — `(= 'not (first (:sentence s)))` is `:negative` — and a rule has no sign of
  its own. [docs/api.md](docs/api.md), [docs/storage.md](docs/storage.md)

  *Breaks:* `sentex`, `sentexes-matching`, `canonical-sentex`, `:polarity`

- **`bravely` and `cautiously` classify the dilemmas without an ASP backend.** With no
  backend reachable, the `:brave-cautious` reasoner reported every contested datum
  `:supportable`, so `bravely` held for each and `cautiously` for none. It now reads
  `label/classify-local`, which enumerates the dilemmas' minimum resolutions from the JTMS
  dependency graph and classifies each believed datum a resolution moves: `:true` when
  every resolution keeps it, `:supportable` when some do, `:false` when none do. So a
  conclusion drawn from both sides of one dilemma is `cautiously` true, and a member that
  two coupled dilemmas both rebut is neither brave nor cautious. Three new switches cap the
  enumeration — `VAELII_CLASSIFY_MAX_CLUSTER_MEMBERS` (default 12),
  `VAELII_CLASSIFY_RESOLUTION_BUDGET` (20000) and `VAELII_CLASSIFY_MAX_JOINT_OPTIMA`
  (1024) — and a datum past any cap stays `:supportable`. The classification reads the
  justification graph and never re-evaluates an `unknown` antecedent, so a conclusion an
  `unknown` condition decides is read from current belief. With a backend reachable the
  prover still reads `classify-program`, whose program holds each member as an independent
  choice, so on coupled dilemmas it reports every member `:supportable`.
  *Class:* **Breaking** (without a backend, `cautiously` now answers true and `bravely`
  false where the documented degradation answered false and true). *Migration:* a caller that read
  `bravely` / `cautiously` without a backend as "every contested datum is one of several
  options" reads the classification instead; with a backend reachable the answers are
  unchanged. [docs/labeling.md](docs/labeling.md), [docs/operations.md](docs/operations.md)

  *Breaks:* `bravely`, `cautiously`

- **A justification names its rule once, as `:informant`, and carries no `:out`.** A
  rule firing's justification listed the rule handle both as `:informant` and among
  `:antecedents`, and every justification carried an `:out` set nothing ever filled.
  `:antecedents` now holds only what the firing matched and the edges it read; the TMS
  treats a rule-handle informant as an implicit antecedent, so validity, retraction and
  defeat of the rule withdraw its conclusions exactly as before, and `why-not`'s
  `:missing` still names an OUT rule. A justification frame on disk is six fields where
  it was seven, 2.6–3.0 B smaller on the three corpora measured; a store or dump written
  with seven-field frames reads unchanged, the rule stored once. *Class:* **Breaking**
  (`justification`, `supporting-justifications` and `dependent-justifications` return
  records whose `:antecedents` no longer contain the rule handle and which have no `:out`
  key). *Migration:* a caller that looked for the rule among `:antecedents` reads
  `:informant`, which is the rule's handle whenever it is an integer; a caller that read
  `:out` drops the read. [docs/nmtms.md](docs/nmtms.md), [docs/naf.md](docs/naf.md)

  *Breaks:* `justification`, `supporting-justifications`, `dependent-justifications`

- **A `:disk-snapshot` KB installs a belief image in place of `recover`.** After a full
  recover, and at close when the records moved, a `:disk-snapshot` KB on the dense network
  writes its whole belief state — the network, the taxonomy and the KB atoms recovery
  fills — to `<dir>/belief/`. The next open installs that image instead of recovering when
  its stamp equals the KB's: the records' fingerprint, the source identity (a digest of the
  engine source that derives belief, comments and docstrings excluded) and the belief
  policies. A KB with a registered prover or evaluatable takes no image. `export!` writes
  the image into the dump's `belief/` directory by default (`:belief? false` omits it), and
  `import!` with `{:belief? true}` installs it in place of the recover when the import kept
  every handle; the import summary's `:belief-image` reports which happened. The belief
  certificate and its switch are gone.
  *Class:* **Refusal** (`vaelii.belief.snapshot` is refused at every spelling).
  *Migration:* unset `vaelii.belief.snapshot`; a store opened as `{:backend :disk-snapshot}`
  writes and installs the image with no switch.
  [docs/storage.md](docs/storage.md#the-belief-image), [docs/api.md](docs/api.md),
  [docs/defenses.md](docs/defenses.md#a-belief-image-is-installed-whole-or-not-at-all)

  *Breaks:* `vaelii.belief.snapshot`

- **A `genlCx` cycle is refused at assert, like a `genl` cycle.** A cycle between two
  contexts that see each other was admitted before — `genlCx-problems` checked only the
  self-edge — so an edge whose super already saw its sub closed a mutually-visible
  component. `genlCx-problems` now reads the global `genlCx` closure (`tax/genlCx?-global`,
  the twin of `genl?-global`) and refuses that edge with `:not-well-formed`, so the context
  hierarchy is a partial order like the type hierarchy. A cycle still reaches the taxonomy
  another way and is still condensed: `recovery/recover` replays a stored edge past the
  assert checks, so a store an older or foreign writer left with a `genlCx` cycle loads and
  ranks over its condensation as before. *Class:* **Refusal** (a cycle-closing `genlCx` edge
  that asserted before now throws). *Migration:* an ontology stating mutual visibility
  between two contexts through a `genlCx` cycle models it another way — a context is where a
  sentex is stored, and the two stay distinct records — while a recovered or foreign store
  holding such a cycle is unaffected. [docs/contexts.md](docs/contexts.md),
  [docs/taxonomy.md](docs/taxonomy.md)

  *Breaks:* `genlCx`

- **Three write paths stored a record no belief-filtered read could find — an inert NAT, a
  bulk symmetric mirror, and `canonical-sentex`'s key — and a NAT-shaped type node threw
  the disjointness audit.** A reifiable compound reached the store unresolved on a path
  below `assert`, leaving a record reachable by its handle and by a `CxEverything` read and
  by nothing a query reifies to. `assert-inert` stored `(likes Rex (FruitFn Apple))` as
  written, so `assert` of the same sentence minted `(likes Rex nat/…)` at a second handle,
  `count-with-functor` answered 2 for one proposition, and the reads split across two
  spellings; it now resolves a ground reifiable NAT to its stored constant first (dedup,
  never mint), as `handle-of` and `sentexes-matching` do, and refuses a NAT this KB never
  minted (`:unminted-nat`), since minting a `termOfUnit` is a belief-carrying side effect
  the entry point never has. `bulk-assert-facts!` under `(symmetric P)` stored the mirror of
  a stored literal as a second record, because the fast path skips the dedup probe and the
  caller who wrote `(siblingOf Bob Ann)` cannot know it canonicalizes onto a stored
  `(siblingOf Ann Bob)`; a symmetric functor now keeps the probe (one taxonomy read per row
  for a rare mark), so the bulk result is again identical to loading the facts one-by-one.
  `canonical-sentex` returned the compound for a NAT sentence though its docstring promises
  the key `assert` stored, and now reifies for read and agrees with `handle-of`, documenting
  that a NAT with no minted constant has no canonical stored form yet. `disjointness-audit`
  sorted `(types kb)` with bare `sort`, and `compare` throws on a list, so a KB with a NAT
  type node threw a bare `ClassCastException`; it orders by `by-print-key` now, as
  `disjoint-line` already does. *Class:* **Refusal** (`assert-inert` turns a previously-
  stored unminted-NAT compound into `:unminted-nat`; the bulk dedup, the `canonical-sentex`
  agreement and the audit ordering are fixes). *Migration:* nothing for a caller whose inert
  NATs already have a stored constant; a caller storing a not-yet-minted reifiable compound
  inert asserts the NAT-bearing fact first, which mints it, then stores it inert.
  [docs/nat.md](docs/nat.md), [docs/canonicalization.md](docs/canonicalization.md)

  *Breaks:* `assert-inert`

- **A constraint rule may not spell an internal solve marker as its consequent.**
  `cardAtMost`, `cardAtLeast`, `minimizeCost` and `softPriority` are the consequent markers
  the answer-set surfaces normalize into — `asp/atMost` / `asp/atLeast` and their soft
  twins, `asp/minimize`, and a priority-tagged `set/softConstraint`. A hand-written marker
  skipped the surface's operand checks, so `(set/hardConstraint (implies (pickCity ?a ?c)
  (cardAtMost ?a ?c)))` stored, and `do/label` then threw a bare `ClassCastException`
  reading `?a`'s binding as the count. `rules/refuse-internal-marker` refuses such a rule at
  `assert` with `:not-well-formed`, names the surface form to write, and `check` reports the
  same problem. *Class:* **Refusal** (a hand-written `cardAtMost` / `cardAtLeast` constraint
  consequent that asserted before now throws). *Migration:* write the surface form —
  `(asp/atMost k ?counted pattern)` in place of `(set/hardConstraint (implies pattern
  (cardAtMost k ?counted)))`, and the soft twins in place of `set/softConstraint`; the surface
  normalizes into the same stored rule, so the handle is unchanged, and a KB exported with
  `export-text!` already spells the surface. [docs/solving.md](docs/solving.md)

  *Breaks:* `cardAtMost`, `cardAtLeast`

- **`asp/minimize` and a priority on `set/softConstraint` give a solve a weighted,
  prioritized objective.** `(asp/minimize priority ?weight body)` adds `?weight` to the
  objective at level `priority` for every binding of `body`, whose choice literal names
  the chosen head and whose background literal binds the weight — clingo's
  `#minimize{ W@P, … }`. `(set/softConstraint priority (implies …))` places an ordinary
  soft constraint at a level other than 1. Distinct priorities are distinct lexicographic
  minimize levels above the keep-belief and tiebreak levels, so a lower priority breaks
  ties among the higher level's optima and never trades against it. `assert` refuses a
  priority that is not a positive integer, a weight slot that is not a variable the body
  binds, and a priority on `set/hardConstraint`, as `:not-well-formed`, and `check` reports
  each. Grounding refuses a weight a background fact binds to anything but an integer
  inside the solver's 32-bit range, naming the weight. `export-text!` writes both forms as
  authored. *Class:* **Additive**. [docs/solving.md](docs/solving.md)

- **A `:disk-snapshot` open declines an image of records a `clear!` removed.** The index
  image and the belief image are stamped with a fingerprint of the record store's slots
  (handle, offset, length), which reads no content. `clear!` truncates the logs, so
  records of the same byte lengths written after it refilled the old slots exactly, and
  the next open mapped the old index image or installed the old belief image over the new
  records. `clear!` now mints a random epoch into the store's counters blob, and both
  fingerprints carry it. A store no `clear!` has emptied carries no epoch, so the images
  already on disk stay valid. *Class:* **Fix**. [docs/storage.md](docs/storage.md)

- **A defeat that releases an `unknown` antecedent or an `exceptWhen` exception
  re-derives the conclusion it had blocked.** Under `(pp ?x) ∧ (unknown (happy ?x)) →
  (rr ?x)`, asserting `(pp Zed)`, then a default `(happy Zed)`, then a monotonic `(not
  (happy Zed))` left `(rr Zed)` underived, while every order that asserted the negation
  earlier derived it. The firing was placed, then blocked and swept when `(happy Zed)`
  arrived. The defeat moves `(happy Zed)` OUT without removing it, and the swept firing had
  left no blocked justification and no refusal record to re-ask. A settle pass now
  re-chains every rule watching the predicate of a datum it newly defeated
  (`settle/released-by-defeat`). *Class:* **Fix** (the belief docs/naf.md promises
  order-independent). *Migration:* none. [docs/naf.md](docs/naf.md)

- **A `genlCx` edge arriving last exposes the `functional`, `asymmetric`,
  `anti_transitive` or `functionalInArg` clash it reveals.** A mark in one context and two
  clashing facts together in another form a clash only once a `genlCx` edge lets the facts'
  context see the mark's. With the edge arriving after both, neither the `:refuse` exposure
  pass nor the `:arbitrate` deciding path found the clash, so belief depended on arrival
  order. The exposure pass read the marks visible to the edge's super context, which gains
  no new sight; it now reads them from the sub context. The deciding path reached only
  disjointness memberships from a `genlCx` edge, and now reaches the marked facts as well,
  within the same budget. *Class:* **Fix** (the order independence docs/nmtms.md
  promises). *Migration:* none. [docs/nmtms.md](docs/nmtms.md),
  [docs/contexts.md](docs/contexts.md)

- **Retracting one `functionalInArg` position of a predicate retires the clashes only that
  position convicted.** The clash memo keyed its vocabulary on the set of predicates
  carrying a `functionalInArg` declaration, and a predicate may carry several positions.
  Retracting one position while another remained left the key unchanged, so
  `contradictions` and `conflicts` kept reporting a pair only the retracted position
  convicted. The key is now the table from each predicate to its positions.
  *Class:* **Fix**. *Migration:* none. [docs/nmtms.md](docs/nmtms.md)

- **`assert` of `(disjoint a b)` refuses a `genl`-related pair only where the asserting
  context sees the edge.** The overlap check read the global `genl` closure, so the refusal
  fired in a context where an `except` hid the bridging edge and the scoped `(genl a b)`
  query answered empty. The check now reads the closure the asserting context sees, and
  admits the pair where an `except` hides the edge. The `genl` cycle check and `disjoint?`'s
  own `genl`-relatedness guard stay global. *Class:* **Fix** (a `disjoint` refused before
  now stores where the scoped query finds no `genl` edge between the pair).
  *Migration:* none. [docs/taxonomy.md](docs/taxonomy.md)

- **`close!` releases a KB's rete alpha memories and its derived RAM index, and a failed
  `open-kb` releases the directory lock it took.** The alpha-memory registry held each KB
  it tracked by a strong key and released nothing. A disk-backed KB with a derived index
  (`:disk-dense`, `:disk-columnar`, `:disk-memory`) kept that index in a process-wide
  registry keyed by its directory after `close!`. A process that opened KBs in a loop
  therefore kept one alpha memory and one derived index per KB it had closed. The registry
  now keys each KB weakly, and `close!` drops the KB's alpha memories and its derived
  index; both rebuild from the records on the next use. The alpha memories register as the
  `:rete-alpha` cache, which the memory-pressure guard may drop. An `open-kb` that threw
  after its durable stores resolved — a stale-index refusal, a `:pg` identity mismatch, a
  recover over a corrupt store — held the directory's exclusive lock until the JVM exited,
  and no later open of that directory succeeded. `open-kb` now closes the directories the
  call opened when construction throws. *Class:* **Fix**. *Migration:* none.
  [docs/api.md](docs/api.md), [docs/caches.md](docs/caches.md)

- **The web `/kbs/load` route opens a disk store with the backend its layout names.** The
  route opened every store as `:disk-log`, so a store with a derived index
  (`:disk-columnar`, `:disk-snapshot`) opened with 0 sentexes and gained an empty
  `index/kv.log`. The route now reads the store's `index/` directory: `trie.csr` opens
  `:disk-snapshot`, `kv.log` opens `:disk-log`, and neither opens `:disk-columnar`, which
  rebuilds its index on open. The open passes `:recover? :auto`, so a stored belief image
  installs when its stamp matches. *Class:* **Fix**. *Migration:* none.
  [docs/catalog.md](docs/catalog.md), [docs/web.md](docs/web.md)

- **The disk store's `compact!` keeps a sentex's premise mark when the provenance frame at
  its handle is lost.** `compact!` handed the premise set to every kind's compaction, so a
  provenance frame lost at a sentex's handle removed that sentex's premise mark for the
  rest of the session. Only the sentexes compaction receives the premise set now.
  *Class:* **Fix**. *Migration:* none. *Released in 0.19.0; this entry was added after the
  release.* [docs/storage.md](docs/storage.md)

- **The special-predicate table refuses an arm keyed on a functor no declaration places in
  it, at namespace load.** `special/check-declarations` read its enumeration halves off the
  entries vector, which held only the functors `pr/in-special-table` admits, so an arm keyed
  on an undeclared functor was dropped from the join rather than refused. It now reads the
  arm functors off the arms map, which holds every armed functor, so the refusal
  `docs/predicates.md` describes fires on the live table. *Class:* **Fix** (no shipped arm is
  undeclared, so no answer moves). *Migration:* none. *Released in 0.19.0; this entry was
  added after the release.* [docs/predicates.md](docs/predicates.md)

- **Seven hot paths drop work that changes no answer.** A disk record fetch takes the
  kind's read lock rather than an exclusive monitor, so a bulk sweep (`export!`, `reindex`,
  the `recover` read side) reads records in parallel. The disk store holds the premise set
  as a Roaring64 `LiveRoster` under the sentexes kind lock rather than a boxed hash set, and
  answers `sentex-ids`, `justification-ids` and `premise-ids` as the immutable
  `HandleRoster` snapshot rather than building a hash set per call. Recovery defers the
  cycle-closing SCC repair of a `genl` or `genlCx` edge to one `restore-depths` pass
  (`*defer-cycle-scc?*`, false on the live path). The retroactive membership sweep skips its
  type-separating reach on a KB that declares no separation. The defn provers decide
  `applicable?` from a stored-count gate and a `genl?` per declaring collection before any
  ancestor walk. `classify-local` clusters dilemmas through the `touch` index in linear time
  rather than by comparing every pair. *Class:* neither label — belief, the justification
  set and the stored content are unchanged, so only latency and heap move. *Released in
  0.19.0; this entry was added after the release.* [docs/storage.md](docs/storage.md),
  [docs/labeling.md](docs/labeling.md)

- **An operation log records a `:disk-snapshot` KB's public writes, and a seal and a
  restore bring its directory back by replaying the log; no public entry point attaches a
  log yet.** `vaelii.impl.oplog` appends one frame per outermost public write — the
  operation, its arguments, and the clock, creator and dynamic bindings the call reads
  beyond them — and a write nested inside another appends nothing. A seal-class call
  (`import!`, `clear!`, `recover`, `reindex`, `load-text!`), a configuration call, an
  argument nippy cannot freeze and a change-feed listener's write each mark the log unusable
  until the next seal. `vaelii.impl.seal` writes the index and belief images, fsyncs the
  record store, writes `oplog/seal.nippy` and starts a new log generation. A restore
  installs both images against the seal's fingerprints and replays that generation's
  frames, checking each replayed write against the stored record, and declines on any
  mismatch. Every public write entry point in `vaelii.core` routes through `oplog/run-op`,
  which does nothing on a KB with no log. A crash-cut sweep, exhaustive under
  `lein test-fuzz`, and a multi-JVM kill test cover the restore. *Class:* **Additive**
  (engine-internal: only `seal/attach!` attaches a log, and no public function calls it).
  *Released in 0.19.0; this entry was added after the release.*
  [docs/glossary.md](docs/glossary.md), [docs/namespaces.md](docs/namespaces.md)

- **A settle-phase instrument splits a settle's wall clock into four cost centres, and
  `lein bench-settlephases` reports the split.** `vaelii.impl.settle-phases` charges each
  settle's self time to the centre running at that instant — belief fixpoint, contradiction
  discovery, resolution and generative chaining — so a nested centre's time is subtracted
  from its parent's and the four buckets sum to the whole. The instrument sits behind one
  atom that is nil when off, where a probe costs a deref and a `nil?` check. `lein lint`'s
  E17 also rosters `genlCx?-global`, the fifth global taxonomy read, and its one caller, the
  `genlCx`-cycle refusal. *Class:* **Additive** (developer tooling; no public function
  moves). *Released in 0.19.0; this entry was added after the release.*
  [docs/namespaces.md](docs/namespaces.md)

- **The engine's `project.clj` names no `vaelii-foreign` coordinate: the `:with-foreign`
  profile is removed, and `scripts/with-foreign.sh` runs a lein task with the readers as an
  ad-hoc dependency.** The profile pinned the plugin at the engine's own version, so a
  carved engine named a plugin coordinate that had to exist on Clojars, and every engine
  release forced a plugin release. A consumer's plugin use runs through the
  `vaelii/foreign.edn` manifest on its own classpath, which the profile never touched. In
  this checkout `scripts/with-foreign.sh` (default task `browser`, `FOREIGN_VERSION` to pin a
  version) and `scripts/link-checkouts.sh` (live plugin source) replace it, and the
  `bench-profile`, `bench-index` and `bench-alloc` aliases drop the profile. *Class:*
  **Additive** (build tooling; `lein with-profile +with-foreign …` no longer resolves in this
  checkout). *Released in 0.19.0; this entry was added after the release.*
  [docs/foreign.md](docs/foreign.md)

## 0.18.1 — 2026-09-11 — "a typed bound at every entry point, and an upper ontology divided by space and time"

**14 entries** — 2 Refusal, 4 Additive, 6 Fix. Every bounded entry point refuses a value
outside its domain by name, reading one shared domain table, and `assert-inert` refuses an
open sentence. The upper ontology divides `thing` by space and time, renames
`spatial_thing` and `temporal_thing` to `spatial` and `temporal`, and adds a `CxUniverse`
collector context. A process-wide cache profile scales every derived cache's bound, and a
memory-pressure guard the servers install shrinks the caches as the old generation fills
and grows them back as it drains. A reified NAT or context constant is named by the
SHA-256 of its expression, so the same expression reifies to the same constant across
processes, and the one-shot clingo solve injects its ground program through the backend
accessors rather than a temp file.

*Breaks:* `:counters?`, `:believed?`, `:max-cost`, `:max-depth`, `:max-term-growth`, `add-evaluatable`, `assert-inert`, `describe`, `why-not`, `spatial_thing`, `temporal_thing`

## 0.18.0 — 2026-09-09 — "declarations that mint the types they constrain, and rules that forward-chain only when asked"

**15 entries** — 2 Breaking, 1 Refusal, 9 Additive, 3 Fix. Assertive argument types become
the default reading: an `arg` / `genlArg` / `interArg` declaration mints the type it
constrains rather than only testing for it. A bare `implies` rule defaults to `:backward`
and materializes nothing, and `set/forwardRule` adds forward chaining to the backward use
rather than replacing it, so a rule forward-chains only where its author asks. The arity
vocabulary gains a runtime floor — a variable-arity application below its `arityMin` is
refused — and `admitsArgnum` answers a position query from the declared arity. New
declaration vocabulary types a whole variable-arity tail (`args`, `argsGenl`, `argAndRest`,
`argAndRestGenl`) and names an `intersection` kind that derives its taxonomy edges, and new
readers report the brave and cautious status of a labeling dilemma, a cardinality bound over
ASP choice heads, and the subsumption status of every type pair. A state-of-affairs and
causality cluster joins the upper ontology in CxAbstract.

*Breaks:* `VAELII_ASSERTIVE_ARG_TYPES`, `(implies` asserted bare, `set/forwardRule`,
`arityMin`, `(lessThan`, `(greaterThan`, `(termsRelated`, `(functionCorrespondingPredicate`

## 0.17.0 — 2026-09-06 — "arity as vocabulary over every relation, and declarations that stop restating their own conclusions"

**14 entries** — 1 Breaking, 4 Refusal, 4 Additive, 5 Fix. A declaration that restates
what the taxonomy already concludes turns that conclusion into a precondition, so the
arrival order of two assertions decides which facts a KB holds. Four entries retire such a
declaration — on `genl`, on fifteen unary marks, on six arity marks, and in the `predAll`
pair's third argument — and the arity vocabulary underneath is rebuilt so `relation` is
the common parent of `predicate` and `function` and every relation lands in exactly one
arity policy. `predAllSpecified` and `predSpecifiedAll` go binary and derive the filler
type from the predicate's own slot contract. Three composite function marks — `injection`,
`surjection` and `bijection` — arrive as one declaration each, a `genlCx` edge's merge
sweep stops growing with the KB, and a late `symmetric` declaration folds a mirrored pair
no earlier version could fold.

*Breaks:* `(predAllSpecified`, `(predSpecifiedAll`, `specified-violations`,
`all-specified-violations`, `(binary_predicate P)` beside `(variable_arity P)`,
`:arg-type`, `*assertive-arg-types?*`, `VAELII_ASSERTIVE_ARG_TYPES`,
`(genlArg genl 1 thing)`, `(arg symmetric 1 predicate)`, `(arg functional 1 predicate)`

## 0.16.0 — 2026-09-04 — "the predAll quantifier family, refusals that name their kind, and declarations that reach back"

**18 entries** — 3 Breaking, 1 Refusal, 7 Additive, 7 Fix. The `predAll` quantifier
family lands in all eight cells. Three refusals stop answering with the wrong keyword:
an unpinned indeterminate term is not provably `different` from anything, a missing
adapter is not an unknown backend, and a wrong operand count is not an unknown option.
Declarations arriving after the facts now reach them — a `(symmetric P)` mark folds
records already stored, a computed `genlCx` edge runs the reconcilers a stated one runs,
and `quotedArg` is answered along the `genl` closure. Every refusal declares what its
`ex-data` carries, and a throw that drops a key fails the build.

*Breaks:* `(different`, `indeterminate_term`, `:unknown-backend`, `:sqlite`, `:pg`,
`:unknown-option`, `:not-stratified`

## 0.15.0 — 2026-09-01 — "definitions that compute, and two renames"

**7 entries** — 2 Breaking, 5 Additive. Definitional membership is answered at query
time rather than only by a forward rule. Two renames: the sentex polarity slot is
`:polarity`, and the `AtomicSentex` record is `LiteralSentex`. A unary predicate is
snake_case and `assert` enforces the spelling in both directions, which retired the
camelCase marks. CxCore names the expression kinds and gains a curation vocabulary.

*Breaks:* `unaryPredicate`, `reifiableFunction`, `abduciblePredicate`,
`closedExtentPredicate`, `disjointMetatype`, `siblingDisjoint`, `warmBlooded`, `:truth`

## 0.14.0 — 2026-08-29 — "the index image as a backend, and the heap it stops paying"

**10 entries** — 1 Refusal, 1 Additive, 5 Fix. The mapped index image becomes a backend
of its own, `:disk-snapshot`, rather than a property of the disk store, and stops
carrying the argument roots into heap. The disk store's live-handle sets become
compressed bitmaps. The writer refreshes a drifted image mid-life and can be told not
to. A `functionalInArg` declaration arriving after the facts it convicts is reported
rather than silently late. Neither adapter shipped at this version; both stayed at
0.13.0.

*Breaks:* `vaelii.index.snapshot`, `:argument-family-ceiling`

## 0.13.0 — 2026-08-25 — "calendar time, joined queries, and the entry points that refuse"

**93 entries** — 5 Breaking, 12 Refusal, 32 Additive, 22 Fix. The largest release:
calendar time, joined queries and a sweep through the entry points that refuse.
`CxChange` ships an event calculus, calendar constructors give a date its own endpoints
so it orders itself, and a metric constraint narrows an interval relation. `or` is
accepted in a rule antecedent, stored as one rule per alternative, and refused as a
goal. Every search entry point takes a bound and the daemon holds them to its ceiling.
Twelve refusals close inputs whose acceptance stored junk, and the `:disk` and
`:pg-disk` pairings are renamed to say that both halves are out of core.

*Breaks:* `:disk`, `:pg-disk`, `VAELII_TEST_BACKEND=disk`, `edit!`,
`edit-with-consequences!`, `apply-proposal!`, `contexts`, `count-in-context`,
`contextDenotingFunction`, `lein cli load`, `prove`, `provable?`, `query`, `argue`,
`forward-chain`, `ask`, `ask?`, `query-plan`, `abduce`, `sentexes-matching`,
`handle-of`, `assert`, `load-text!`, `lein cli assert`, `unaryPredicate`,
`binaryPredicate`, `ternaryPredicate`, `/kbs`, `lein serve --listen <flag>`,
`vaelii.client/client`, `:timeout-ms`, `:token`, `dereference`, `resolve-by-locator`,
`set-trust!`, `trust-of`, `display-name-of`

## 0.12.0 — 2026-08-23 — "query contexts, bulk loading, and a literal's type"

**99 entries** — 3 Breaking, 3 Refusal, 7 Additive, 5 Fix. Query contexts, bulk loading,
and a literal's type. `resultIsa` and `resultGenl` become `result` and `genlResult`; the
four function marks classify what they mark, and the reifiability criterion is written
down. A records read stays lazy, and a proof's witness is one of its bindings. Three
reads that could not answer the question stop answering empty. First release of the two
adapters, `com.vaelii/postgres` and `com.vaelii/sqlite`, each at this version.

*Breaks:* `resultIsa`, `resultGenl`, `reifiableFunction`, `unreifiableFunction`,
`quotingFunction`, `contextDenotingFunction`, `ist`, `:proof?`, `?ctx`,
`qualitative-network`, `possible-relations`, `:arg-type`, `:quoted-arg-type`, `result`,
`genlResult`, `:arg-genl`, `character_string`, `:pg-disk`, `:dir`,
`:stale-index-records`, `register-modal-predicate!`

## 0.11.0 — 2026-08-22 — "contradiction solving, arrival order, and the durable log"

**67 entries** — 2 Breaking, 4 Additive. Contradiction solving, arrival order, and the
durable log. `antiTransitive` convicts the chain it forbids rather than being declared
and deferred. Definitional collection relations tie membership to a defining condition,
and sibling disjointness lets a collection's specializations separate themselves, with
an escape hatch for a pair that must overlap. A computed predicate or function is
registered in one line.

## 0.10.0 — 2026-08-20 — "more than one agent over one knowledge base"

**9 entries** — 4 Additive. Koinii: several agents coordinate over one shared knowledge
base, with belief projection for what each agent holds true. A context can be a reified
function application whose `genlCx` edges compute themselves. Mention-opacity arrives —
a quoting function reads its argument by spelling — and `quotedArg` types an argument
against a syntactic type. The `argIsa`, `argGenl` and `interArgIsa` spellings become
`arg`, `genlArg` and `interArg`.

## 0.9.0 — 2026-08-17 — "the truth-maintenance network defaults to dense"

**15 entries** — 5 Breaking, 8 Additive. The dense truth-maintenance network becomes the
default and gives a concurrent reader a consistent view. Four relation properties are
enforced rather than documented. A subsumption rests on its strongest route rather than
its shortest. An algebraic property becomes one predicate instead of a mark and a twin,
which retired the `...Predicate` spellings.

*Breaks:* `defeat-class`

## 0.8.0 — 2026-08-14 — "predicates inherit down the hierarchy"

**50 entries** — 1 Breaking, 1 Additive. Predicates inherit down the hierarchy. A KB
whose declared hazards are unresolved refuses writes rather than accepting them
unchecked, and a derived record's teardown is refused where belief was never built.
`check` and `check-edit` answer for the entry point they mirror. Five refusals close
recovery paths that believed records the store did not hold.

*Breaks:* `:unrecovered-kb`, `write-hazards`, `note-hazards!`, `contradictions`,
`violations`, `:constraint-exposure`

## 0.7.0 — 2026-08-12 — "contexts get one spelling"

**2 entries.** Contexts get one spelling. A context name is `Cx`-prefixed rather than
`Context`-suffixed, and the context-transitivity predicate is `genlCx`.

## 0.6.0 — 2026-08-12 — "stored rules become first-class"

**22 entries** — 2 Breaking, 4 Refusal, 2 Additive. Stored rules become first-class: a
rule can conclude a rule, and a rule carries a handle, TMS support and retraction with
no rule-specific machinery. A capability claim about a kind is `capabilityType` and
about a member is `hasCapability`. A NAF guard written as a conjunction now guards, and
the strictest policy stops being the leakiest.

## 0.5.1 — 2026-08-11 — "faster writes, more to watch"

**15 entries.** Faster writes, more to watch. A settle pays for the region it moved
rather than for what the KB holds. The arbitrating half of a bounded pass says when its
budget stopped it. Four places where arrival order decided an answer are closed.

## 0.5.0 — 2026-08-07 — "operating the engine as a service"

**23 entries.** Operating the engine as a service. The daemon authenticates and refuses
to bind an address without a token. One space number names a KB's stores, `:space`,
replacing the separate record and index spellings. `context-size` becomes
`count-in-context`, `different` descends into compound arguments, and a name can carry a
sense and a lexeme.

*Breaks:* `:record-space`, `:index-space`, `docs/storage.md`

## 0.4.0 — 2026-08-05 — "correctness fixes against the invariants"

**33 entries.** Correctness fixes against the four invariants. A conjunctive query could
answer nothing while each of its conjuncts answered, and no longer does. `assert`
refuses a sentence that is not an s-expression, an `exceptWhen` query's literals are
held to the naming invariants, and an `edit!` batch key nothing reads is refused.

## 0.3.0 — 2026-08-04 — "a type on every refusal"

**29 entries.** A type on every refusal: every `ex-info` the engine throws carries a
`:type`, and the daemon's refusal keywords become plain. Both servers hold one
request-body ceiling, and the browser serializes its writes. An `ist` form must have
exactly three elements.

## 0.2.0 — 2026-08-03 — "the public API boundary, drawn"

**17 entries.** The public API boundary is drawn — six public namespaces, everything
else `vaelii.impl.*` and free to change. Every handle-taking function refuses a
non-handle. `close!` releases a durable KB's directory, an argument-constraint refusal
names its convicting declaration in content order, and the five sweeps start running in
CI.

## 0.1.0 — 2026-07-31 — "the first release"

The first public release.

## 2026-07-19 .. 2026-07-30 — the pre-release dailies

Twelve dated entries before versioning began, one per day of the initial build: the
whole stack on day one (2026-07-19), then order independence made an invariant, equality
and a sudoku solved, sound negation as failure, performance fixes and an operational
surface, denser storage measured first, OpenCyc in the engine's own format, reads scoped
to the asking context, aggregation over query results, the gate (lint, suite and
scaling), one entry point for backward chaining, and declarations that re-check what
they change (2026-07-30).
