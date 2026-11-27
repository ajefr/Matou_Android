# Matou user guide

Matou is an Android SSH / Telnet client inspired by [KiTTY](https://github.com/cyd01/KiTTY), the
PuTTY fork. If you know KiTTY, you will recognize saved sessions, auto-login and startup commands
(KiTTY's *autocommand*). Matou is an independent project, not affiliated with KiTTY or PuTTY.

> The app's interface is currently in French. This guide quotes the labels as they appear on screen,
> with their meaning in English (an English translation of the interface is planned).

- [Sessions](#sessions)
- [SSH authentication](#ssh-authentication)
- [Private keys](#private-keys)
- [Host keys](#host-keys)
- [Port forwarding](#port-forwarding)
- [Telnet and raw TCP](#telnet-and-raw-tcp)
- [Startup commands](#startup-commands)
- [Using the terminal](#using-the-terminal)
- [Tabs and the menu](#tabs-and-the-menu)
- [Themes and fonts](#themes-and-fonts)
- [Background connections](#background-connections)
- [Security notes](#security-notes)
- [Troubleshooting](#troubleshooting)

## Sessions

The home screen lists your saved sessions, most recently used first.

| Action | How |
|---|---|
| Connect | Tap a session |
| Edit, duplicate, delete | Long-press a session |
| New session | **＋** in the toolbar |
| One-off connection | **⚡** in the toolbar |
| Back to open sessions | **Ouvertes (n)** (open) in the toolbar |

### Session fields

| Field | Notes |
|---|---|
| Name | Optional. Defaults to `user@host`. |
| Protocol | SSH, Telnet or raw TCP. Changing it updates the default port (22 / 23). |
| Host, Port | Host name, IPv4 or IPv6 address. |
| User | For SSH, asked at connection time if empty. For Telnet, sent on the login prompt. |
| Password | Leave empty to be asked each time. The eye icon shows or hides what you type. |
| Private key | SSH only. See [Private keys](#private-keys). |
| Passphrase | Passphrase of the private key. Leave empty to be asked. |
| Startup commands | One command per line, sent after login. |
| Login / password prompt | Telnet only. Text that triggers auto-login (default `login:` and `password:`). |
| Port forwarding | SSH only. See [Port forwarding](#port-forwarding). |
| Theme, font size | Colors and text size for this session. |
| Terminal type | Value of `$TERM` announced to the server (default `xterm-256color`). |
| Keep-alive | Seconds between SSH keep-alive messages. `0` disables them. |
| Backspace sends Ctrl+H | For servers or devices that expect `^H` instead of `DEL` (`^?`). |
| Swipe browses command history | Default [swipe mode](#swipe-mode) for this session. |
| Legacy SSH algorithms | Enables ssh-rsa (SHA-1), DSA, CBC ciphers and SHA-1 key exchanges. |

### Quick connect

Accepted formats:

```
server.example.com            SSH, port 22, user asked
alex@server.example.com       SSH, user alex
alex@192.168.1.10:2222        SSH on port 2222
alex@[2001:db8::1]:22         IPv6
telnet://192.168.1.1          Telnet, port 23
telnet://router:2323          Telnet on port 2323
raw://10.0.0.5:4001           Raw TCP (serial-over-IP servers, MUD…)
```

Quick connections are not saved.

## SSH authentication

Matou tries, in order: **public key** (if a key is set), **keyboard-interactive**, then **password**.

- A saved password is tried once. If it is rejected, you are asked for the password.
- Two-factor prompts (TOTP, Duo…) arrive as keyboard-interactive questions and are shown in a dialog.
- If neither a key nor a password is configured, you are asked for the password.

## Private keys

In the session editor, under **Clé privée (SSH)** (private key):

- **Importer…** (import) picks a key file from your phone (Downloads, Drive, USB…). Supported formats:
  - OpenSSH (`-----BEGIN OPENSSH PRIVATE KEY-----`): ed25519, ECDSA, RSA
  - PEM / PKCS#1 / PKCS#8 (`-----BEGIN RSA PRIVATE KEY-----`, …)
  - **PuTTY `.ppk`** versions 2 and 3, so the keys you already use with PuTTY or KiTTY work as they are
  - Encrypted keys: enter the passphrase in the session, or leave it empty to be asked
- **Générer** (generate) creates a new **ed25519** key pair. The public key is shown so you can copy it.
  Long-press the key line later to copy the public key again.
- **Retirer** (remove) detaches the key from the session.

The key is copied into the app's private storage, so you can delete the original file afterwards.
Other apps cannot read it.

To authorize a generated key on a server, append the public key to `~/.ssh/authorized_keys`:

```sh
echo 'ssh-ed25519 AAAA… matou@android' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

## Host keys

On the first connection to a server, Matou shows its key fingerprint and asks whether to trust it.
Accepted keys are stored in the app's `known_hosts` file. If a server's key changes later, you get
a warning and can refuse the connection: do so unless you know the server was reinstalled.

## Port forwarding

Add one rule per line in **Redirections de ports** (port forwarding). Lines starting with `#` are ignored.

| Rule | Meaning |
|---|---|
| `L 8080:localhost:80` | Phone port 8080 → port 80 seen from the server |
| `L 0.0.0.0:8080:intranet:80` | Same, but reachable from other devices on your Wi-Fi |
| `L 5900:192.168.1.20:5900` | Reach a VNC server on the remote LAN |
| `R 2222:localhost:22` | Server port 2222 → port 22 on the phone side |

The general form is `L [bind_address:]port:host:host_port` or `R port:host:host_port`. Without a bind
address, local rules listen on `127.0.0.1` only. The terminal shows each forward as it is set up,
including failures (port already in use, refused by the server).

Forwards stay open as long as the session is connected, including in the background.

## Telnet and raw TCP

**Telnet** negotiates echo, suppress-go-ahead, terminal type and window size (NAWS), so full-screen
programs get the right size.

**Auto-login**: if the session has a user and/or password, Matou waits for the login prompt, sends
the user, waits for the password prompt and sends the password. Prompts are matched without regard
to case and ignore color codes. Change the prompt texts for devices that use something else, for
example `Username:` on Cisco equipment.

**Raw TCP** sends and receives bytes as they are, without any negotiation. Use it for serial-over-IP
servers (ser2net), MUDs, or debugging text protocols.

> Telnet sends everything, passwords included, in clear text. Prefer SSH whenever the device supports it.

## Startup commands

Commands listed in **Commandes au démarrage** (startup commands) are sent one per line once the connection is ready (after
auto-login for Telnet). Examples:

```
cd /var/log
tmux attach || tmux new
sudo -i
```

## Using the terminal

### Gestures

| Gesture | Effect |
|---|---|
| Tap | Show the keyboard. In mouse-aware apps (htop, mc, tmux), also sends a click. |
| Swipe up / down | Depends on the swipe mode (see below). In full-screen apps (less, man, vim), always sends arrow keys. In mouse-aware apps, sends wheel events. |
| Pinch | Change the font size (saved in the session). |
| Long-press, then drag | Select text. Release to get **Copier / Coller / Tout copier** (copy / paste / copy all). |
| Tap during a selection | Cancel it. |

### Swipe mode

Vertical swipes on the shell prompt can do one of two things:

| Mode | Button label | Swipe down | Swipe up |
|---|---|---|---|
| Scroll (default) | **⇅ Défil.** | Scroll back through the terminal output | Scroll forward |
| History | **⇅ Hist.** (green) | ↑: previous command in the shell history | ↓: next command |

Tap the **⇅** button at the start of the extra keys bar to switch modes. The choice is saved in the
session; you can also set it in the session editor (*Glisser le doigt parcourt l'historique des
commandes*).

### Extra keys bar

| Key | Behavior |
|---|---|
| **Ctrl**, **Alt** | Tap: applies to the next key only (highlighted). Long-press: locked (orange) until tapped again. |
| **⇅ Défil. / ⇅ Hist.** | Switch the [swipe mode](#swipe-mode) |
| **Échap** | Esc |
| **Tab**, arrows, **Début** / **Fin**, **PgPréc** / **PgSuiv**, **Suppr** | Tab, arrows, Home / End, Page Up / Down, Delete |
| `\| - / ~ \ * & > < :` | Symbols that are awkward to reach on touch keyboards |
| **F1** … **F12** | Function keys (scroll the bar to the right) |

Examples: **Ctrl** then `c` sends Ctrl+C; **Ctrl** then `d` sends Ctrl+D; **Alt** then `b` moves one word back in bash.

### Hardware keyboards

Physical keyboards work, including Ctrl, Alt, arrows, function keys and Esc.
<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>V</kbd> pastes the clipboard.

### Disconnection

When the connection drops, the reason is printed in red. Press <kbd>Enter</kbd> to reconnect.

## Tabs and the menu

Each connection opens in a tab at the top of the screen. The dot shows its state:
● connected, ◌ connecting, ○ disconnected.

- Tap a tab to switch to it; long-press it to close it.
- **+** opens the session list to start another connection.
- **⋮** opens the menu: *Coller* (paste), *Copier tout l'historique* (copy whole history),
  *Dupliquer la session* (duplicate), *Reconnecter*, *Réinitialiser le terminal* (reset),
  *Thème*, *Agrandir / Réduire le texte* (larger / smaller text), *Fermer l'onglet* (close tab).

## Themes and fonts

Built-in themes: Matou night (default), PuTTY classic, Solarized dark, Solarized light, Dracula,
Gruvbox, Nord and Green phosphor. Pick one in the session editor or from the **⋮** menu: the
choice is saved in the session.

Programs can change colors at run time (OSC 4/10/11), for example base16 shell scripts.
*Réinitialiser le terminal* (reset) restores the theme.

## Background connections

While at least one session is open, Matou shows a persistent notification and runs a foreground
service so that Android does not close the connections. On Android 13 and later, allow
notifications when asked so the notification can be shown.

Some manufacturers (Xiaomi, Huawei, Samsung…) still kill background apps aggressively. If
connections drop when the screen is off, exempt Matou from battery optimization in the system settings.

## Security notes

- Passwords and passphrases are encrypted with a key held in the Android Keystore. The key never
  leaves the device and cannot be extracted, even with a backup.
- Private keys, `known_hosts` and the session list are stored in app-private storage. Android
  backups are disabled for the app.
- Host keys are always verified. Only accept an unknown key if you can check its fingerprint.
- The *legacy algorithms* option weakens security: enable it only for devices that need it.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Algorithm negotiation fail` | The server only supports old algorithms. Enable **Autoriser les anciens algorithmes SSH** (legacy algorithms). |
| `Auth fail` | Wrong password or key, or the key is not in `authorized_keys`. Check the server logs (`/var/log/auth.log`). |
| `UnknownHostKey` / `reject HostKey` | You refused the host key, or it changed. Accept it if you trust the server. |
| Backspace prints `^?` or `^H` | Toggle **Retour arrière envoie Ctrl+H** (backspace sends Ctrl+H) in the session. |
| Colors look wrong or the layout is broken | Set **Type de terminal** (terminal type) to `xterm-256color`, or `xterm` / `vt100` on very old systems. |
| Lines are cut or wrap badly after a rotation | Most programs redraw on resize. Press Ctrl+L or run `reset`. |
| Telnet auto-login does nothing | Adjust the login and password prompt texts to match what the device prints. |
| The connection drops in the background | See [Background connections](#background-connections), and set a keep-alive (for example 30 s). |
