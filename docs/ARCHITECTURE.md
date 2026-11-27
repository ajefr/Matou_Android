# Architecture

```
core/                     Pure Kotlin, no Android dependency, unit-tested on the JVM
  Terminal.kt             VT/xterm parser and emulator (state machine, modes, SGR, OSC, reports)
  Screen.kt               Screen buffer: lines of cells, scrollback, resize
  Colors.kt               Cell color encoding and the 256-color palette
  WcWidth.kt              Character display width (combining, wide, emoji)
  KeyEncoder.kt           Keys and modifiers → xterm escape sequences
  TelnetCodec.kt          Telnet option negotiation (ECHO, SGA, TTYPE, NAWS) and IAC escaping
  AutoResponder.kt        Prompt → answer rules used for Telnet auto-login

app/                      The Android application
  SessionsActivity.kt     Home screen: saved sessions, quick connect
  EditSessionActivity.kt  Session editor, key import and generation
  TerminalActivity.kt     Tabs, menu, extra keys; answers prompts (host key, passwords)
  TerminalView.kt         Draws the screen on a Canvas; keyboard, gestures, selection
  ExtraKeysView.kt        Esc / Ctrl / Alt / arrows / F-keys bar
  TerminalSession.kt      One connection: transport + emulator + reader thread; SessionManager
  Transport.kt            SshTransport (JSch), SocketTransport (Telnet / raw TCP)
  Profile.kt              Session model, JSON store, Keystore encryption of secrets
  ConnectionService.kt    Foreground service keeping connections alive
  Themes.kt               Color themes
  PasswordToggle.kt       Show / hide icon for password fields
```

## Data flow

```
 network ──► Transport.read() ──► TerminalSession reader thread ──► Terminal.feed()
                                                                         │
 TerminalView.onDraw() ◄── invalidate (main thread) ◄────────────────────┘
       │
 keyboard / extra keys ──► KeyEncoder ──► TerminalSession.write() ──► writer thread ──► Transport.write()
```

- The emulator is shared by the reader thread and the UI thread; every access is done under
  `synchronized(terminal)`.
- Network writes go through a single-thread executor, so the UI thread never blocks on the network.
- Device reports (cursor position, device attributes, color queries) produced by the emulator
  are sent back through the same writer.
- Questions during the connection (host key, password, passphrase, 2FA) are blocking calls from the
  network thread to a `Prompter`, implemented by `TerminalActivity` with dialogs.

## Notes

- Android does not load the Java 11+/15+ classes of JSch's multi-release jar, so `JschSetup` forces
  the Bouncy Castle implementations of Ed25519, Ed448 and X25519.
- Generated ed25519 keys are written in the OpenSSH v1 format (the legacy PEM writer does not support EdDSA).
- Sessions survive activity recreation because they live in the `SessionManager` singleton for
  the life of the process; the foreground service keeps the process alive.
