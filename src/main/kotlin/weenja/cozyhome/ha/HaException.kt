package weenja.cozyhome.ha

import java.io.IOException

/**
 * An answer from Home Assistant that retrying on another URL will not fix:
 * a rejected login, a failed command, an HTTP error status.
 *
 * Network failures (timeouts, refused connections) stay plain [IOException]s,
 * so callers can tell "Home Assistant said no" from "Home Assistant was not reachable".
 *
 * @property httpStatus the HTTP status code when there is one, else 0.
 */
class HaException(message: String, val httpStatus: Int = 0) : IOException(message)
