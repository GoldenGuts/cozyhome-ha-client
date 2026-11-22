# cozyhome-ha-client

A small Home Assistant client in Kotlin, with a Swift package for iOS: the connection layer behind
[Cozy Home Widgets](https://weenjahq.com/cozy-home), published so you can read how the app talks to your Home
Assistant.

It covers:

- **Login**: Home Assistant's OAuth flow (code exchange, refresh, revoked logins) or a long-lived
  token.
- **REST API**: states, services (with `return_response`), calendars, history, images.
- **WebSocket API**: a hand-written RFC 6455 client, the auth handshake, commands, events, and
  the read-only scan of areas, floors, devices, entities, labels, favorites and energy prefs.
- **JSON and models**: a strict RFC 8259 parser and the entity and registry types.

No dependencies beyond the Kotlin standard library and the JDK. The Swift package in [`swift/`](swift) uses only
Foundation and Security; see [Swift (iOS)](#swift-ios).

## What it promises

- It talks only to the Home Assistant URLs you configure, the primary one first and then the
  fallback (a LAN address, for example). There are no analytics and no other network calls.
- An image URL that points at another host is refused before any request is sent, so your token
  never leaves your Home Assistant. Redirects are not followed.
- TLS uses the platform trust store with hostname verification. There is no switch to turn it off.
- Every scan command works for a non-admin user.
- Tokens are never logged, and `toString()` on token types (in Swift: `description`, `debugDescription` and
  `dump()`) leaves them out.

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

## Swift (iOS)

`swift/` is a Swift package for iOS 17 and macOS 14: OAuth and long-lived tokens, the REST calls, the WebSocket
login and commands over `URLSessionWebSocketTask`, and a Keychain token store. It is written for Swift 6 strict
concurrency: `TokenManager` and `HASession` are actors, everything else is `Sendable`. The promises above hold for
it too.

```swift
import CozyHomeHAClient

let store = KeychainTokenStore(
    service: "com.example.lights",
    accessGroup: "group.com.example.lights",
    settingsSuiteName: "group.com.example.lights"
)
let tokens = TokenManager(tokenStore: store)

/// Login: load `login.authorizeURL(for:)` in a WKWebView, and when `login.isRedirect(_:for:)`
/// is true for a navigation, cancel it and pass its URL here.
func finishLogin(redirect: URL, baseURL: String) async throws {
    let login = OAuthLogin(tokenStore: store)
    guard let code = login.authorizationCode(from: redirect) else { return } // the user cancelled
    try await login.exchangeCode(code, baseURL: baseURL)
}

func toggleKitchenLight() async throws {
    // REST: every light's state, then a service call.
    let rest = HARestClient(tokenStore: store, tokenManager: tokens)
    for state in try await rest.states().arrayValue {
        guard let entityID = state["entity_id"]?.stringValue, entityID.hasPrefix("light.") else { continue }
        print("\(entityID): \(state["state"]?.stringValue ?? "unknown")")
    }
    try await rest.callService(domain: "light", service: "toggle", data: ["entity_id": "light.kitchen"])

    // WebSocket: log in once, run commands, close.
    let websocket = HAWebSocketClient(tokenStore: store, tokenManager: tokens)
    let areas = try await websocket.withSession { session in
        print("Connected to Home Assistant \(session.haVersion ?? "")")
        return try await session.call(HACommand("config/area_registry/list"))
    }
    print("\(areas?.arrayValue.count ?? 0) areas")
}
```

`KeychainTokenStore` stores the login the way the app does: one generic-password item with
`kSecAttrAccessibleAfterFirstUnlock`, because the widgets refresh and run buttons while the phone is locked
(StandBy, Lock Screen). That class is not `ThisDeviceOnly`, so the login is part of an encrypted backup and comes
back when that backup is restored onto a new phone; it is not synchronized through iCloud Keychain. The doc comment
on the type has the details.

| Folder in `swift/Sources/CozyHomeHAClient` | What it holds |
|---|---|
| `Auth` | `TokenStore`, `InMemoryTokenStore`, `KeychainTokenStore`, `OAuthLogin`, `TokenManager` |
| `REST` | `HARestClient` and its `URLSession` transport (no redirects, capped bodies) |
| `WebSocket` | `HAWebSocketClient`, `HASession`, `HAMessages` |
| top level | `HAError`, `JSONValue` |

In the app, the iOS side is split differently: the networking and Keychain code is Swift like this package, while
JSON, the models and the WebSocket messages come from the app's Kotlin core, built as an XCFramework. The package
here carries small Swift versions of those parts instead, so it builds with nothing but Xcode.

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

The Swift package needs Xcode 16.3 or newer (Swift 6.1):

```
cd swift && swift build && swift test
```

Its tests answer HTTP through a `URLProtocol` stub and play the WebSocket server from a script, so they need no
network either.

## Relationship to the app

The Android and iOS apps keep their own platform copies of this code (Android types, coroutines,
a disk cache for images). This library follows the same design on the plain JVM, and the Swift package follows
the iOS app's `URLSession` and Keychain code. Where a review found something to tighten, this repository has the
stricter version, and those changes go into the apps as well.

The apps' widgets, widget builder, auto-setup rules, icons and artwork are not part of this
repository. See [NOTICE.md](NOTICE.md).

## License

MIT for the code in this repository. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
