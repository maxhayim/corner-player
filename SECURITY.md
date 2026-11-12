# Security Policy – Corner Player

Corner Player plays your own cameras and streams in a small window over other apps on an Android TV. Camera
addresses often carry user names and passwords, and the window draws over other apps, so security is taken
seriously.

---

## Reporting a Vulnerability

If you discover a security vulnerability:

- Do NOT open a public issue.
- Use GitHub's [Private Vulnerability Reporting](../../security/advisories/new) for this repo.
- Include:
  - A clear description of the issue
  - Steps to reproduce
  - Affected version(s)
  - Any logs or screenshots (sanitized: no camera addresses, passwords, IP addresses or network names)

Please allow reasonable time for investigation and patching before public disclosure.

Responsible disclosure is required.

---

## Camera Credentials

- Push cameras from a computer as a file (`install.sh config.json`) rather than typing passwords on the TV. The app
  takes the file into its own private storage and deletes it.
- Only the ADB shell can push that file: the app's config provider requires `WRITE_SECURE_SETTINGS`.
- On screen, addresses show only the scheme, host and port, never the user name or password in them.
- Keep config files out of git (`backup/` is git-ignored), and never paste a camera address into an issue.
- Prefer a camera account that can only view, not one that can change the camera's settings.

---

## Network Exposure

- Corner Player connects only to the addresses you give it: no telemetry, no update checks, no ads.
- Plain `http://` and `rtsp://` are allowed so home cameras work. On a network you don't trust, RTSP streams and
  their passwords can be read in transit; use the camera's encrypted options where it has them.
- Never forward cameras, ADB (port 5555) or network control (port 8765) to the internet. To watch from away, use a
  VPN to your home network.

---

## Network Control

- Off by default. Only when you switch it on does Corner Player listen, on port 8765, and it keeps listening after a
  restart until you switch it off.
- Every request needs the token shown on the TV, sent as `Authorization: Bearer <token>`; it is compared in constant
  time, and requests are answered one at a time. **New token** replaces it.
- It can open your cameras and inputs by name, or a stream address it is given, show a doorbell popup, and close
  the window; nothing else. It never returns camera addresses.
- It is plain HTTP: anyone who can read your network's traffic can read the token. Keep it on a network you trust.
- Bodies over 4 KB are refused; every parameter is checked, and a bad one is answered with an error rather than
  guessed at. Nothing in a request is ever passed to a shell.

---

## Doorbell Events

- `POST /doorbell` needs the same token, and only works with network control on.
- Corner Player never signs in to Ring, Nest or any doorbell's cloud and never asks for their passwords. Home
  Assistant (or your own automation) holds those; the TV only gets an event and, optionally, an address to show.
- A `url` in a doorbell request (for example Home Assistant's camera proxy with its short-lived `access_token`) is
  kept in memory for that popup only: never saved, never logged, and only its host is shown on screen.
- A doorbell `url` may not point at the TV itself (localhost, `127.x`, `::1`, `0.0.0.0`) or at a link-local
  address (`169.254.x`, `fe80::`), so a request can't make the TV fetch from its own services. It still fetches
  from any other address on your network that an authorised caller names, as `/play` does: that is what the token
  protects.
- Doorbell names are cleaned of control characters and shortened before they are shown.
- A TV in standby is never woken by a doorbell event.

---

## Overlay Window

- The corner window never takes focus or the remote's keys, so it can't capture what you type in other apps.
- It is opaque and keeps pointer touches in its own corner (Android lets touches through only for windows at most
  80 % opaque).
- Display over other apps is the only special permission; `install.sh` grants it because many TVs hide the setting.

---

## Supported Versions

Security updates are applied to:

- The latest tagged release

Older versions may not receive patches.

---

## Secure Development Practices

Corner Player development guidelines:

- No hardcoded credentials, channels or stream addresses
- No debug backdoors, no remote logging, no analytics
- Never log or show a camera address with its user info
- Validate playlist and config input; never pass it to a shell

Pull Requests touching the config provider, address handling or the overlay will receive additional review.
