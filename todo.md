# Clodin compiler TODOs

## Hot reload notes from Extempore

Extempore does **not** recompile the functions that depend on a changed
function. It avoids that by indirecting through a stable thunk:

- The first `bind-func foo` emits a stable `foo` thunk that loads the
  current closure from `foo_var` and calls it, plus `foo_maker`,
  `foo_setter`, `foo_getter`, `foo_native` and the body.
- A redefinition emits a new body `foo__N` plus a new maker and setter.
  The setter stores the new closure into `foo_var`. Existing callers
  still call the stable `foo` thunk, so they see the new body without
  being recompiled. See `examples/core/extempore_lang.xtm` around
  "closure redefinition propagation" and `runtime/xtc-driver.xtm`
  around `xtc:driver:emit-thunk-ir`.
- Signature changes are rejected (`print-no-redefinitions-error` in
  `runtime/xtc-driver.xtm`). Static functions cannot be redefined
  (`runtime/xtc-bind.xtm`).
- Each JIT module has its own ORC resource tracker. Redefinition erases
  the old maker and setter, never a body by name; orphaned bodies are
  released once a replacement maker exists. Retired code is kept for a
  10 second grace period because native code may still be executing it.
  See the module ownership comment in `src/EXTLLVM.cpp`.

So the remembered behaviour is propagation to callers, not dependent
recompilation.

## Hot reload design decisions

Decisions for the reloadable-procedure implementation. Where this section and
"Registry design after text IR removal" disagree, this section wins.

### Build modes and procedure kinds

- `:monolithic` — today's behaviour: on every change the whole module is
  relinked and re-JITed. Calls are direct (`call @foo`) and LLVM may inline
  them. No stubs and no cells are installed, and procedure names are emitted
  without version numbers.
- `:reloadable` — one stable stub per reloadable procedure. A redefinition
  emits a new body module and calls the stub's setter. No relinking, no
  inlining of the call target, and a native loop running in another
  procedure sees the new body on its next call.

The mode is a process-wide setting, defaulting to `:monolithic` until the
stub path is proven. Procedure kind is per-`defproc` metadata, `^:static` or
`^:reloadable`, and only matters in `:reloadable` mode:

- `^:static` procedures are emitted as direct symbols and cannot be
  redefined.
- Everything else gets a stub. The default kind is `:reloadable` for now; it
  may flip to static later.
- Mixed builds are allowed: a static procedure calling a reloadable one goes
  through its stub (that edge cannot be inlined), while a reloadable
  procedure calling a static one calls it directly.

Switching modes requires a full reset and re-emission of every body from its
`:ast`, because the emitted call sites and symbol names differ.

### Module layout

One concern per module. Never combine a stub with a body and never batch stubs
or bodies into one module:

- `stub:foo` — `@foo$cell` (the pointer to the current implementation), the
  stable thunk `@foo`, and the setter `@foo$set`. One module per procedure
  name and signature. Added once.
- `body:foo$1`, `body:foo$2`, ... — one module per compilation. Contains only
  the implementation `@foo$N`, its private string literals, and its external
  declarations.
- `global:g` — one module per `def`-ed global, holding the storage for `@g`.
  Added once; re-evaluating the defining form must not re-create the module.

Hard invariant: a module that defines a stable symbol (`@foo$cell`, `@g`) is
added to the JIT exactly once and is never re-added, removed, relinked, or
unloaded until a full reset.

### Naming

- The first stub for a name is `foo`. A signature change mints a new stub
  `foo$sigK`, where K counts signature revisions for that name. The registry
  keeps `name -> {signature -> stub}`. Old stubs stay until a reset.
- Bodies are `foo$N`, where N is a process-wide monotonic counter. A body
  symbol is never reused. In `:monolithic` mode the body is emitted under the
  public name `foo` with no version number.
- Generated names use `$` suffixes. Procedure and global names containing `$`
  are rejected for now, so a Clojure symbol can never collide with a
  generated stub, body, setter, cell or global symbol.

### Stub module

```llvm
@foo$cell = global ptr @foo$unbound

define <signature> @foo(<args>) {
  %code = load atomic ptr, ptr @foo$cell acquire, align 8
  %function = bitcast ptr %code to <function pointer type>
  %result = call <signature> %function(<args>)
  ret %result
}

define <signature> @foo$unbound(<args>) {
  call void @abort()
  unreachable
}

define void @foo$set(ptr %code) {
  store atomic ptr %code, ptr @foo$cell release, align 8
  ret void
}
```

- The cell is written only through `@foo$set`. Do not write the cell by
  computing its address and storing from Clojure.
- The thunk never names a body symbol. It only loads its own cell, so it is
  independent of which body is installed and of the body's symbol.
- The stub's LLVM function type is the procedure signature, so a stub is keyed
  by name and signature. A signature change cannot reuse the same thunk: it
  mints a new stub symbol and call sites are recompiled against it.
- The cell starts at an `abort` body, not null, so a call that races an
  install traps in `abort` instead of jumping to address zero. This also
  gives `declare-proc` a ready-made unbound body.
- A signature change invalidates every pointer to the old stub, including
  native function pointers held by C code. This is documented behaviour.
- The JVM downcall handle points at the stub, so it survives every body
  redefinition. It must be rebuilt when a signature change mints a new stub,
  and after a reset.

### Memory ordering

The cell load is `atomic acquire` and the setter store is `atomic release`:

- A release store publishes everything written before it, including the JIT's
  code and instruction-cache maintenance for the new body, before the pointer
  itself becomes visible. An acquire load orders subsequent reads, including
  the instruction fetch of the new body, after the pointer load.
- Atomic accesses cannot be removed, duplicated, or hoisted out of a loop, so
  the optimizer cannot keep an old pointer in a loop even though the store
  lives in a different module.
- A single aligned pointer store is not torn, and a caller may run
  concurrently with an install: it sees either the old or the new pointer,
  and the old body stays mapped. Calls need no lock.
- On x86-64 acquire/release compile to plain moves; on arm64 they are
  `ldar`/`stlr`, one instruction per call. `seq_cst` is unnecessary.
- Plain `volatile` would prevent hoisting but is not a publication guarantee
  in LLVM's memory model, so it is not used for the cell.
- Globals that are read in a loop and written by another thread follow the
  same rule (`atomic acquire` load, `atomic release` store).
- C API note: `LLVMSetOrdering` takes the enum as an int (`Acquire = 4`,
  `Release = 5`). The jextract bindings expose `LLVMSetOrdering` and
  `LLVMSetVolatile` but not the enum constants, so `clodin.llvm-api` defines
  named constants for the orderings it uses.

### Install timing

- `:reloadable` mode installs the stub when the procedure is first
  registered, and installs a new body as soon as it is built. A native loop
  never returns to the JVM, so lazy install cannot work there.
- `:monolithic` mode stays lazy on the first call, as today.
- A stub is always installed before any module that references it is
  materialised.

### Body module

- One module per `defproc` evaluation. The body has external linkage so the
  JIT can look it up.
- A recursive call inside a body compiles to `call @foo` (the stub), not
  `call @foo$N`. An older body therefore recurses into the newest definition.
- Callee references are external declarations that resolve to the callees'
  stubs.
- Old body modules stay loaded until a full reset. Retired bodies are not
  recorded or reclaimed individually.

### Globals

Implemented in `clodin.globals` and the `defvar` macro. The decisions below
supersede the earlier `def` sketch.

- The surface form is `(defvar name type literal)`. `def` is a Clojure
  special form and cannot be used as a macro, so `defvar` is the name.
  Supported types are `:boolean`, `:i64`, `:i32`, `:f64`, `:f32` and `:ptr`;
  aggregate types are deferred.
- In clodin source the name reads as a value and `(set name value)` writes it.
  A local or parameter shadows a global of the same name.
- Every global owns one storage slot in JIT memory. In `:reloadable` mode the
  storage lives in its own `globals` module, added once per JIT generation.
  A procedure module only declares the symbol as an external global, so
  storage is never duplicated into a procedure body.
- In `:monolithic` mode the storage modules are linked into the procedure
  module, because the whole JIT is replaced on every rebuild anyway. A
  monolithic rebuild therefore reinitialises every global from its source
  initial value. Globals do not survive a monolithic rebuild; stateful
  programs use `:reloadable` mode.
- `:boolean` globals are stored as an `i32` slot holding 0 or 1. LLVM can
  only perform atomic accesses on byte-sized types (an atomic `i1` access is
  rejected by the verifier), and the JVM `VarHandle` cannot compare-and-set a
  boolean. An `i32` slot is the one representation that gives real atomic
  loads, stores and compare-and-set on both sides. Native code converts
  between `i1` and the slot.
- Access is always atomic: native reads use `atomic acquire` loads, native
  `set` uses `atomic release` stores, and the JVM side uses `VarHandle`
  acquire/release accesses. A native loop that reads a global therefore never
  caches it, even at `:O3`. This is unconditional rather than opt-in: an
  atomic acquire/release access on x86-64 is a plain move and on arm64 one
  `ldar`/`stlr`, so the cost is acceptable and there is no way to get the
  concurrency wrong.
- An external global declaration must state its alignment. A module without a
  data layout defaults `i64` to ABI alignment 4, and LLVM then cannot prove
  an atomic access is naturally aligned, so it lowers the access to an
  out-of-line `__atomic_load`/`__atomic_store` call, which the JIT cannot
  resolve. Both the storage module and every referencing declaration set the
  natural alignment explicitly.
- The JVM side sees each global as a `Global` object implementing
  `clojure.lang.IDeref` and `clojure.lang.IAtom2`. `deref`, `reset!`,
  `swap!`, `compare-and-set!`, `reset-vals!` and `swap-vals!` therefore work
  unchanged. The object stores only the global's name and resolves the current
  storage address on every access, so a JIT rebuild or reset never leaves a
  stale pointer behind.
  - `reset!`, `swap!` and `compare-and-set!` dispatch on `IAtom`, and
    `reset-vals!`/`swap-vals!` on `IAtom2`; neither requires an actual
    `clojure.lang.Atom`.
  - `compare-and-set!` compares the raw storage representation, not identity.
    For `:ptr` that is the address, and for `:boolean` it is 0 or 1.
  - `IRef` (watches, validators) is deliberately not implemented: native
    writes could never notify a JVM watch.
- `swap!` is a compare-and-set retry loop, so concurrent JVM writers are
  safe. A native `set` is a plain release store, not a compare-and-set, so a
  native write racing a `swap!` can be overwritten. That is ordinary
  last-writer-wins and is documented rather than prevented.
- Native compare-and-set and atomic read-modify-write are not implemented
  yet. See "Future global features".
- Re-evaluation is per-type rather than `defonce`-like:
  - same type: the live value and its storage are kept, and only the recorded
    source initial value changes, so the next reset uses the new literal;
  - different type: treated like a procedure signature change. A fresh native
    symbol (`name$gN`) is minted, fresh storage is installed and every body
    is re-emitted, so code emitted against the old layout can never read the
    new storage through the old type. A procedure that still declares the old
    type fails to compile with a clear error.
- `defvar` always initialises on first definition. There is no
  `defvaronce` yet; see "Future global features".
- Globals are bare-named for now, like procedures, so names must not collide
  with a procedure, an extern or another global. Names containing `$` are
  rejected because generated symbols use that suffix.
- A global must be registered before the `defproc` that reads it, because
  procedure bodies are compiled at macroexpansion time. `declare-var` is
  deferred; see "Future global features".
- A full reset or a build-mode switch drops all storage and reinstalls it
  from the source initial values.
- `build-executable!` and `build-library!` link the storage modules of every
  global referenced by the selected procedures, so an offline binary owns its
  globals and has no hot reload.
- Ownership is deferred: memory a `:ptr` global points at is not freed
  automatically and the pointer is lost on a reset.
- A native loop blocks the JVM thread that entered it, so a `reset!` that is
  meant to stop it must come from another thread (for example a `future`).
- Printing is a language feature, not a stdout capture: see "Printing" below.
  A background loop can print to the REPL through a `future`, because the
  print runtime calls back into the JVM on the calling thread and `future`
  conveys `*out*`.

### Printing

`(print ...)` and `(println ...)` are clodin forms, not `extern printf`.
Operands are concatenated without separators, so spaces are written
explicitly; `(println)` with no operands prints just a newline. Each operand
is lowered according to its static type:

- a string literal is written with its known UTF-8 byte length;
- `:ptr` is treated as a null terminated C string and written with `strlen`;
  a null pointer prints nothing instead of trapping;
- `:i64` / `:i32` are formatted with `snprintf` and `%ld` / `%d`;
- `:f64` / `:f32` are formatted with `%g` (an `f32` is extended to `double`
  first);
- `:boolean` writes `true` or `false`;
- `println` appends a newline.

The runtime lives in `clodin.print` as an LLVM module with
`clodin$write$bytes(ptr, i64)` and one formatting helper per type. The compiler only
emits calls to those helpers, so a procedure module is small and the
formatting code exists once.

`clodin$write$bytes` loads a function pointer, `@clodin$write`. In a JIT build the
pointer is a JVM upcall stub that writes the bytes to the calling thread's
`*out*`. The upcall runs on the thread that entered the native procedure, so
the dynamic binding is the caller's: `with-out-str`, CIDER and a `future` that
conveys `*out*` all work. There is no capture lock and no global redirect, so
a long-running procedure never blocks another procedure's output.

In an offline build there is no JVM, so `@clodin$write` is null and
`clodin$write$bytes` falls back to `write(1, ...)`, which is what a standalone
binary wants. The same lowering therefore works in both modes.

`extern printf` remains raw libc output and goes to the process stdout, not
`*out*`. That is the deliberate split: use `println` for REPL-visible output,
and `printf` only when you specifically want C formatting or are writing a
standalone binary.

A native thread that the JVM has never attached is attached for the upcall,
but it has no Clojure binding frame, so it sees the root `*out*`. JVM-created
threads (`future`, `Thread`) carry the binding they were created with.

Formatting is `%g` for floats, so `1.5` prints as `1.5` and large values use
exponent notation. A width/precision form is deferred; see "Future print
features".

### Redefinition

1. Build `body:foo$N` and compare its signature with the current stub.
2. Same signature: add the body module, look up `foo$N`, call `@foo$set`. No
   relinking and no handle rebuild.
3. Different signature: mint a new stub, re-emit every body from its `:ast`
   against the new signature, install them, and rebuild the handles of the
   procedures whose signature changed. The current implementation re-emits
   all bodies, not just the transitive callers, because inferred return
   types must settle across the whole call graph before any body is built.
   A same-signature swap is one atomic setter call and is safe while calls
   run. A signature-change re-emission has a short window where a new
   stub's cell still points at `abort`; that is acceptable for a disruptive
   edit and should be done between audio blocks or while stopped.
4. Validate everything before calling any setter, so a failed redefinition
   leaves the old implementation in place. Partial application is accepted:
   a `defproc` that succeeds earlier in a reload stays applied even if a
   later form fails.

### Signature changes

- Callers are recompiled; signature changes are not rejected. The
  implementation re-emits every body (see "Redefinition") so that inferred
  return types settle to a fixpoint before code generation.
- The registry must check parameter and return types at every call site.
  LLVM does not check them, so an unchecked mismatch silently reinterprets
  values.
- Recompute inferred return types during the walk: a callee's return-type
  change can change a caller's inferred return type, which can force
  re-emission of the caller's own callers.
- A caller without a stored `:ast` (static, external, or otherwise opaque) is
  an error for a signature change, naming the caller.

### Reset

- There is no way to remove a single procedure.
- A full reset disposes the whole JIT, drops every stub, body, global, and
  handle, and rebuilds and loads everything from the registry using the
  current mode. Switching modes is implemented as a reset plus re-emission
  from `:ast`.
- A reset invalidates every JVM downcall handle, every native function
  pointer, and all global state, because globals live in JIT memory.
- Reset is explicit only (never triggered by `require :reload`), requires
  that no native code is executing, and refuses to run while an install is in
  progress.

### State and `let`

- `let` bindings are `alloca`s in the entry block: per-call stack state.
  Mutating that state with `set` is supported and is local to the call; it
  survives loop iterations within one call.
- Per-call state is not preserved across a call and therefore not across a
  redefinition. This is accepted.
- Memory allocated inside a procedure and referenced only by a local is not
  preserved. Persistent state must live in a `def` global or in caller-owned
  JVM buffers passed as `:ptr`.
- An old body that is still running keeps its own stack and any pointers
  stored there; nothing is moved or freed under it.

### Diagnostics

`clodin.inspect` always displays the body currently pointed to by the stub.
Each stub, body and global module gets its own IR and disassembly view.

### Build outputs (AOT)

- `build-executable!` and `build-library!` link the latest compiled body of
  each procedure under its public name and ignore stubs, setters, cells and
  declarations. The resulting binary has no hot reload.
- Both build modes produce the same AOT output for the same registry: the
  current body per name.

### Static procedures (not implemented in this iteration)

- A `defproc` marked `^:static` is emitted as a plain direct symbol with no
  stub and no cell. Calls to it are direct and may be inlined.
- A static procedure cannot be redefined.
- The default kind is `:reloadable` for now; flipping the default to static
  is a later decision, not part of this iteration.

## Registry design after text IR removal

The registry is the source of truth for the module. Each procedure entry
stores (refined by "Hot reload design decisions"):

- `:namespace` — the defining namespace, used by AOT selection.
- `:name` — the public symbol name.
- `:param-names` — the parameter symbols, needed to re-emit the AST.
- `:param-types` — the parameter types.
- `:return-type` — the settled return type (explicit or inferred).
- `:explicit-return-type` — the declared type, or nil when inferred.
- `:kind` — `:reloadable` or `:static`.
- `:stub-symbol` — the stub currently bound to the name; nil in
  monolithic mode.
- `:stub-signature` — the signature of the current stub.
- `:stubs` — a map from signature to stub symbol, for reusing a stub when
  a signature reappears.
- `:sig-counter` — how many signature revisions the name has had.
- `:body-symbol` — the symbol of the current implementation (`foo$N`, or
  `foo` in monolithic mode).
- `:generation` — incremented for every installed body.
- `:ast` — the body forms needed to re-emit the procedure.
- `:bitcode` — in-memory LLVM bitcode generated through the C API.
- `:callees` — a map from callee name to the signature used at the call
  site.

`defproc` evaluation type-checks the body, generates bitcode through
the C API and records the definition. In monolithic mode it marks the
module dirty; `compile-module!` then links every entry's stored bitcode
into one module and JITs it. In reloadable mode it installs the stub on
first definition and a new body otherwise. A signature change re-emits
every body from its AST, because inferred return types must settle across
the call graph before code generation.

LLVM does not validate call signatures across definitions. A call with
an `i64` signature can target a `double` definition and both
`LLVMVerifyModule` and `LLVMLinkModules2` accept it. The registry must
do the signature check itself.

Generating bitcode at `defproc` evaluation still requires callee
signatures to be known. Forward references and mutual recursion need a
declaration form or deferred codegen for unresolved calls.

Per-procedure bitcode duplicates shared globals and named types. When
those are added, use a shared prelude module or
`linkonce_odr`/unique internal names.

Module state should also track `module-version`, `current-jit`,
`procedure-handles`, and retired JIT generations. A build records the
version it started with and only clears dirty entries if the version
did not change.

## Caveats to fix

- [ ] Support or explicitly reject `set` on function parameters.

  Parameters are represented as raw LLVM values (`:parameter? true`), not as
  stack slots. `(set parameter value)` currently emits a `store` through the
  parameter value itself, which produces invalid LLVM IR because, for
  example, `%parameter` is an `i64` and not a `ptr`.

  Choose one:

  - spill each parameter into an `alloca` slot in the entry block so it can
    be mutated like a normal local, or
  - reject `set` on parameters with a clear compiler error.

- [x] Replace stale procedures by a full reset, not per-procedure removal.

  Removing a single procedure is deliberately not supported. A full reset
  disposes the JIT, drops every stub, body, global and handle, and rebuilds
  from the registry. A `defproc` removed from a source file stays registered
  until that reset, because the registry has no source tracking. See "Reset"
  in "Hot reload design decisions".

- [ ] Make module state namespace-aware.

  `procedure-registry`, `module-dirty`, the current JIT generation, and
  the downcall handles are currently global to `clodin.compiler`. Multiple
  namespaces that use `defproc` share one module, so editing one
  namespace can force a full rebuild that includes unrelated
  procedures.

- [ ] Support forward references and mutual recursion with `declare-proc`.

  A procedure can only call procedures registered before it. `declare-proc`
  registers a signature and installs the stub with a null cell, so later
  definitions, direct recursion, and mutual recursion compile. A call
  through the null cell invokes a generated `foo$unbound` body that prints a
  diagnostic and traps. See "Hot reload implementation tasks".

- [x] Decide how compile failures leave the module state.

  A new body is built and verified before any setter runs, so a failed
  redefinition leaves the old body playing. Signature changes snapshot the
  registry and handles and restore them on failure. `module-dirty` stays
  true after a failed monolithic build.

- [ ] Add pointer safety and alignment rules.

  `load`, `store`, and `pointer-add` perform unchecked raw memory access.
  Loads and stores currently rely on LLVM's default ABI alignment, so an
  unaligned raw pointer can produce undefined behavior.

- [x] Guard compilation against concurrent procedure registration.

  `define-proc!` and `compile-module!` both take `compile-lock`, so a
  registration can no longer be missed by a build in progress. Reloadable
  installs are likewise serialized, and the cell swap itself is atomic.

## Hot reload implementation tasks

- [x] Implement the reloadable build mode described in "Hot reload design
  decisions": one stub module per procedure, one body module per compilation,
  setter-based cell updates, acquire/release atomics, eager install, and the
  `:monolithic`/`:reloadable` switch with reset plus re-emission from `:ast`.
  Do not combine or batch stub and body modules. Globals are still pending.

- [x] Add the naming rules: reject `$` in procedure and global names, mint
  `foo$sigK` stubs on signature changes, and use a process-wide monotonic
  counter for body symbols.

- [ ] Add `declare-proc`.

  `(declare-proc name [param type ...] return-type)` registers a signature and
  installs the stub (whose cell starts at the generated `foo$unbound`
  body that calls abort) so a procedure can be called before its body is
  compiled. Declarations are registry entries with `:body nil` and are
  skipped by AOT builds.

- [x] Add `defvar` for globals.

  Implemented in `clodin.globals` plus a `defvar` macro. One storage slot per
  global, its own `globals` module in `:reloadable` mode and linked into the
  procedure module in `:monolithic` mode. Scalar types and `:ptr`;
  atomic acquire/release access by default; a `Global` handle implementing
  `IDeref` and `IAtom2` so `reset!`, `swap!`, `compare-and-set!`,
  `reset-vals!` and `swap-vals!` work. A type change mints `name$gN` and
  re-emits every body. Covered by `test-globals-*` in `clodin.compiler`.
  Superseded the earlier `def` sketch; see "Globals" above.

- [ ] Add static stubless procedures.

  A `^:static` `defproc` emits a direct symbol with no stub and no cell, so
  calls are direct and can be inlined, and redefinition is an error. Do not
  implement yet.

- [x] Measure the module count.

  Loaded `clodin.compiler`: 54 procedures. Switching to reloadable mode
  installs 54 stubs plus 54 bodies in ~176 ms. One body redefinition
  (build + JIT add + setter) is ~2 ms; 20 consecutive redefinitions take
  ~17 ms. A signature change, which re-emits all 54 procedures, takes
  ~66 ms. A monolithic whole-module rebuild takes ~18 ms. Two modules per
  procedure plus one per redefinition is acceptable at this size; the
  no-batching rule does not need revisiting yet.

- [x] Write the acceptance tests.

  Six tests cover: an already compiled native caller sees a new body; a
  redefinition preserves a caller-owned buffer's contents; a signature
  change re-emits callers and updates their inferred types; switching
  between modes works in both directions; a caller body calls the stub
  symbol; and a running native `while` loop observes a swap mid-loop. The
  loop test runs at `:none`, and a flag passed as a raw `:ptr` is still not
  atomic, so a spin-loop flag load through a pointer could be hoisted. A
  `defvar` global does not have that problem: its access is always atomic, so
  `test-globals-native-loop-sees-reset` runs at `:O3`.

## Future global features

Deferred deliberately; everything on this list is a separate iteration.

- [ ] Native compare-and-set and atomic read-modify-write.
  `(compare-and-set-var name expected new)` and `(atomic-add name value)`
  lowered to `cmpxchg`/`atomicrmw`, so a native loop can do a lock-free
  counter update. Until then a native `(set name (+ name 1))` is a
  non-atomic load, add and store: safe against a single writer plus JVM
  `swap!`, but it can lose an increment against a concurrent native writer.
  The storage is already atomic, so only the instruction is missing.

- [ ] `defvaronce`.
  `defvar` always initialises, so re-evaluating one re-runs registration.
  A `defvaronce` would keep an already-bound global untouched, like
  `defonce`, and would be the natural form for a global that should never be
  reset by a reload.

- [ ] `declare-var`.
  Procedure bodies are compiled at macroexpansion time, so a global must be
  registered before the `defproc` that reads it. `(declare-var name type)`
  would register the type and storage without an initial value, so a global
  can be forward declared below its first user. Calls would see zero or nil
  until the real `defvar` initialises it.

- [ ] Aggregate global types.
  Buffers, structs and arrays as global storage. Needs the Odin-style buffer
  type first, then a constant-expression form for the initializer.

- [ ] Namespace-qualified global symbols.
  Globals are bare-named, so two namespaces cannot both define `counter`.
  This is blocked on the general namespace-aware module state, the same
  blocker as procedures.

- [ ] Ownership and freeing.
  A `:ptr` global can hold malloc'ed memory but nothing frees it, and a
  reset loses the pointer. Needs a designated owner or a finalizer
  convention.

- [ ] Watches and validators.
  Requires implementing `IRef`. A native write can never notify a JVM
  watch, so this is only meaningful for a global that native code never
  writes. Validators would apply to JVM writes only.

## Future print features

- [ ] Width, precision and padding.
  Floats currently use `%g`. A format option, for example
  `(println (format "%.2f" value))`, would allow fixed decimals and column
  alignment. The runtime already has the formatting helpers; only the surface
  form and the format-string lowering are missing.

- [ ] Print an arbitrary byte range.
  `clodin$print$bytes` exists but is only used for string literals. Expose it
  as `(print-bytes pointer length)` for buffers that are not null terminated.

- [ ] Print aggregate values.
  Structs, arrays and the Odin-style buffer type, once those types exist.

- [ ] A `format` function returning a string.
  Useful for building a message before printing it, and for tests that want
  the formatted text without touching `*out*`.

## Porting the print runtime to clodin

`clodin.print` builds the runtime with the LLVM C API because it needs three
things the language does not have yet. The goal is to write the runtime as
ordinary `defproc`s in a `clodin.runtime` namespace and delete the hand-built
runtime module, so the printing code is no longer a special case the compiler
maintains.

### What stays in Clojure

`write-bytes!`, the upcall stub and `writer-address` create a Panama upcall
stub and write to a `java.io.Writer`. Those cannot move: they are the JVM side
of the boundary. Only the native half moves.

### The three blockers

1. `if` lowers to `llvm.select`, so both branches are evaluated and neither can
   have a side effect. `clodin$write$bytes` needs "if the pointer is null call
   `write`, else call the pointer", which must execute exactly one branch.
2. There is no stack scratch buffer, so `snprintf` has nowhere to write. `let`
   binds one scalar into a hoisted slot; it does not give an addressable block.
   `malloc`/`free` would work but allocates per print and a shared global buffer
   would race between threads.
3. There is no indirect call, so a `:ptr` holding a function address cannot be
   invoked from clodin source.

### Mapping of runtime functions

| runtime function | needs | after phases |
| --- | --- | --- |
| `clodin$print$bool` | Phase 1 | clodin |
| `clodin$print$bytes` | none | clodin |
| `clodin$print$newline` | none | clodin |
| `clodin$print$cstr` | Phase 1 (null guard) | clodin |
| `clodin$print$i64` / `i32` / `f64` / `f32` | Phases 1, 2 | clodin |
| `clodin$write$bytes` | Phases 1, 2, 3 | clodin |
| upcall stub, `write-bytes!` | JVM | stays Clojure |

### Phase 1: branching `if`

`if` becomes a real branch. Statement arms (arms with no value) are allowed, so
`(if condition (do-side-effect) (other-side-effect))` is legal, and a value
`if` still works. Options are recorded under "Branching `if` options" below.

Today statement arms are not rejected, they crash: `(if flag (store ...)
(store ...))` reaches `build-select` with a nil operand and throws a
`NullPointerException` from inside the LLVM downcall. Phase 1 fixes that by
construction, and the new `if` must report a clear error for arms of different
types.

Acceptance: `clodin$write$bytes`, `clodin$print$bool` and
`clodin$print$cstr` can be written as `defproc`s and produce the same output as
the current runtime; `(if condition (store ...) (store ...))` works; a plain
value `if` still compiles to a single value; existing tests pass.

### Phase 2: stack scratch buffer

A form that yields a `:ptr` to an uninitialised stack block of a given byte
size, hoisted to the entry block so a loop does not grow the stack:

    (let [buffer :ptr (alloca 32)]
      ...)

Options:

- `(alloca byte-count)`: one form, yields `:ptr`, composes with `let`.
  Alignment is the natural maximum (16) so it can hold doubles.
- `(with-scratch [name size] body...)`: scoped and non-escaping, but adds a
  second binding form next to `let` and is redundant with a hoisted `alloca`.
- Odin-style buffer type with a length header: deferred to the buffer feature.

Recommendation: `(alloca byte-count)`, hoisted to the entry block. It composes
with `let`, needs no new binding form, and the entry-block hoist gives the
loop-safety that `with-scratch` would otherwise have to guarantee.

Acceptance: the integer and float print helpers can be written as `defproc`s
using `(alloca 32)` and `snprintf`, with no `malloc`, and a print inside a
`while` does not grow the stack per iteration.

### Phase 3: indirect call

A form that calls a function pointer:

    (call-pointer clodin-write :void [:ptr :i64] data length)

Options:

- `(call-pointer pointer return-type [param-types ...] args...)`: explicit and
  small; the type is written at the call site.
- A first class function-pointer type `:fn` so a `:ptr` can be called directly:
  larger type-system change, and every place that handles `:ptr` would need to
  distinguish the two.

Recommendation: `call-pointer`. It is one compiler form and one `build-call`
with a loaded pointer, and it does not touch the type system.

Acceptance: `clodin$write$bytes` can be written as a `defproc`, including the
null check that falls back to `write(1, ...)`.

### Phase 4: move the runtime into clodin

- New namespace `clodin.runtime` with the print helpers as `defproc`s.
- The writer pointer becomes a global, `(defvar clodin-write :ptr nil)`, used by
  `clodin$write$bytes` as `(call-pointer clodin-write ...)`. This reuses the
  globals feature instead of a hand-built global.
- The compiler loads `clodin.runtime` before any user namespace and, after
  creating or resetting the JIT, `reset!`s `clodin-write` to
  `(print/writer-address)`. A monolithic rebuild reinitialises globals, so the
  reset has to happen on every build; an AOT build leaves it nil and the
  fallback writes to file descriptor 1.
- Delete `runtime-module`, the `build-*` helpers and the `install-runtime!`
  module plumbing. `clodin.print` keeps only the upcall stub and the writer.

Acceptance: `(println ...)` from a procedure, from `with-out-str`, from a
`future`, and from a standalone binary all work with no runtime module built by
the LLVM C API; the only remaining C-API-built module is the globals and
procedure modules the compiler already produces.

### Branching `if` options

The shared shape is: compile the condition, `condbr` to a then block and an
else block, compile each arm, join at a merge block. The options differ in how
the arms' values are combined and in how the surface form is split.

- **A. Add `when` only; keep `if` pure.** `(when condition body...)` is a real
  branch with no value. Smallest change, no value plumbing, and `if` keeps its
  documented select semantics.
  - Pros: no phi, no result slot, no change to any existing `if`; about as small
    as `compile-while`; low risk to the 44 existing tests.
  - Cons: `if` stays surprising for anyone coming from Clojure; a value that
    depends on a condition needs a `let` slot plus a `when`, and the slot has to
    be initialised; the runtime port needs the two-arm null check written as two
    `when`s or an early-return style.

- **B. `if` branches, value combined with a `phi` node.**
  - Pros: Clojure semantics; side effects allowed in either arm; one form.
  - Cons: needs `LLVMBuildPhi`/`LLVMAddIncoming` wrappers (the C API has them,
    the bindings generated them, but `clodin.llvm-api` does not wrap them yet);
    a phi must be the first instruction in the merge block, so the merge block
    must stay empty until the phi is built; an arm whose block already has a
    terminator needs a check or the extra `br` is invalid IR.

- **C. `if` branches, value combined through a hoisted stack slot.** Same control
  flow as B, but each arm stores its value into an alloca and the merge block
  loads it.
  - Pros: no phi API surface; works when one arm's final block shape differs,
    because the store is placed at the end of each arm's own block; reuses
    `hoisted-alloca`, which `let` already uses; `mem2reg` turns it back into a
    phi at `:O1` and above.
  - Cons: at `:none` there is an extra alloca, store and load per value `if`;
    a slot is allocated even when one arm is the only value-producing one.

- **D. Unified `if`: branch always; combine a value only when both arms produce
  one.** This is B or C plus the statement case, so `(if c (set x 1) (set x 2))`
  and `(if c 1 2)` are both legal.
  - Pros: one form covers both uses; matches Clojure; the runtime port needs
    only this.
  - Cons: the compiler must handle the "no value type" case, which means
    `compile-if` has two paths; the merge block is entered with `:result-value
    nil` and `:return-type nil`, and `compile-body` has to treat that as a
    statement result instead of a missing value.

- **E. Keep `if` as select and add `when` plus `when-else`.** Two statements
  and no value form.
  - Cons: `when-else` is close enough to `if` that two forms with subtly
    different evaluation rules are a trap.

- **F. Make `if` branching and require both arms to produce a value, adding
  `when` for statements.**
  - Pros: each form has one meaning.
  - Cons: `(if c (store ...) (store ...))` is rejected, so the runtime's null
    check still needs `when`, and users hit the rejection often.

Recommendation: **D with C's value plumbing**. It makes `if` mean what it means
in Clojure, gives the runtime everything it needs from one form, and avoids new
LLVM API surface. If the extra load and store at `:none` measurement matters
later, switching the value case to a phi is a local change behind the same
surface form.

## Unimplemented speedups

- [ ] Use a faster optimization level during development.

  The LLJIT target machine uses its default optimization level for every
  build. A lower level during development would make REPL iteration
  faster, with the default reserved for final builds.

- [ ] Skip the JIT rebuild when the module bitcode is unchanged.

  The dirty flag avoids repeated compiles without new registrations, but
  a reload of identical code still rebuilds the whole module. Hash the
  linked module bitcode and reuse the current JIT and handles when the
  hash matches.

- [ ] Cache compiled JIT modules by module bitcode hash.

  Even when the bitcode differs, previously compiled module hashes could
  be reused when returning to an earlier version of the code.

- [ ] Build per-namespace modules and handles.

  This would avoid recompiling unrelated namespaces and would make reloads of
  one namespace much cheaper. It requires the namespace-aware module state
  listed above.

- [ ] Add incremental compilation for monolithic mode.

  Reuse per-procedure bitcode for unchanged procedures and re-emit only
  procedures whose body or callee signatures changed. In reloadable mode
  this is superseded by the stub design; re-emitting callers is still
  needed when a signature changes.

- [ ] Compile asynchronously or on demand in the background.

  Lazy compilation currently blocks the first call while LLVM compiles
  the module. A background compile could be started after registration
  and joined only when a procedure is actually called.

## Next features

- [ ] Add the Odin-style buffer/array type with a length stored in the first
  memory slot.
- [ ] Add `for` as a macro that lowers to `let` + `while`.
