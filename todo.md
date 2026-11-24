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

Symbols are qualified; see "Qualified names" below for the rule and the
separators. Relative to a mangled procedure symbol `foo`:

- The first stub for a name is `foo`. A signature change mints a new stub
  `foo$sigK`, where K counts signature revisions for that name. The registry
  keeps `name -> {signature -> stub}`. Old stubs stay until a reset.
- Bodies are `foo$N`, where N is a process-wide monotonic counter. A body
  symbol is never reused. In `:monolithic` mode the body is emitted under the
  procedure's symbol `foo` with no version number.
- Generated names use `$` suffixes, and a short name may not contain `$` or
  `.`, so a Clojure symbol can never collide with a generated stub, body,
  setter, cell or global symbol.

### Qualified names (done)

Names are qualified: every procedure and global is registered under
`namespace/name` and its native symbol carries the namespace as
`namespace.name`. The prefix rule below was written when names were bare, so
read `foo` everywhere in this section as the mangled symbol
`clodin.compiler.foo`, `foo$N` as `clodin.compiler.foo$N`, and so on.

Decisions:

- **Why.** Two namespaces must be able to define the same short name, and the
  JIT looks symbols up by name, so the namespace has to be in the symbol.
  C++ has the same requirement and solves it the same way: namespaces vanish
  at link time and the mangled symbol carries them (`ns::foo` becomes
  `_ZN2ns3fooEv`), so `a::foo` and `b::foo` cannot collide. There is no ADL
  analogue and none is wanted: clodin has no overloading, so a reference is
  resolved by namespace, not by argument type.
- **Separators.** The registry key uses `/` (`clodin.runtime/print-i64`); the
  native symbol uses `.` (`clodin.runtime.print-i64`). `$` stays reserved for
  generated suffixes (`foo$3`, `foo$sig2`, `counter$g1`), and a short name may
  not contain `$` or `.`, so `clodin.runtime.print-i64$3` can only be read one
  way. Both rules are in `clodin.names`.
- **Resolution.** A symbol with a slash is used as written. An unqualified
  symbol refers to the defining namespace first, then to `clodin.core`, and
  nothing else. There is no `require`, `refer` or alias form yet, so a
  procedure in another namespace is called by its qualified name. Clodin has
  no overloading, so namespace resolution is the whole rule and there is no
  argument driven lookup to add later.
- **Offline builds drop the prefix.** A JIT build needs the qualified symbol,
  but the system linker exports the plain names and the entry point has to be
  exactly `main`, so `procedures-for-linking` binds `*symbol-style*` to
  `:plain`. The consequence is that short names must be unique across the
  procedure set of one offline build, which is what they were before this
  change.
- **Shadowing is allowed.** `clodin.core` is a namespace like any other and
  holds no procedures yet, because `print` and `println` are compiler forms,
  not procedures: they are variadic and lowered per operand type, so they
  cannot be written as `defproc`s. A definition may shadow a built in form,
  but an unqualified reference always means the form, so the procedure is then
  reachable only by its qualified name. `defproc` and `defvar` warn when that
  happens instead of rejecting it.
- **Globals too.** Same rule, same mangling: `clodin.runtime/clodin-write` is
  `clodin.runtime.clodin-write` natively. Globals always keep the qualified
  symbol, even in an offline build, because nothing has to export them.
- **REPL leniency.** `invoke-procedure` and the `clodin.inspect` helpers accept
  a short name when exactly one registered procedure has it, and report the
  candidates when it is ambiguous. The language itself is always strict.
- **Not part of this change.** Module state is still global to
  `clodin.compiler`: one module, one JIT, one handle table. Qualified names do
  not make a reload of one namespace cheaper; that is still the separate
  namespace-aware module state item. `:callees` keys and `procedure-handles`
  are qualified names now, so that work starts from a consistent base.

Covered by `test-qualified-names` in `clodin.compiler` and
`clodin.names`'s own tests.

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

The runtime lives in `clodin.runtime` as ordinary procedures: `write-to-out`,
`print-bytes`, `print-newline`, and one formatting helper per type. The
compiler lowers a print to calls to those procedures, so a procedure module
only declares them and the formatting code exists once. The runtime used to be
an LLVM module built through the C API and was ported to clodin in "Porting the
print runtime to clodin"; `clodin.print` now holds only the JVM side.

`write-to-out` loads the writer pointer from the `clodin-write` global. In a
JIT build the compiler points that global at a JVM upcall stub that writes the
bytes to the calling thread's `*out*`. The upcall runs on the thread that entered the native procedure, so
the dynamic binding is the caller's: `with-out-str`, CIDER and a `future` that
conveys `*out*` all work. There is no capture lock and no global redirect, so
a long-running procedure never blocks another procedure's output.

In an offline build there is no JVM, so `clodin-write` stays null and
`write-to-out` falls back to `write(1, ...)`, which is what a standalone binary
wants. The same lowering therefore works in both modes.

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

- `:namespace` — the defining namespace, used by AOT selection and to build
  the native symbol.
- `:name` — the short name, without the namespace.
- The registry key is the qualified name `namespace/name`; the native symbol
  is `namespace.name`.
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

  Registry keys, `:callees` keys and handle keys are qualified names now, so
  the naming half of this is done. What is still shared is the state itself:
  `module-dirty`, the current JIT generation and the downcall handles are
  global to `clodin.compiler`, and every namespace compiles into one module.
  Editing one namespace still forces a full rebuild that includes unrelated
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

- [x] Namespace-qualified global symbols.
  Globals are registered as `namespace/name` with the native symbol
  `namespace.name`, the same rule as procedures. Two namespaces can both
  define `counter`. It turned out to need only the naming refactor, not the
  namespace-aware module state. See "Qualified names".

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

- [x] Print an arbitrary byte range.
  `clodin.runtime/print-bytes` is a public procedure, so a buffer that is not
  null terminated is printed with `(clodin.runtime/print-bytes data length)`.
  It is not a compiler form, so `print` does not dispatch to it.

- [ ] A `not`, and so a `when-not`.
  `print-cstr` spells its null test `(when (= (is-null value) false) ...)`
  because there is no way to negate a `:boolean`. A `not` form is one `xor`
  with true and would remove the only awkward line in the runtime.

- [ ] Print aggregate values.
  Structs, arrays and the Odin-style buffer type, once those types exist.

- [ ] A `format` function returning a string.
  Useful for building a message before printing it, and for tests that want
  the formatted text without touching `*out*`.

## Porting the print runtime to clodin (done)

`clodin.print` used to build the runtime with the LLVM C API because it needed
three things the language did not have. The runtime is now ordinary `defproc`s
in `clodin.runtime` and the hand-built runtime module is gone, so the printing
code is no longer a special case the compiler maintains. The phases below are
kept as the record of what each blocker was and how it was removed.

### What stays in Clojure

`write-bytes!`, the upcall stub and `writer-address` create a Panama upcall
stub and write to a `java.io.Writer`. Those cannot move: they are the JVM side
of the boundary. Only the native half moves.

### The three blockers

1. `if` lowers to `llvm.select`, so both branches are evaluated and neither can
   have a side effect. `write-to-out` needs "if the pointer is null call
   `write`, else call the pointer", which must execute exactly one branch.
   Resolved by Phase 1, a real branching `if`, plus statement position in
   "Statement position and `:void`".
2. There is no stack scratch buffer, so `snprintf` has nowhere to write. `let`
   binds one scalar into a hoisted slot; it does not give an addressable block.
   `malloc`/`free` would work but allocates per print and a shared global buffer
   would race between threads. Resolved by Phase 2, `(stack-allocate size
   alignment)`.
3. There is no indirect call, so a `:ptr` holding a function address cannot be
   invoked from clodin source. Resolved by Phase 3, `(pointer-call pointer
   [param-types] return-type args...)`, together with `(is-null pointer)` for
   the null check.

### Mapping of runtime functions

| runtime procedure | needed | landed as |
| --- | --- | --- |
| `clodin.runtime/print-bool` | Phase 1 | clodin |
| `clodin.runtime/print-bytes` | none | clodin |
| `clodin.runtime/print-newline` | none | clodin |
| `clodin.runtime/print-cstr` | Phase 1 (null guard) | clodin |
| `clodin.runtime/print-i64` / `i32` / `f64` / `f32` | Phases 1, 2 | clodin |
| `clodin.runtime/write-to-out` | Phases 1, 2, 3 | clodin |
| upcall stub, `write-bytes!` | JVM | stays Clojure in `clodin.print` |

### Phase 1: branching `if` (done)

`if` is a real branch with Clojure's semantics: only the taken arm runs, so an
arm may have side effects. Both arms producing a value of the same type gives a
value `if`, merged with a phi in the join block. Both arms being statements
gives a statement `if`. Mixing them is a clear compile error.

`(when condition body...)` is added as a statement branch with no else arm. It
produces no value, because clodin has no nil to return when the condition is
false.

Design notes:

- The phi is built in the join block before anything else is emitted there, as
  LLVM requires a phi to be the block's first instruction.
- Each arm is branched from its own final block, because a nested branching
  form inside an arm can move the builder past the arm's first block.
- An arm that already ends in a terminator is treated as diverging: it
  contributes no phi incoming and its type is compatible with the other arm's.
  If both arms diverge, the join block gets `unreachable` and the form itself
  diverges.
- `compile-body` stops emitting after a form that sets `:terminated?`, and
  `build-procedure!` skips its implicit `ret` for a terminated body. Nothing
  sets `:terminated?` yet; this is the groundwork for `return`.
- New wrappers in `clodin.llvm-api`: `block-terminator`, `block-terminated?`,
  `build-phi`, `add-incoming`.

Acceptance met: statement `if` writes through a pointer, the untaken arm does
not run, nested and loop-hosted `if` work, a value `if` compiles to a phi,
arm-type mismatches are reported clearly, and `when` runs its body only on the
true path. Covered by `test-if`, `test-if-value-uses-a-phi`,
`test-if-arm-mismatch` and `test-when`.

#### Early termination stays out of scope

Phase 1 deliberately does not add `return`, `break`, `continue` or labelled
loops. None of them is needed for the runtime port, and Clojure has no early
termination, so there is no habit to preserve. The terminator guard described
under "Branching `if` options" is specified now precisely so that they can be
added later without revisiting `if`.

For the record, what the two reference languages provide:

- Odin: `return`, `break [label]`, `continue [label]`, `fallthrough`,
  `defer`, `errdefer`, `or_return`, `#must_tail`, `panic`, `#panic`,
  `unreachable()` (a `-> !` procedure), and the per-procedure attributes
  `#no_bounds_check` / `#bounds_check`. See `odin.ebnf` ("Branch & Defer":
  `BranchStmt = "break" [ IDENT ] | "continue" [ IDENT ] | "fallthrough"`) and
  `base/builtin/builtin.odin`, which declares `unreachable :: proc() -> !`.
- Zig: `return`, `break :label value`, `continue :label`, labelled blocks and
  loops, `while` with a continue expression and an `else` clause, `defer`,
  `errdefer`, `try`/`catch`/`orelse`, `unreachable`, the `noreturn` type, and
  `@panic`/`@trap`/`@compileError`, all of which return `noreturn`. See
  `doc/langref.html.in` ("noreturn" lists `break`, `continue`, `return`,
  `unreachable` and `while (true) {}` as its values) and
  `doc/langref/test_labeled_break.zig`.

Neither language has `goto`.

#### Is early termination needed for peak performance?

In two specific shapes, neither of which is reachable in clodin yet. A third
shape, searching for a match, was claimed here earlier and is corrected below.

1. Hand-unrolled or vectorized scans: the correction case. Both standard
   libraries implement "find the first match" as a per-block SIMD compare:
   Odin's `bytes.index_byte` (`core/bytes/bytes.odin`) does `return i + ...`
   from inside the block loop after `simd.reduce_or`, and Zig's
   `mem.findScalarPos` (`lib/std/mem.zig`) does
   `return i + std.simd.firstTrue(matches).?` inside `while (true)`.

   This was claimed here earlier as an early-termination win. It is not.
   Measured:

   - clodin's `find-i64`, written with a `found` flag because there is no
     `return`, compiles at `:O3` to `ldr / cmp / b.eq` with no stack traffic:
     LLVM turns the flag test into a direct loop exit, so the flag costs one
     `add` and nothing more.
   - GCC 15.2 vectorizes a search loop *with* the early exit, and does it for
     both spellings: `find_return` and the flag equivalent both contain
     `cmeq v31.2d, v31.2d, v29.2d` + `umaxp` + `cbz`. So "the vectorizer
     refuses an exit in the middle" is an LLVM 21 limitation, not a law.
   - LLVM 21 does not vectorize either spelling: clodin's `find-i64` has 0
     vector operations. That is what the explicit SIMD rewrite fixes, and it
     fixes it regardless of how the exit is spelled.

   So for a search loop, early termination is ergonomics, not performance. It
   saves one live variable and the two-`set` flag dance, and that is the whole
   argument.
2. Assertions that remove checks rather than control flow: Odin's
   `#no_bounds_check` and Zig's `unreachable`/`@setRuntimeSafety(false)`.
   These are the same family as the terminator guard in that they let the
   optimizer assume a branch is impossible.
3. Guaranteed tail calls. Odin's `#must_tail` lowers to LLVM
   `musttail` (`src/llvm_backend_proc.cpp`), which is a stronger guarantee
   than a `while` loop gives for mutual recursion and is load bearing in
   interpreter dispatch loops. A clodin `while` state machine covers the
   same ground for now.

For ordinary scalar code the optimizer normalizes `if (c) return a; ...` into
the same control flow as a flag-based loop, and LLVM handles multi-exit loops
fine. So early termination is ergonomics, plus the two assertion and tail call
shapes above. It is not a prerequisite for the vectorized search and it is not
a prerequisite for the SIMD design under "Vectorization".

### Phase 2: stack scratch buffer (done)

A form that reserves an uninitialised stack block of a given byte size and
alignment and yields a `:ptr` to it, hoisted to the entry block so a loop does
not grow the stack:

    (let [scratch :ptr (stack-allocate 32 16)]
      (sprintf scratch "value = %ld" value))

Decisions:

- The form is `(stack-allocate byte-count alignment)`. Both arguments are
  compile time integer literals, because the block is a single `alloca`:
  neither its size nor its alignment can depend on a runtime value.
- There is no default alignment. This is a low level feature, so the caller
  states both numbers. `byte-count` must be at least 1, `alignment` must be a
  power of two no greater than 65536, and both must fit in a long.
- The name is `stack-allocate`, not `alloca`. `alloca` is the C/LLVM jargon
  abbreviation, and C's `alloca` promises different semantics: a runtime size
  and a fresh block per execution, so it grows the stack inside a loop. The
  verb form also matches the existing object-first operator names
  (`pointer-add`, `buffer-get`, `buffer-set`), where the noun names the source
  or target and the preposition stays implicit.
- The form names the operation and yields a plain `:ptr`, so `buffer` and
  `array` stay free for the aggregate types. See "Next features".
- The alloca is `[byte-count x i8]` with an explicit alignment, so the caller
  can hold a `double` (alignment 8), a SIMD value, or nothing in particular
  (alignment 1). LLVM only guarantees that the returned pointer is aligned to
  at least that boundary; it does not promise the block is padded to it, so
  writing past `byte-count` is out of bounds even when the frame pads the slot.
- Hoisting is what makes it loop safe: one block per call, the same address on
  every iteration. A `with-scratch` form would be redundant, and a point-of-use
  alloca would grow the stack per iteration and need `stacksave`/`stackrestore`.
  The flip side is that a loop reusing the block must reinitialise what it
  reads, because the previous iteration's bytes are still there.
- The block is uninitialised. Nothing zeroes it, matching LLVM and C.
- The block dies with the call, so a `:ptr` returned from a procedure, stored
  into a global, or kept past the call is dangling. Per-call scratch that has
  to outlive the call belongs in caller-owned memory passed in as a `:ptr`.
- Diagnostics are compile time: a non-literal, a zero byte count, a
  non-power-of-two alignment, an alignment above 65536, and a wrong arity each
  name the offending argument.

Implementation: `compile-stack-allocate` in `clodin.compiler`, plus an
alignment argument on `llvm/hoisted-alloca`, which `let` still calls without
one and therefore keeps the type's ABI alignment.

Acceptance met: the integer and float print helpers can be written as
`defproc`s using `(stack-allocate 32 16)` and `snprintf`, with no `malloc`; a
`stack-allocate` inside a `while` compiles to one alloca in the entry block, so
the loop stores the same address every iteration and the stack does not grow.
At `:O3` on linux-aarch64, `stack-scratch-record-addresses` emits one
`sub sp, sp, #0x20` and the vectorizer broadcasts that single `sp` value.
Covered by `test-stack-allocate`, `test-stack-allocate-literal` and
`test-power-of-two` in `clodin.compiler`.

### Phase 3: indirect call (done)

A form that calls a function pointer:

    (pointer-call clodin-write [:ptr :i64] :void data length)

Decisions:

- The form is `(pointer-call pointer [param-types] return-type args...)`:
  pointer first, then the signature, then the arguments. Parameter types come
  before the return type to match `extern`, which is `(extern name [params]
  return-type ...)`, so there is one order to remember.
- The name is `pointer-call`, not `call-pointer`, for the same reason
  `stack-allocate` beat `alloca`: the family is object first, verb second
  (`pointer-add`, `buffer-get`, `buffer-set`, `stack-allocate`), and the noun
  names the thing being called.
- The signature is written at the call site because a `:ptr` carries no type. A
  `:fn` type was rejected: it is a larger type-system change, and every place
  that handles `:ptr` would have to distinguish the two.
- The signature must be literal: a literal vector of parameter types and a
  literal return type. Parameter types are checked against
  `:boolean :i64 :i32 :f64 :f32 :ptr`, a return type of `:void` (or `nil`, as
  `extern` allows) means the call produces no value, and `:void` is rejected as
  a parameter. The argument count must equal the vector length and every
  argument type is checked against the written one.
- The callee's *real* signature cannot be checked: LLVM trusts the function
  type it is handed and silently reinterprets values on a mismatch. That is
  documented rather than prevented, and it is why the written form is checked
  as strictly as it can be.
- A `:void` call produces no value, so binding one is a compile error rather
  than a mystery `i64` from the return register.
- No variadic pointer calls. `extern` has `:varargs`, but a `:ptr` to `printf`
  cannot be called; the print runtime only needs the fixed-signature `write`
  fallback, which goes through `extern`, not through a pointer.
- No null guard. A null pointer jumps to address zero and takes the process
  down; the caller tests first. Silently calling `abort` instead would hide the
  mistake behind a call that appears to return.
- `(is-null pointer)` was added for exactly that test: an `icmp eq` against
  address zero, yielding a `:boolean`. A `:ptr` is the only clodin type that
  can hold a null, so any other operand is a compile error. The print runtime
  needs it twice: `write-to-out` checks its writer pointer before calling
  through it, and `print-cstr` checks the string it was handed.
- The result type drives `expression-type`, so a procedure ending in a
  `pointer-call` infers the declared return type and `:void` means no value.
- A `pointer-call` records no `:callees` entry, because there is no name to
  re-emit against. Globals referenced inside the pointer expression are
  recorded by the ordinary compile path, so AOT selection and re-emission stay
  correct.
- `:boolean` in a pointer signature lowers to `i1`, while the JVM boundary uses
  a one-byte layout. That mismatch already exists for `extern` and procedure
  signatures, so it is allowed for consistency and not promised for C `_Bool`.
- Shadowing is allowed, so `pointer-call` and `is-null` are in
  `compiler-forms`: a procedure may take those names, but an unqualified
  reference still means the form and `defproc` warns. See "Qualified names".

Acceptance met: `write-to-out` is written as a `defproc`, including the null
check that falls back to `write(1, ...)`; see "Phase 4". Covered by
`test-pointer-call`, `test-pointer-call-signature` and `test-is-null` in
`clodin.compiler`, which reach a real libc address (`strlen`, resolved from the
JVM side with the default lookup) and a JVM upcall stub in both directions. The
null pointer trap itself is documented, not tested: triggering it kills the JVM.

Deferred: a native callback that points at a *clodin* procedure. There is no
address-of-procedure form, so `pointer-call` reaches JVM upcall stubs and
resolved C symbols only. See "Next features".

### Phase 4: move the runtime into clodin (done)

The print runtime is ordinary clodin source in `clodin.runtime`, and the
hand-built LLVM runtime module is gone.

Decisions:

- **Names.** The helpers live in `clodin.runtime` and are called by their
  qualified names, because names are qualified now. `clodin.core` holds no
  procedures: `print` and `println` are compiler forms (variadic, lowered per
  operand type), so they cannot be procedures, and no core procedure is needed.
- **The two halves.** `clodin.runtime` is the native half: `write-to-out`,
  `print-bytes`, `print-newline`, `print-i64`, `print-i32`, `print-f64`,
  `print-f32`, `print-bool` and `print-cstr`, all `defproc`s. `clodin.print` is
  the JVM half and keeps only the upcall stub and `writer-address`, which are
  the two things that cannot be written in clodin.
- **`write-to-out` versus `print-bytes`.** The name that crosses the boundary
  says where it goes (`write-to-out`), so it is not confused with
  `print-bytes`, which writes a byte range to it. Everything routes through
  `write-to-out`:

      (defproc write-to-out [data :ptr length :i64] :void
        (if (is-null clodin-write)
          (write (to-i32 1) data length)
          (pointer-call clodin-write [:ptr :i64] :void data length)))

  The `if` is a statement `if`: one arm returns `:i64` and the other returns
  nothing, which is exactly what statement position allows.
- **The writer is a global.** `(defvar clodin-write :ptr nil)` in
  `clodin.runtime`, so the hand-built `@clodin$write` global is gone and the
  write is an atomic acquire load. The compiler stores the JVM upcall address
  in it with one idempotent `install-writer!`, called after the storage
  addresses are recorded and before the handles are published: from
  `recompile-module!` for a monolithic build, and from `install-globals!` for a
  reloadable one. It has to run on every build, because a monolithic rebuild
  reinitialises globals from their source initial value and a reset drops them.
  It runs *after* the addresses are recorded on purpose: writing through a
  global with no address would ask `ensure-storage!` for one, and that hook
  compiles the module again inside the build that is already running.
- **Loading order.** `clodin.runtime` requires `clodin.compiler` for `defproc`,
  and the compiler needs the helpers registered before it compiles any body
  that prints, so the runtime is loaded lazily: `define-proc!` calls
  `require-runtime!` for any namespace but `clodin.runtime` itself, and the
  flag is set before the `require` so the runtime's own `defproc`s cannot
  recurse. A top-level require in either direction would be a cycle.
- **Print lowering is an ordinary call.** `compile-print-operand` emits a call
  to `clodin.runtime/print-*` through the same `emit-procedure-call` the
  language uses, so a print gets the signature check, the stub target in
  reloadable mode, and a `:callees` entry. The callee entry is what makes
  `namespace-closure` pull `clodin.runtime` into an offline build; the old
  special case that linked a runtime module in unconditionally is gone, and
  `link-procedures` lost its `writer-address` parameter.
- **AOT.** The writer stays nil offline, so `write-to-out` falls back to
  `write(1, ...)`. A library build exports `clodin.runtime.clodin-write` like
  any other global, so a host could install its own writer; nothing does that
  automatically, and it is documented rather than tested.
- **`print-cstr` needs a negated test.** `(when (= (is-null value) false) ...)`
  is the spelling until a `not` (and so a `when-not`) exists. Recorded under
  "Future print features".
- **Two casts stay.** `(write (to-i32 1) ...)` because a Clojure integer
  literal is `:i64` and `write` takes `:i32`; `to-float` was extended to accept
  `:f32` so `print-f32` can widen through it rather than formatting twice.
- **`snprintf` is clamped.** It returns the length it would have written, so a
  truncated format would make `print-bytes` read past the 32-byte scratch
  block. `(min (to-i64 length) 32)` keeps the read inside the block. `%ld` and
  `%g` cannot reach 32 bytes today, so this was a latent bug, not a live one.
- **Literal lengths.** `print-bool` passes 4 and 5 for `"true"` and `"false"`,
  because there is still no way to ask a string literal for its length. The
  alternative would be a `strlen` call per print.
- **Live sessions must reset.** Registry bitcode compiled against
  `clodin$print$*` no longer has a matching definition, so an already-running
  REPL has to `reset-module!` or restart after this change. No aliases were
  kept.

Covered by `test-println-writes-to-out`, `test-extern-printf`,
`test-background-print-goes-to-conveyed-out`, `test-runtime-writer`,
`test-runtime-writer-does-not-recompile` and `test-runtime-in-standalone-build`
in `clodin.compiler`, plus the existing boolean, f32, cstr and library tests.

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

Decision: **D, with the phi plumbing from B.** `if` means what it means in
Clojure, and the value case uses a phi so there is no stack slot per value
branch. `nil` arm types mean "statement". `when` was added as a statement
branch.

### Statement position and `:void` (done)

Amends the rule above: mixing a value arm with a statement arm is an error **in
value position only**. In statement position both arms are compiled as
statements, so their values are discarded and the arms need not agree in type.
That is what the print runtime needs, because `write` returns `:i64` while
`write-to-out` returns nothing:

    (defproc write-to-out [data :ptr length :i64] :void
      (if (is-null clodin-write)
        (write (to-i32 1) data length)
        (pointer-call clodin-write [:ptr :i64] :void data length)))

Decisions:

- A form is in statement position when its value is discarded. That is every
  body form but the last, the body of `when` and `while`, the body of a `let`
  in statement position, and the body of a procedure declared `:void`.
- Statement position propagates only through the forms that forward their
  value: `if` (both arms) and `let` (its last body form). Everything else is
  compiled in value position and its value dropped, so `set`, `store`, a `let`
  initializer, a call argument and a `print` operand always need a value.
- The two arms need not agree in type in statement position. Each arm is run
  for its side effects, and the return value that is useful in one case need
  not be the same as in another. No phi is built and the form produces no
  value.
- `:void` is a declarable return type: `(defproc f [...] :void body...)`. It is
  stored as `:explicit-return-type :void` with `:return-type nil`, so the ABI,
  the stub, the downcall descriptor and AOT all see a void procedure while the
  declaration stays visible. `settle-signatures!` already skips any entry with
  an explicit return type, so a declared void procedure is never re-inferred.
- Declaring `:void` makes the body a statement, so a value the last form
  produces is discarded. That is the same rule as a non-final body form, and it
  is what lets one arm return `:i64` and the other return nothing.
- Without a declaration the return type is still inferred from the last form,
  so a procedure whose last form is a statement `if` must declare `:void`;
  otherwise inference reads the taken arm's type and codegen then fails with
  "has no value to return".
- `:void` is rejected as a parameter type.

Implementation: `compile-statement` is a separate entry point next to
`compile-expression`, so value position is the default and no flag is threaded
through the emitter state. `compile-body`, `compile-if` and `compile-let` take
an explicit `value-needed?` argument, and the six call sites that discard a
value choose it there: `build-procedure!` (false when `:void` was declared),
`compile-let` (inherited), `compile-if` arms (true), statement `if` arms
(false), `compile-when` (false) and `compile-while` (false). Because the flag
never enters the state, no subexpression can inherit it, which is why no
value-consuming site needs to reset anything.

Covered by `test-statement-position` and `test-void-return-type` in
`clodin.compiler`.

## Vectorization

No action needed. Measured on linux-aarch64 with LLVM 21 at `:O3`:

- `sum-i64` (a `while` over `(load :i64 (pointer-add ...))` into a `let`
  accumulator) vectorizes without help: `movi v0.2d` / `movi v1.2d`, two
  128-bit accumulators, `ldp q2, q3, [x8, #-16]`, `add v0.2d, v2.2d, v0.2d`,
  two-way unrolled, with a scalar tail and a final `addp d0, v0.2d`.
- `scale-f64` (two independent pointers) also vectorizes. Because the pointers
  could alias, LLVM emits its own runtime guard, `sub x9, x0, x1; cmp x9, #32;
  b.lo <scalar>`, and vectorizes the non-overlapping path.

So the optimizer already has what it needs: `optimize-module!` runs
`default<O3>` through `LLVMRunPasses` with a host `TargetMachine`, which is what
supplies the vector width and cost model.

The one thing that blocks it is reading a global inside a hot loop. An atomic
acquire load may not be hoisted or reordered, and a loop containing one is not
vectorizable:

- `scale-loop-live`, multiplying by an `f64` global each iteration: 0 vector
  operations, one `ldar` per iteration.
- `scale-loop-hoisted`, copying the global into a `let` first: 2 vector
  operations, one `ldar` before the loop.

Guidance for now: hoist a global into a `let` before a hot loop. That is the
ordinary loop-invariant move and it costs no language feature.

There is a second asymmetry, and it is semantic rather than structural. Integer
reduction vectorizes; float reduction does not:

| procedure | vector ops at `:O3` |
| --- | --- |
| `sum-array-i64` | 3 |
| `sum-buffer-f64` | 0 |

Integer addition is associative, so the vectorizer may reorder it freely. Float
addition is not, and `optimize-module!` deliberately keeps IEEE semantics
("the pipeline only optimizes, never changes IEEE floating point results"), so
there is no fast-math permission to reassociate. Summing `f64` therefore stays
scalar. The portable workaround is manual instruction level parallelism: four
independent `let` accumulators over a four-way unrolled loop, which the SLP
vectorizer can often pack and which at worst still overlaps the add latency.

### Deferred

- [ ] A `noalias` marker for pointer parameters.
  Removes LLVM's runtime alias guard on two-pointer loops. A small, opt-in win;
  it is an assertion the caller must keep, so it belongs behind an explicit
  marker rather than being the default.

- [ ] Explicit SIMD.
  Vector types are a library-level feature, not compiler assistance, and they
  earn their keep in five shapes the auto-vectorizer will not cover:

  1. No loop at all. Zig's `acosBinary32Vec` is a branch-free polynomial over a
     vector, and the scalar `acos` simply calls it with one lane, so a caller
     can evaluate eight `acos` values with one dependency chain.
  2. Semantics barriers. The `f64` reduction above: LLVM may only reassociate
     when the language grants permission, so a library that wants a vector
     reduction must ask for it explicitly.
  3. Loops with data dependent exits, such as `find first match`
     (`core/bytes/bytes.odin`, `lib/std/mem.zig`).
  4. Operations with no scalar counterpart: horizontal reductions
     (`simd.reduce_or`, `simd.reduce_min`, `std.simd.firstTrue`), lane-mask
     `select`, unaligned vector loads, pack/unpack and byte shuffles. Crypto
     rounds are the clearest case (`core/crypto/_chacha20/simd128`,
     `lib/std/crypto/ghash_polyval.zig`).
  5. Width and feature policy chosen by the library, not the cost model.
     `std.simd.suggestVectorLength` and Odin's
     `when ODIN_ARCH == .amd64 && intrinsics.has_target_feature("avx2")` pick
     the width; `core/bytes/bytes.odin` explains skipping AVX512 because of
     downclocking, which no compiler will decide for you.

  Even so it is a scalpel: about 41 files in Zig's `lib/std` and 26 in Odin's
  `core` use it, concentrated in math, crypto, `mem` and strings. clodin has no
  workload that needs it yet; audio DSP would be the first real candidate.

- [ ] A non-atomic global variant.
  Atomic access is the right default, but it forbids the optimizations above.
  A `^:relaxed` (or single-threaded) global would let a hot loop read a global
  directly. Measure before adding: the `let` hoist already covers the common
  case.

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

## Early return

Deferred as a feature, prepared for structurally. "Early termination stays out
of scope" above records why it is ergonomics rather than a performance need;
this section records the plan and the groundwork that already landed.

- [ ] Add a `return` statement.

  `(return value)` and `(return)` emit `ret` (or `ret void`) in the current
  block and mark the rest of the body unreachable. Clojure has no return, so
  the form is clodin-specific and has to be a real statement, not a value.

  The groundwork is already in Phase 1 of "Porting the print runtime to
  clodin". `compile-body` stops emitting after a form sets `:terminated?`,
  `build-procedure!` skips its implicit `ret` for a terminated body, and
  `compile-if` already handles an arm that ends in a terminator: the arm
  contributes no phi incoming, its type is compatible with the other arm's, and
  if both arms terminate the join block is marked `unreachable`.

  Remaining work is therefore small:

  - `compile-return` compiles the value, checks it against the procedure's
    return type, emits `ret`, and returns a state with `:terminated? true`.
  - A procedure whose declared return type is absent must reject `(return
    value)` and accept `(return)`.
  - A return inside `while` must be allowed: the loop's end block simply loses
    that predecessor, and the back edge is not emitted from a terminated body
    block.
  - Diagnostics: `(return ...)` at the top level of the module, outside any
    procedure, is an error.

  Interaction with inference: a body whose last form is `(return x)` leaves
  `:result-value` nil. Inference already uses `expression-type`, which must
  report nil for `return`, and the explicit type check must be skipped for a
  terminated body, otherwise a valid early-returning procedure looks like it
  has no value to return.

## Values and absence

Clodin has no runtime nil for scalar types, and that is deliberate.

- In the compiler, a nil type means "this form produces no value". `when`,
  `while`, `set`, `store` and `print` are like that. It is a compile-time
  property, not a value.
- The only runtime null is a `:ptr` holding address zero. `(defvar p :ptr nil)`
  starts there, `(is-null p)` tests it, and printing a null `:ptr` writes
  nothing.
- `(let [x :i64 (when false 42)] ...)` is a compile error:
  "let binding x is declared as :i64 but its value is no value". It does not
  become 0. Defaulting to 0 would make "absent" indistinguishable from a real
  zero, so a default must be written explicitly: `(if condition 42 0)`.
- For reference: Odin's `nil` is assignable only to nillable types
  (`type_has_nil` in `src/types.cpp`: pointer, rawptr, cstring, slice, proc,
  map, dynamic array, union, enum, bit_set, `any`, `typeid`), and variables are
  zero-initialized unless marked `---`. Zig has no null at all outside optional
  types `?T`. C has null only as a pointer constant. None of the three gives an
  integer a null value.

- [ ] Optional values, if they are ever wanted.
  A real option type, for example `(option :i64)` or Zig-style `?i64`, plus
  `some`/`none` (or `nil` restricted to optionals), `orelse`, and an explicit
  unwrap that traps on absence. This is the only way to make absence
  representable without making every scalar nullable. Do not add a bare `nil`
  literal before this exists: it would have no type to inhabit.

## Next features

- [ ] Add an aggregate array type. `stack-allocate` names the operation and
  yields a plain `:ptr`, so both words are still free: `array` for an
  element-typed block and `buffer` for a region of memory. Note that the
  earlier wording "Odin-style buffer/array with a length stored in the first
  memory slot" is not Odin's design. Odin has fixed arrays `[N]T` (length in
  the type), slices `[]T` (a pointer and a length passed as a fat value) and
  dynamic arrays `[dynamic]T` (a separate `{data, len, cap, allocator}` header
  in `base/runtime/core.odin`); `Buffer` is only a `core:bytes` struct. So
  decide separately whether a block carries a length header at all, and
  whether it earns its keep once the element count is a compile time
  literal.
- [ ] Add an address-of form for procedures, so native code can call clodin
  back. `pointer-call` can only reach JVM upcall stubs and resolved C symbols
  today, because clodin source has no way to take a procedure's address. A
  `(procedure-address foo)` yielding the stub's address would make callbacks
  native-to-native too, and mixed with `pointer-call` it closes the loop. It
  must keep the hot reload promise in mind: the stub address is stable until a
  signature change or a reset, while a body address is not.
- [ ] Add `for` as a macro that lowers to `let` + `while`.
