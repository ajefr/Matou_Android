# Changelog

All notable changes to Matou are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.1] - 2026-11-27

### Added
- Swipe mode: vertical swipes either scroll the terminal or browse the shell command history (↑/↓),
  switchable with a new **⇅** button in the extra keys bar and saved per session
- Bilingual interface: English and French, following the phone's language (per-app language on Android 13+)
- **Quit** in the session list menu and in the notification: closes every connection and exits the app
- Screenshots in the README, rendered by a Robolectric test (`MATOU_SCREENSHOTS=$PWD/docs/screenshots ./gradlew :app:testDebugUnitTest`)

## 0.1.0 - 2026-11-27

First prototype (not published as a release; included in 0.1.1).

### Added
- Saved sessions (SSH, Telnet, raw TCP) with edit, duplicate, delete and quick connect
- Passwords and passphrases encrypted with the Android Keystore; show / hide icon on password fields
- SSH through JSch: password, keyboard-interactive (2FA), OpenSSH / PEM / PuTTY `.ppk` keys,
  in-app ed25519 key generation, host key verification, keep-alive, optional legacy algorithms
- Local and remote port forwarding
- Telnet negotiation (echo, terminal type, NAWS) and auto-login on configurable prompts
- Startup commands; reconnect with Enter after a disconnection
- xterm-compatible emulator: 256 colors and truecolor, alternate screen, scroll regions,
  wide characters, DEC line drawing, OSC title / colors / clipboard, bracketed paste, mouse reporting
- Tabs, 8 themes, pinch to zoom, text selection and clipboard, extra keys bar
- Foreground service keeping connections alive in the background
- Signed release builds and GitHub Actions CI producing debug and release APKs



[Unreleased]: https://github.com/ajefr/Matou_Android/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/ajefr/Matou_Android/releases/tag/v0.1.1
