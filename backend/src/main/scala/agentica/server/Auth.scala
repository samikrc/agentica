package agentica.server

import cask.Request

/** Bearer-token middleware.
 *  Packaged launchers should provide AGENTICA_TOKEN.
 *  Browser/dev mode can use AGENTICA_DEV_TOKEN as a fallback.
 */
object Auth
{

    private val token: String =
        sys.env.get("AGENTICA_TOKEN")
            .orElse(sys.env.get("AGENTICA_DEV_TOKEN"))
            .getOrElse(throw RuntimeException("Neither AGENTICA_TOKEN nor AGENTICA_DEV_TOKEN is set"))

    /** Validates the request's `Authorization: Bearer ...` header.
     *  @param request  Incoming HTTP request.
     *  @return         [[Right]] when authorized, otherwise [[Left]] with an error message.
     */
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
