# Project Notes

- Backend build and tests run from `backend/` with `mvn test`.
- Maven Surefire reports zero ScalaTest tests first; the ScalaTest Maven plugin runs the actual suite later in the same command. Verify the final ScalaTest summary rather than the Surefire count.
- Tools use one canonical `family_verb` identifier across provider schemas, registry dispatch, help, events, persistence, and evaluation output. Native calls use JSON arguments; the text `run(command=...)` protocol is not a production fallback.
- Judge calls in the evaluation harness must not receive agent tool schemas.
- Published Scala member methods in `backend/src/main/scala` and `backend/src/test/scala` require adjacent contract-style Scaladoc: behavioral description, every `@param`, `@return` for non-`Unit` results, `@throws` where relevant, and useful `[[Type]]` links. Local functions and overriding methods inherit or remain implementation details. `ScaladocCoverageTest` enforces presence by default; run `mvn -DstrictScaladoc=true test` to audit contract tags while existing tag debt is remediated.
