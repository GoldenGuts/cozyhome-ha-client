# Security

This code handles Home Assistant access tokens, so security reports are welcome.

Please report a vulnerability privately through GitHub's **Report a vulnerability** button on the
Security tab of this repository, rather than in a public issue. I aim to reply within a few days.

What the code is designed to guarantee:

- It talks only to the Home Assistant URLs the user configured. Images that name another host are
  refused before any request is made, and HTTP redirects are not followed for them.
- TLS uses the platform's default trust store and hostname verification. There is no option to
  turn certificate checks off.
- Tokens are never logged.
- No analytics, telemetry or third-party network calls.
