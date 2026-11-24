# Clodin compiler TODOs

Open work only, plus the decisions that constrain it. Anything that describes
what the code already does lives in the code and its docstrings, so this file
cannot drift out of sync with it.

## Standing decisions

Constraints for anything new. Changing one is a deliberate step, not a detail.

- Two build modes: `:monolithic` relinks one module per change, `:reloadable`
  gives each procedure a stub so a redefinition reaches callers that are
  already compiled. Switching modes is a full reset plus re-emission.
- Never batch stub and body modules. One module per stub, one per body, one per
  global; a module that defines a stable symbol is added to the JIT exactly
  once and never relinked or unloaded before a reset.
- A signature change re-emits every body, because inferred return types must
  settle across the whole call graph. That is deliberately more work than the
  change needs.
- The stub cell is an atomic acquire/release pointer that starts at an `abort`
  body rather than null, so a call racing an install traps instead of jumping
  to address zero.
- No single procedure is ever removed. A full reset is the only way to drop
  one, and a `defproc` deleted from its source stays registered until then.
- Nothing verifies a callee's real ABI signature. The registry checks the
  written call site, and a call through a `:ptr` cannot be checked at all. A
  mismatch is undefined behaviour by design, not a guarded error.
- Names are qualified: `namespace/name` in the registry, `namespace.name` for
  the native symbol. Only the referring namespace and `clodin.core` are visible
  unqualified. There is no `require`, `refer` or alias form.
- `clodin.core` holds procedures written in clodin itself, not compiler forms,
  and they are visible unqualified everywhere. `print` and `println` stay
  compiler forms because they are variadic and lowered per operand type, so
  they cannot live in a namespace.
- Globals are always accessed atomically. A non-atomic variant is a separate
  item below, not a default.
- Offline builds export plain symbols, so short names must be unique across one
  executable or library, and AOT output has no hot reload.

## Correctness gaps

- [ ] Give raw memory access explicit alignment rules.

  `load`, `store`, `buffer-get` and `buffer-set` perform unchecked access and
  rely on LLVM's default ABI alignment, so an unaligned pointer is undefined
  behaviour with no diagnostic. Either add an alignment argument to those
  forms, or state that pointers must be naturally aligned and check what can be
  checked.

- [ ] Make module state namespace-aware.

  Names are qualified, but the state is not: `module-dirty`, the current JIT
  and the handle table are global to `clodin.compiler`, and every namespace
  compiles into one module. Editing one namespace still rebuilds unrelated
  procedures.

- [ ] Support forward references and mutual recursion with `declare-proc`.

  A body can only call procedures registered before it, because bodies are
  compiled at macroexpansion time. `(declare-proc name [param type ...]
  return-type)` would register the signature and install the stub, whose cell
  starts at the unbound body, so later definitions and mutual recursion
  compile. Declarations are registry entries without a body and are skipped by
  offline builds.

## Reloading

- [ ] Add static stubless procedures.

  A `^:static` `defproc` emits a direct symbol with no stub and no cell, so
  calls are direct and can be inlined, and redefining one is an error. The
  default kind stays `:reloadable` until this exists.

## Globals

- [ ] Native compare-and-set and atomic read-modify-write.

  `(compare-and-set-var name expected new)` and `(atomic-add name value)` as
  `cmpxchg`/`atomicrmw`, so a native loop can do a lock-free update. Until then
  a native `(set name (+ name 1))` is a load, add and store, which can lose an
  increment against a concurrent native writer. The storage is already atomic,
  so only the instruction is missing.

- [ ] `defvaronce`.

  `defvar` always initialises, so re-evaluating one re-runs registration. A
  `defvaronce` would leave an already-bound global untouched, like `defonce`,
  for a global that a reload must never reset.

- [ ] `declare-var`.

  A global must be registered before the `defproc` that reads it.
  `(declare-var name type)` would register the type and storage without an
  initial value, so a global can be forward declared below its first user.

- [ ] Aggregate global types.

  Buffers, structs and arrays as global storage. Needs the aggregate array type
  first, then a constant-expression form for the initializer.

- [ ] Ownership and freeing.

  A `:ptr` global can hold allocated memory but nothing frees it, and a reset
  loses the pointer. Needs a designated owner or a finalizer convention.

- [ ] Watches and validators.

  Requires implementing `IRef`. A native write can never notify a JVM watch, so
  this is only meaningful for a global that native code never writes, and
  validators would apply to JVM writes only.

## Language

- [ ] Add a `return` statement.

  `(return value)` and `(return)` are statements: they emit `ret` and mark the
  rest of the body unreachable. Clojure has no return, so this is
  clodin-specific.

  It is ergonomics, not speed, and the measurements behind that are worth not
  repeating: a flag-driven find loop compiles at `:O3` to one extra `add`, GCC
  vectorises both spellings of the search, and LLVM vectorises neither, so the
  rewrite that helps is the explicit one. Odin offers `return`, labelled
  `break`/`continue`, `defer` and `#must_tail`; Zig adds labelled blocks,
  `defer`/`errdefer` and `noreturn`. Neither has `goto`.

  Interactions to settle: a `return` inside `while` must be allowed, a
  procedure with no declared return type must accept `(return)` and reject
  `(return value)`, and inference must not see a terminated body as one that
  forgot its value. `noreturn` assertions (`unreachable`, non-null promises)
  are the same family and are the second real performance case after tail
  calls.

- [ ] Add `for` as a macro that lowers to `let` + `while`.

- [ ] Let an integer literal adapt to the expected type.

  A Clojure literal is always `:i64`, so an `:i32` parameter needs an explicit
  `(to-i32 1)`, which is why the print runtime has one. A literal that narrows
  to the parameter or binding type would remove that class of cast. This is a
  typing change, so it belongs here rather than in the runtime.

- [ ] Allow variadic pointer calls.

  `pointer-call` takes a fixed signature only, so a `:ptr` to `printf` cannot
  be called. A trailing `:varargs` marker, matching `extern`, plus the C
  default argument promotions would cover it.

- [ ] Add an aggregate array type.

  `stack-allocate` names the operation and yields a plain `:ptr`, so both words
  are free: `array` for an element-typed block and `buffer` for a region of
  memory. Decide separately whether a block carries a length header: Odin's
  fixed arrays keep the length in the type, its slices pass a pointer and a
  length as a fat value, and its dynamic arrays keep a separate
  `{data, len, cap, allocator}` header, so "length in the first memory slot" is
  nobody's design and has to earn its keep.

- [ ] Add an address-of form for procedures.

  Native code can only call JVM upcalls and resolved C symbols, because clodin
  source cannot take a procedure's address. `(procedure-address foo)` would
  make callbacks native-to-native and close the loop with `pointer-call`. It
  has to respect the reloading promise: a stub address is stable until a
  signature change or a reset, a body address is not.

- [ ] Optional values, if they are ever wanted.

  Clodin has no runtime nil for scalar types: a nil *type* means the form
  produces no value, and the only runtime null is a `:ptr` holding zero. Absence
  is therefore unrepresentable for a scalar, and defaulting to zero would make
  absent indistinguishable from a real zero. A real option type (`(option
  :i64)`, `some`/`none`, `orelse`, an unwrapping that traps) is the only way to
  add absence without making every scalar nullable, and a bare `nil` literal
  must not be added before it exists.

## Printing

- [ ] Width, precision and padding.

  Floats use `%g`. A format option, for example `(println (format "%.2f"
  value))`, would allow fixed decimals and column alignment.

- [ ] Print aggregate values.

  Structs, arrays and the aggregate array type, once those types exist.

- [ ] A `format` function returning a string.

  Useful for building a message before printing it, and for tests that want the
  formatted text without touching `*out*`.

## Performance

- [ ] A non-atomic global variant.

  Atomic access is the right default, but it forbids hoisting and
  vectorisation. A `^:relaxed` or single-threaded global would let a hot loop
  read a global directly. Measure before adding: hoisting the global into a
  `let` before the loop already covers the common case, and that is the
  guidance until then.

- [ ] A `noalias` marker for pointer parameters.

  Removes LLVM's runtime alias guard from two-pointer loops. Opt-in, because it
  is an assertion the caller has to keep.

- [ ] Explicit SIMD.

  A library-level feature, not compiler assistance. It earns its keep in five
  shapes the auto-vectoriser will not cover: no loop at all (a branch-free
  polynomial over a vector), semantics barriers (an `f64` reduction, where
  reassociation needs the language's permission), data dependent exits,
  operations with no scalar counterpart (horizontal reductions, lane masks,
  unaligned loads, shuffles), and width or feature policy chosen by the library
  rather than the cost model. Even so it is a scalpel; audio DSP is the first
  real candidate in clodin.

- Auto-vectorisation needs no work: `:O3` already vectorises integer loops,
  with or without an alias guard. Two facts to remember before optimising:
  reading a global inside a hot loop blocks it (an atomic load may not be
  hoisted), and `f64` reduction stays scalar because reordering it would change
  IEEE results, so the portable route is four independent accumulators over a
  four-way unrolled loop.

## Speedups

- [ ] Use a faster optimization level during development.

  Every build uses one level. A lower level during REPL iteration, with the
  default reserved for final builds, would make editing cheaper.

- [ ] Skip the JIT rebuild when the linked module bitcode is unchanged.

  The dirty flag avoids repeated compiles without new registrations, but a
  reload of identical code still rebuilds everything. Hash the linked bitcode
  and reuse the current JIT and handles when the hash matches.

- [ ] Cache compiled JIT modules by module bitcode hash.

  Even when the bitcode differs, a previously compiled module could be reused
  when the code returns to an earlier version.

- [ ] Build per-namespace modules and handles.

  Requires the namespace-aware module state above.

- [ ] Add incremental compilation for monolithic mode.

  Re-emit only the procedures whose body or callee signatures changed. In
  reloadable mode this is superseded by the stubs; re-emitting callers is still
  needed on a signature change.

- [ ] Compile asynchronously or on demand in the background.

  Lazy compilation blocks the first call while LLVM compiles. A background
  compile started at registration and joined on first call would hide it.
