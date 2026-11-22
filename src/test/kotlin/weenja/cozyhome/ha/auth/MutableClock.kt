package weenja.cozyhome.ha.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves by hand, so expiry checks need no sleeping. */
internal class MutableClock(var nowMillis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)

    override fun millis(): Long = nowMillis
}
