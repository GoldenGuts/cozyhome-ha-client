# cozyhome-ha-client

A small Kotlin client for Home Assistant: the connection layer behind
[Cozy Home Widgets](https://weenjahq.com/cozy-home), published so you can read how the app talks to your Home
Assistant.

It covers:

- **Login**: Home Assistant's OAuth flow (code exchange, refresh, revoked logins) or a long-lived
  token.
- **REST API**: states, services (with `return_response`), calendars, history, images.
- **WebSocket API**: a hand-written RFC 6455 client, the auth handshake, commands, events, and
  the read-only scan of areas, floors, devices, entities, labels, favorites and energy prefs.
- **JSON and models**: a strict RFC 8259 parser and the entity and registry types.

No dependencies beyond the Kotlin standard library and the JDK.

## What it promises

- It talks only to the Home Assistant URLs you configure, the primary one first and then the
  fallback (a LAN address, for example). There are no analytics and no other network calls.
- An image URL that points at another host is refused before any request is sent, so your token
  never leaves your Home Assistant. Redirects are not followed.
- TLS uses the platform trust store with hostname verification. There is no switch to turn it off.
- Every scan command works for a non-admin user.
- Tokens are never logged, and `toString()` on token types leaves them out.

## Usage

The API blocks, so call it from a background thread (on Android: `Dispatchers.IO`).

```kotlin
import weenja.cozyhome.ha.auth.InMemoryTokenStore
import weenja.cozyhome.ha.auth.TokenManager
import weenja.cozyhome.ha.model.EntityState
import weenja.cozyhome.ha.model.Registry
import weenja.cozyhome.ha.rest.HaRestClient
import weenja.cozyhome.ha.websocket.HaMessages
import weenja.cozyhome.ha.websocket.HaSession

fun main() {
    val store = InMemoryTokenStore(
        baseUrl = "https://ha.example.com",
        fallbackUrl = "http://192.168.1.10:8123",
        longLivedToken = System.getenv("HA_TOKEN"),
    )
    val tokens = TokenManager(store)

    // REST: every entity's current state.
    val rest = HaRestClient(store, tokens)
    val states = EntityState.parseList(rest.states())
    for (light in states.filter { it.domain == "light" }) {
        println("${light.name}: ${light.state}")
    }

    // WebSocket: run the read-only scan, then ask the registry where things are.
    val scan = HaSession.open(listOf(store.baseUrl, store.fallbackUrl), tokens) { session ->
        println("Connected to Home Assistant ${session.haVersion}")
        HaMessages.scanCommands.mapValues { (_, command) -> session.tryCall(command) }
    }
    val registry = Registry.parse(scan["areas"], scan["floors"], scan["devices"], scan["entities"], scan["labels"])
    for (state in states) {
        println("${state.name} is in ${registry.areaOf(state.id)?.name ?: "no area"}")
    }
}
```

For a real app, implement `TokenStore` on top of your platform's private storage. On Android, keep
that storage out of cloud backup and device transfer with `dataExtractionRules`.

## Layout

| Package | What it holds |
|---|---|
| `auth` | `TokenStore`, `OAuthLogin`, `TokenManager` |
| `rest` | `HaRestClient` over `HttpURLConnection` |
| `websocket` | `WebSocketConnection` (RFC 6455), `HaSession`, `HaMessages` |
| `json` | `Json` tree and parser |
| `model` | `EntityState`, `Registry` and its entries |

## Build and test

JDK 17 or newer:

```
./gradlew build
```

The tests run against local fake servers (a scripted WebSocket server and the JDK's HTTP server).
They need no Home Assistant and no network.

## Relationship to the app

The Android and iOS apps keep their own platform copies of this code (Android types, coroutines,
a disk cache for images). This library follows the same design on the plain JVM. Where a review
found something to tighten, this library has the stricter version, and those changes go into the
apps as well.

The apps' widgets, widget builder, auto-setup rules, icons and artwork are not part of this
repository. See [NOTICE.md](NOTICE.md).

## License

MIT for the code in this repository. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
