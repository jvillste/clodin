# Clodin compiler TODOs

## Decisions

- [x] Generate LLVM IR with the LLVM C API (`LLVMBuild*`,
  `LLVMAddFunction`, ...), not by rendering IR text.
- [x] Bind the LLVM C API with jextract-generated Panama bindings.
- [x] Compile and load in-process with LLVM ORC/LLJIT. No `.ll` or `.so`
  files and no clang child process.
- [x] macOS on Apple Silicon is the primary target. Verify jextract
  output, `libLLVM.dylib` discovery, and ORC JIT execution on macOS
  before depending on them.
- [x] Start with whole-module rebuild for hot reload. Rebuild the whole
  LLVM module on any change, but reuse per-procedure bitcode for
  procedures that did not change. Stable thunks and indirect calls are
  deferred until there is a concrete use case for recompiling a single
  function.
- [x] Run `scripts/generate-llvm-bindings.sh` to install jextract and
  generate the LLVM C API bindings. Tested on Linux/AArch64 with
  LLVM 21.1: the generated bindings build a function through the C
  API, JIT it with LLJIT, and call it through Panama.
- [ ] On macOS, verify that jextract-generated bindings can load
  Homebrew's `libLLVM.dylib`, run ORC JIT code, and downcall into it
  with Panama.

The clang and IR-text assumptions in the sections below are superseded
by these decisions where they conflict.

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

## Registry design after text IR removal

The registry is the source of truth for the module. Each procedure entry
stores:

- `:name` — the symbol name.
- `:signature` — parameter types and return type.
- `:ast` — the body forms needed to re-emit the procedure.
- `:bitcode` — in-memory LLVM bitcode generated through the C API.
- `:callees` — a map from callee name to the signature used at the call
  site.
- `:dirty?` — true when the entry changed since the last successful
  build.

The registry does not store rendered text IR, `.ll` paths, `.so` paths,
or clang output.

`defproc` evaluation type-checks the body, generates bitcode through
the C API, records the signature, AST, bitcode, and callees, and marks
the entry dirty.

A whole-module rebuild:

1. Starts from the bitcode that each `defproc` evaluation already
   stored; dirty entries already have fresh bitcode.
2. For every entry, compares `:callees` with the current registry
   signatures.
3. Reports an error naming the caller if a callee is missing.
4. Re-emits the caller from its AST if a callee signature changed.
5. Re-emits transitive callers if a re-emitted procedure's own
   signature changes.
6. Parses all `:bitcode` into one fresh LLVM context, links it into one
   module, verifies it, and hands it to the JIT.
7. Clears dirty flags only for the module version that was built.

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

- [ ] Remove stale procedures when a source file is reloaded or edited.

  The registry is only reset when the compiler namespace itself is reloaded.
  If a `defproc` is removed from a source file and that file is reloaded, the
  old procedure remains registered and is still included in the module until
  the compiler state is explicitly reset.

- [ ] Make module state namespace-aware.

  `procedure-registry`, `module-dirty`, the current JIT generation, and
  the downcall handles are currently global to `clodin.compiler`. Multiple
  namespaces that use `defproc` share one module, so editing one
  namespace can force a full rebuild that includes unrelated
  procedures.

- [ ] Support forward references and mutual recursion.

  A procedure can only call procedures registered before it. There is no
  declaration form yet, so later definitions, direct recursion, and mutual
  recursion cannot be compiled.

- [ ] Decide how compile failures leave the module state.

  `module-dirty` remains true when LLVM/JIT compilation fails, which is
  good for retrying, but the registry already contains the failed
  procedure's bitcode. There is no rollback or partial-module recovery
  strategy.

- [ ] Add pointer safety and alignment rules.

  `load`, `store`, and `pointer-add` perform unchecked raw memory access.
  Loads and stores currently rely on LLVM's default ABI alignment, so an
  unaligned raw pointer can produce undefined behavior.

- [ ] Guard compilation against concurrent procedure registration.

  `compile-module!` is serialized by `compile-lock`, but
  `register-procedure!` is not. A `defproc` registered while a build is
  already running can be missed by that build while `module-dirty` is
  cleared at the end. Use a module version number and only clear the dirty
  state when the version has not changed during the build.

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

- [ ] Add incremental compilation.

  Reuse per-procedure bitcode for unchanged procedures and re-emit only
  procedures whose body or callee signatures changed. Add stable thunks
  or dependent recompilation when single-function reload is needed.

- [ ] Compile asynchronously or on demand in the background.

  Lazy compilation currently blocks the first call while LLVM compiles
  the module. A background compile could be started after registration
  and joined only when a procedure is actually called.

## Next feature

- [ ] Add the Odin-style buffer/array type with a length stored in the first
  memory slot.
- [ ] Add `for` as a macro that lowers to `let` + `while`.
