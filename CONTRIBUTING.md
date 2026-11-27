# Contributing to Matou

Thanks for your interest! Bug reports, device feedback and pull requests are welcome.

## Reporting a bug

Open an issue with:

- your phone model and Android version;
- the protocol (SSH / Telnet / raw) and, if relevant, the server or device (OpenSSH version, router model…);
- what you did, what you expected and what happened;
- for display problems, the program involved (`vim`, `htop`, `tmux`…) and a screenshot.

Never paste passwords, private keys or full `known_hosts` entries in an issue.

## Development setup

See [docs/BUILDING.md](docs/BUILDING.md). In short:

```sh
MATOU_CORE_ONLY=1 ./gradlew :core:test   # fast JVM tests of the terminal core
./gradlew assembleDebug                  # Android build
```

The code layout is described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Pull requests

- Keep each pull request focused on one change, and describe how you tested it (device, Android version, server).
- Terminal emulation changes go in `core/` and come with a unit test in `core/src/test`.
- Follow the existing Kotlin style (`kotlin.code.style=official`); comments may be in English or French.
- CI must be green: it runs the core tests and builds the APK.
- Add a line to the `[Unreleased]` section of [CHANGELOG.md](CHANGELOG.md).

## Security

If you find a vulnerability (for example in credential storage or host key handling), please do not
open a public issue. Contact the maintainer privately through GitHub first.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
