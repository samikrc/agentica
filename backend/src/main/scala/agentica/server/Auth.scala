package agentica.server

import cask.Request

/** Bearer-token middleware.
 *  In production: token is set by Tauri via AGENTICA_TOKEN env var.
 *  In dev mode:   AGENTICA_DEV_TOKEN is accepted as a fallback (see dev-sidecar.sh).
 */
object Auth
{

    private val token: String =
        sys.env.get("AGENTICA_TOKEN")
            .orElse(sys.env.get("AGENTICA_DEV_TOKEN"))
            .getOrElse(throw RuntimeException("Neither AGENTICA_TOKEN nor AGENTICA_DEV_TOKEN is set"))

    def validate(request: Request): Either[String, Unit] =
    {
        request.headers.get("authorization").flatMap(_.headOption) match
        {
            case Some(h) if h.startsWith("Bearer ") && h.drop(7).trim == token => Right(())
            case Some(_) => Left("Invalid bearer token")
            case None    => Left("Missing Authorization header")
        }
    }
}
