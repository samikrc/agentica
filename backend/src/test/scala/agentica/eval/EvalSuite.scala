package agentica.eval

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Base class for integration/evaluation suites that require an external LLM/VLM.
 *
 *  Subclasses inherit the standard `AnyFunSuite` machinery and a helper to load
 *  the active provider sweep from an eval config file.
 *
 *  Subclasses should also be annotated with `@DoNotDiscover` so they are **not**
 *  picked up by a plain `mvn test` run. Run them explicitly by selecting the suite,
 *  e.g.:
 *    mvn test -Dsuites=agentica.eval.PDFEvalTest
 *
 *  The provider sweep is read from a JSON config file:
 *    - `PDF_EVAL_CONFIG` system property / env var, or
 *    - `/eval-config.json` on the test classpath.
 */
abstract class EvalSuite extends AnyFunSuite
{
    /**
     *  Loads the active provider sweep configuration from the eval config file.
     *
     *  If no config source is found, the calling test is canceled (skipped).
     *
     *  @return  List of [[EvalProviderConfig]] entries (each carries its own sessionTypes).
     */
    protected def loadProviderConfigs(): List[EvalProviderConfig] =
    {
        EvalConfig.resolve() match
        {
            case Some(configs) => configs
            case None =>
                assume(false,
                    "No eval config found. Set PDF_EVAL_CONFIG or create /eval-config.json on the test classpath.")
                Nil
        }
    }
}
