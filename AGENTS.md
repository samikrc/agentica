# Project Notes

- Backend build and tests run from `backend/` with `mvn test`.
- Comment-only or Scaladoc-only changes need no compile or test run. Run the targeted suite for a localized code change and the full suite at checkpoints; do not burn a build on documentation edits.
- Maven Surefire reports zero ScalaTest tests first; the ScalaTest Maven plugin runs the actual suite later in the same command. Verify the final ScalaTest summary rather than the Surefire count.
- Standalone test-source mains such as `agentica.misctests.VLMCompareTest` use `mvn test-compile exec:exec -Dexec.classpathScope=test` with `-Dexec.args` specifying JVM flags, `-classpath %classpath`, the main class, and its arguments. `exec:java` conflicts with the existing plugin's `<classpath/>` argument configuration.
- Tools use one canonical `family_verb` identifier across provider schemas, registry dispatch, help, events, persistence, and evaluation output. Native calls use JSON arguments; the text `run(command=...)` protocol is not a production fallback.
- Judge calls in the evaluation harness must not receive agent tool schemas.
- Work on a Jira ticket happens on a branch named `sr-AG-XX-multi-word-description` (e.g. `sr-AG-38-prompt-hash-cache-key`), created before starting the work.
- Published Scala member methods in `backend/src/main/scala` and `backend/src/test/scala` require adjacent contract-style Scaladoc: behavioral description, every `@param`, `@return` for non-`Unit` results, `@throws` where relevant, and useful `[[Type]]` links. Local functions and overriding methods inherit or remain implementation details. `ScalaStyleCoverageTest` enforces presence by default; run `mvn -DstrictScaladoc=true test` to audit contract tags while existing tag debt is remediated. Enums must use brace syntax (`enum X { ... }`), not colon-significant style (`enum X:`).
- Wrap every `if` condition in parentheses and omit the redundant `then`: `if (cond) body`. Pattern guards (`case … if …`) and `for`-comprehension filters are not conditions and keep no parens. `ScalaStyleCoverageTest` enforces both repo-wide.
- Naming: abbreviations are all-caps unless they start the identifier — `LLMCallMetrics`, `ticketTTLMs`, `JSONCompressor` (not `JsonCompressor`), `ImportantRE` (not `ImportantRe`), but `llmUsage`. Immutable constants belonging to an `object` are upper camel case (`MinRows`, `ContainmentRatio`); locals and instance fields stay lower camel case.
- Pull requests merge with **squash and merge**, then the head branch is deleted.
- Agent skills live in `.agents/skills/` (vendor-neutral Agent Skills location). To review workspace changes, a branch, or a PR, follow `.agents/skills/pr-review/SKILL.md`.
