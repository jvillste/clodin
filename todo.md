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

## Next feature

- [ ] Add the Odin-style buffer/array type with a length stored in the first
  memory slot.
- [ ] Add `for` as a macro that lowers to `let` + `while`.
