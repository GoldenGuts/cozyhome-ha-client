import Foundation
import Security

/// A `TokenStore` that keeps the login the way the iOS app does: credentials in the Keychain, URLs in
/// `UserDefaults`.
///
/// The URLs are not secrets, so they live in the defaults suite you name (the app uses its App Group's suite, which
/// the widget extension reads too). The credentials are one generic-password item holding a small JSON document.
///
/// **Accessibility.** The item is written with `kSecAttrAccessibleAfterFirstUnlock`: it can be read once the device
/// has been unlocked one time since it started, including while it is locked again. The app needs that, because its
/// widgets refresh and run buttons in StandBy and on the Lock Screen, where a `WhenUnlocked` item cannot be read.
/// The class is not a `ThisDeviceOnly` one, so the login is part of an encrypted device backup and comes back when
/// that backup is restored onto a new phone. The item is not synchronizable, so iCloud Keychain does not copy it to
/// the user's other devices.
///
/// **Sharing.** Pass the App Group (or a keychain access group) both targets are entitled to as `accessGroup`, and
/// the app and its extensions see the same login. Nothing is cached in memory: every read goes to the Keychain, so a
/// refresh written by one process is what the next read in another process returns.
///
/// A failed Keychain write leaves the previous value in place; the setters cannot report it, matching `TokenStore`.
public final class KeychainTokenStore: TokenStore {
    /// What the Keychain item holds.
    internal struct StoredCredentials: Codable, Equatable {
        var longLivedToken: String?
        var oAuthTokens: OAuthTokens?
    }

    private static let baseURLKey = "base_url"
    private static let fallbackURLKey = "fallback_url"
    private static let credentialsAccount = "auth"

    private let service: String
    private let accessGroup: String?
    /// A name rather than a `UserDefaults` instance, which is not `Sendable`. Looking the suite up per access is
    /// cheap: Foundation keeps one shared instance per suite.
    private let settingsSuiteName: String?

    /// Serializes read-modify-write of the one Keychain item within this process. Another process (the widget
    /// extension) can still write between a read and a write here; both only ever store a complete, valid login.
    private let writeLock = NSLock()

    /// - Parameters:
    ///   - service: the Keychain service name, usually the app's bundle identifier.
    ///   - accessGroup: a group shared with the app's extensions, or nil for the app's own Keychain.
    ///   - settingsSuiteName: the `UserDefaults` suite for the URLs, such as the App Group; nil for the standard one.
    public init(service: String, accessGroup: String? = nil, settingsSuiteName: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
        self.settingsSuiteName = settingsSuiteName
    }

    private var settings: UserDefaults {
        settingsSuiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    public var baseURL: String {
        get { settings.string(forKey: Self.baseURLKey) ?? "" }
        set { settings.set(normalizeBaseURL(newValue), forKey: Self.baseURLKey) }
    }

    public var fallbackURL: String {
        get { settings.string(forKey: Self.fallbackURLKey) ?? "" }
        set { settings.set(normalizeBaseURL(newValue), forKey: Self.fallbackURLKey) }
    }

    public var longLivedToken: String? {
        get { readCredentials()?.longLivedToken }
        set { updateCredentials { credentials in credentials.longLivedToken = normalizeToken(newValue) } }
    }

    public var oAuthTokens: OAuthTokens? {
        get { readCredentials()?.oAuthTokens }
        set { updateCredentials { credentials in credentials.oAuthTokens = newValue } }
    }

    public func clearCredentials() {
        writeLock.lock()
        defer { writeLock.unlock() }
        SecItemDelete(itemQuery() as CFDictionary)
    }

    /// The attributes that identify the item. `kSecUseDataProtectionKeychain` makes macOS use the same Keychain as
    /// iOS, so the accessibility class means the same thing on both.
    internal func itemQuery() -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.credentialsAccount,
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        return query
    }

    /// The attributes written with the data; see the type's documentation for why this class.
    internal func itemAttributes(data: Data) -> [String: Any] {
        [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
    }

    private func readCredentials() -> StoredCredentials? {
        var query = itemQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return try? JSONDecoder().decode(StoredCredentials.self, from: data)
    }

    private func updateCredentials(_ change: (inout StoredCredentials) -> Void) {
        writeLock.lock()
        defer { writeLock.unlock() }
        var credentials = readCredentials() ?? StoredCredentials()
        change(&credentials)
        if credentials == StoredCredentials() {
            SecItemDelete(itemQuery() as CFDictionary)
            return
        }
        guard let data = try? JSONEncoder().encode(credentials) else { return }
        // Update in place rather than delete and add: a reader in the widget extension never sees a gap with no
        // login, and a crash between the two steps cannot log the user out.
        let attributes = itemAttributes(data: data)
        let status = SecItemUpdate(itemQuery() as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let newItem = itemQuery().merging(attributes) { _, written in written }
            SecItemAdd(newItem as CFDictionary, nil)
        }
    }
}

extension KeychainTokenStore: CustomStringConvertible {
    /// Names the item, never its contents.
    public var description: String {
        "KeychainTokenStore(service: \(service), accessGroup: \(accessGroup ?? "none"))"
    }
}
