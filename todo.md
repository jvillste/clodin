# Clodin compiler TODOs

## Caveats to fix

- [ ] Hoist `let` allocas to the function entry block.

  Currently `compile-let` emits `alloca` instructions at the point where
  the `let` appears. A `let` inside a `while` body therefore allocates new
  stack storage on every loop iteration, and none of it is released until
  the procedure returns.

  Fix by collecting allocas in the emitter state and emitting them directly
  after the `entry:` label, before any loop or branch code.

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

  `procedure-registry`, `module-dirty`, the compiled library, and the downcall
  handles are currently global to `clodin.compiler`. Multiple namespaces that
  use `defproc` share one module, so editing one namespace can force a full
  rebuild that includes unrelated procedures.

- [ ] Support forward references and mutual recursion.

  A procedure can only call procedures registered before it. There is no
  declaration form yet, so later definitions, direct recursion, and mutual
  recursion cannot be compiled.

- [ ] Decide how compile failures leave the module state.

  `module-dirty` remains true when clang fails, which is good for retrying, but
  the registry already contains the failed procedure. There is no rollback or
  partial-module recovery strategy.

- [ ] Add pointer safety and alignment rules.

  `load`, `store`, and `pointer-add` perform unchecked raw memory access.
  Loads and stores currently rely on LLVM's default ABI alignment, so an
  unaligned raw pointer can produce undefined behavior.

- [ ] Guard compilation against concurrent procedure registration.

  `compile-module!` is serialized by `compile-lock`, but
  `register-procedure!` is not. A `defproc` registered while clang is
  already compiling can be missed by that build while `module-dirty` is
  cleared at the end. Use a module version number and only clear the dirty
  state when the version has not changed during the build.

## Unimplemented speedups

- [ ] Use a faster optimization level during development.

  `clang -O2` is used for every build. `-O0` or `-O1` would make REPL iteration
  faster, with `-O2` reserved for final builds.

- [ ] Skip clang and library reload when the module IR is unchanged.

  The dirty flag avoids repeated compiles without new registrations, but a
  reload of identical code still rebuilds the whole library. Hash the rendered
  module IR and reuse the existing library/handles when the hash matches.

- [ ] Cache compiled libraries by module IR hash.

  Even when the IR differs, previously compiled module hashes could be reused
  when returning to an earlier version of the code.

- [ ] Build per-namespace modules and handles.

  This would avoid recompiling unrelated namespaces and would make reloads of
  one namespace much cheaper. It requires the namespace-aware module state
  listed above.

- [ ] Add incremental compilation.

  Instead of compiling every procedure in the module on each change, compile
  changed procedures into object files and relink, or use an in-process
  JIT/ORC-style layer with a stable dispatch table.

- [ ] Compile asynchronously or on demand in the background.

  Lazy compilation currently blocks the first call while clang runs. A
  background compile could be started after registration and joined only when
  a procedure is actually called.

## Next feature

- [ ] Add the Odin-style buffer/array type with a length stored in the first
  memory slot.
- [ ] Add `for` as a macro that lowers to `let` + `while`.
