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

- `def` defines a global in its own `global:g` module. Globals are the
  supported way to keep state across redefinitions: buffers, oscillator
  phases, voice tables, and pointers to malloc'ed memory.
- A global is not declared in a procedure's stub module. It has its own stable
  module so that a procedure stub never owns state.
- Initial form is `(def g :i64 0)`. Start with scalar types and `:ptr`;
  aggregate types are deferred.
- Semantics are `defonce`-like: a redefinition or reload leaves an
  already-bound global alone, both its module and its value. Explicit mutation
  uses a separate store/set form (still to be added).
- Globals use the same atomic acquire/release access as the cell when another
  thread may write them: a loop that reads such a global must not cache it.
  A global that is only written by its defining thread can use plain
  accesses.
- Ownership is deferred: memory held by a global is not freed automatically
  and is lost on a reset. This is documented behaviour.

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

- [ ] Add `def` for globals.

  One stable `global:name` module per global, added once and never re-created
  by redefinition. Start with scalar types and `:ptr`, `(def g :i64 0)`,
  `defonce`-like semantics, and atomic acquire/release access by default when
  another thread may write the global. Aggregate types,
  explicit mutation, and ownership/free are deferred.

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
  loop test runs at `:none` because user pointer loads are not yet volatile,
  so a spin-loop flag load could otherwise be hoisted.

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
