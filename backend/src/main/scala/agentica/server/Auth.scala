package agentica.server

import cask.Request

import java.security.SecureRandom

/** Bearer-token middleware.
 *  Packaged launchers should provide AGENTICA_TOKEN.
 *  Browser/dev mode can use AGENTICA_DEV_TOKEN as a fallback.
 *  When neither is set, a random per-launch token is generated so the API
 *  is never reachable with a publicly-known key.
 */
object Auth
{
    private val token: String =
        sys.env.get("AGENTICA_TOKEN")
            .orElse(sys.env.get("AGENTICA_DEV_TOKEN"))
            .getOrElse(randomToken())

    /**
     *  Generates a random 128-bit hex token and prints it to stderr so the
     *  operator can reach the API when no token was configured.
     *
     *  @return  The generated token string.
     */
    private def randomToken(): String =
    {
        val bytes = new Array[Byte](16)
        new SecureRandom().nextBytes(bytes)
        val t = bytes.map("%02x".format(_)).mkString
        System.err.println(s"[Auth] No AGENTICA_TOKEN/AGENTICA_DEV_TOKEN set; generated per-launch token: $t")
        t
    }

    /** The active bearer token, exposed so launchers/UI bootstrap can use it. */
    def currentToken: String = token

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

    /**
     *  Short-lived, single-use tickets for browser endpoints that cannot send
     *  headers (e.g. the `WebSocket` log stream).  Issued via `POST /log/ticket`
     *  under normal bearer auth; consumed on first use or on expiry.
     */
    private val TicketTtlMs = 30_000L
    private val tickets     = new java.util.concurrent.ConcurrentHashMap[String, Long]()

    /**
     *  Issues a ticket valid for [[TicketTtlMs]] milliseconds and one use.
     *
     *  @return  The ticket string, safe to pass as a `?ticket=` query parameter.
     */
    def issueTicket(): String =
    {
        val bytes = new Array[Byte](16)
        new SecureRandom().nextBytes(bytes)
        val t = bytes.map("%02x".format(_)).mkString
        tickets.put(t, System.currentTimeMillis() + TicketTtlMs)
        if tickets.size() > 1024 then
        {
            val now = System.currentTimeMillis()
            tickets.entrySet().removeIf(_.getValue < now)
        }
        t
    }

    /**
     *  Consumes a previously issued ticket: valid only once and within its TTL.
     *
     *  @param ticket  Ticket value supplied as a query parameter.
     *  @return        [[Right]] when the ticket was valid, otherwise [[Left]].
     */
    def consumeTicket(ticket: String): Either[String, Unit] =
        Option(ticket).filter(_.nonEmpty) match
        {
            case None => Left("Missing ticket")
            case Some(t) =>
                val expiry = Option(tickets.remove(t))
                expiry match
                {
                    case Some(until) if System.currentTimeMillis() <= until => Right(())
                    case _ => Left("Invalid or expired ticket")
                }
        }
}
