# TODOS

Deferred work from planning and review sessions.

---

## P3 — Post-Launch

### Refactor PromptBuilder into mode-specific context builders

**What:** Extract `LeaguesContextBuilder` and `IronmanContextBuilder` classes. `PromptBuilder` delegates to the correct one based on `ctx.isLeaguesMode()`. Eliminates the `if (leaguesMode)` blocks scattered through `PromptBuilder.buildSystemPromptImpl()`.

**Why:** The Approach A toggle (ironman/leagues mode) introduces conditional gating inline in `PromptBuilder`. This is intentional tech debt — safe to ship, but deepens a 600+ line static utility that already has too many responsibilities. The refactor makes each mode's prompt assembly independently testable and easier to extend.

**Pros:** Clean separation of concerns. Each context builder tests in isolation. Easier to add a third mode later (e.g., GIM mode). Follows the Approach B architecture already designed.

**Cons:** ~M effort (human) / S with CC. Touches `PromptBuilder`, its tests, and call sites. No behavior change — pure refactor, carries low risk but some test-update burden.

**Context:** Approach B was designed during the 2026-04-12 CEO plan review (Ironman+Leagues toggle) as the clean long-term architecture. Approach A (config flag + `if` blocks) was chosen for the 3-day pre-launch window. The TODO comment `// TODO(post-launch): refactor into LeaguesContextBuilder + IronmanContextBuilder` marks the location in `PromptBuilder`.

**Effort:** M human → S with CC
**Priority:** P3
**Depends on:** Ironman+Leagues toggle (Approach A) must be shipped first.
