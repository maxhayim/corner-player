# Smart doorbell popups

When someone rings, Corner Player shows a small popup in a corner of the TV, over whatever is on: the doorbell's
name, "Someone is at the door", the time, and the door camera's picture when one can be played. After the Duration
(45 seconds by default) it closes by itself, and whatever was in the corner before comes back. The TV app underneath
keeps playing and keeps the remote.

<p align="center">
  <img src="../assets/doorbell-popup.png" alt="The doorbell popup in the top-right corner of a TV's home screen: the door camera's picture fills it, with Front Door, Someone is at the door, 11:16 AM and LIVE over the bottom of the picture" width="720"/>
</p>
<p align="center"><sub>On a MOKA R8G over its home screen. The door camera is a test picture served as an MJPEG feed from a computer on the same network. App banners belong to their owners.</sub></p>

Corner Player never talks to Ring, Nest or any other doorbell's cloud, and never needs their passwords. Something on
your home network that already knows about the doorbell (Home Assistant, or any automation that can send an HTTP
request) tells the TV, with the TV's token:

```
Doorbell pressed → Home Assistant sees the event → POST /doorbell to the TV → popup with the camera
              → Duration over → the corner goes back to what was in it (or closes)
```

- [What the popup shows](#what-the-popup-shows)
- [On the TV](#on-the-tv)
- [Ring with Home Assistant](#ring-with-home-assistant)
- [Other doorbells and cameras](#other-doorbells-and-cameras)
- [The HTTP API](#the-http-api)
- [Troubleshooting](#troubleshooting)
- [Limitations](#limitations)

## What the popup shows

| The camera is... | The popup shows | Tag on the banner |
|---|---|---|
| A video stream (RTSP, HLS) | Live video filling the popup, the banner over its bottom | `LIVE` (once the video plays) |
| MJPEG | Pictures as they arrive, filling the popup, with the banner | `LIVE` |
| A snapshot address (answers with a picture) | The picture, asked for again every second, filling the popup, with the banner | `SNAPSHOT` |
| None set, Show: Notification only, or not found | The banner alone, as a small card | none |
| Set but it fails (offline, expired address, wrong password) | Switches to the banner alone and keeps its timer | `NO VIDEO` |

The picture is cropped to fill the popup, centred, so a camera of another shape (4:3, square) loses a little at its
edges rather than showing bars; the banner is drawn over the bottom of the picture on a fade to dark. A snapshot is
never called live. With no picture at all the popup still says someone is at the door: a ring is never
lost because a camera is.

Repeated rings: the same press sent twice within 5 seconds changes nothing; a later ring shows the popup again with a
fresh timer. Motion (if switched on) never covers a ring's popup and shows at most once per Motion cooldown. A
popup over another short one (a doorbell over a doorbell, or over a short `/play`) still goes back to what was in the
corner before the first.

When the TV's screen is off (standby), nothing is shown and the TV is never woken: the reply says
`"shown": false, "reason": "The TV's screen is off."`.

## On the TV

Open Corner Player. **Network control** must be **On** for anything on the network to reach the TV (it is off by
default); note its **Address** and **Token**. Then, under **Doorbell**:

| Row | What it does |
|---|---|
| **Doorbell popups** | On (default) or Off. Off, doorbell events are answered but show nothing. |
| **Camera** | The camera shown when a request names none: None (the banner alone) or one of your cameras. OK picks the next. |
| **Show** | Camera (default) or Notification only. |
| **Popup corner** | Top right (default), and the other three. |
| **Popup size** | 25, 33 (default) or 40 % of the screen's width. |
| **Duration** | 10, 15, 30, 45 (default), 60 or 120 seconds. ◀ ▶ or OK. |
| **Chime** | Off (default) or On: a short two-note chime as it pops up, at the TV's notification volume. |
| **Triggers** | Ring only (default) or Ring and motion. |
| **Motion cooldown** | With motion on: at most one motion popup every 0, 30, 60 (default), 120, 300 or 600 seconds. |
| **Restore previous** | On (default): what was in the corner comes back when the popup ends. Off: the corner closes. |
| **Test doorbell popup** | Shows the popup now, as a ring would, with these settings. Needs no doorbell, and works with network control off. |

The settings can also be pushed with the cameras, in `config.json`:

```json
{"doorbell": {"camera": "Front door", "corner": "TOP_RIGHT", "size": 33, "seconds": 45,
              "chime": false, "motion": false, "cooldown": 60, "video": true, "restore": true}}
```

## Ring with Home Assistant

> **Check against your Home Assistant.** Entity names below follow the Ring integration's current naming as far as
> this was written, but the Home Assistant documentation could not be checked while writing it. Before you copy them,
> open **Developer tools → States** and search for your doorbell's name to find your own `event.` and `camera.`
> entities.

What Ring gives Home Assistant (through the official [Ring integration](https://www.home-assistant.io/integrations/ring/),
signed in with your own Ring account, in Home Assistant, not in Corner Player):

- **Event entities** for the doorbell: a ring (event type `ding`) and motion (event type `motion`), for example
  `event.front_door_ding` and `event.front_door_motion`. Their state is the time of the last event.
- **Camera entities**, for example `camera.front_door_live_view`. Ring's live view reaches Home Assistant over
  WebRTC, which Corner Player can't play, and Ring offers no RTSP. What Corner Player *can* show is the camera
  entity's **picture**, through Home Assistant's camera proxy, which needs no Ring password and no Home Assistant
  login on the TV: each camera entity carries a short-lived `access_token` that Home Assistant replaces every few
  minutes.

So for a Ring doorbell the popup shows a **snapshot** (tagged `SNAPSHOT`, refreshed every second while it's up, as
fresh as Ring makes it), or the banner alone if there is none. Live Ring video in the corner is not supported.

### 1. secrets.yaml

```yaml
corner_player_doorbell_url: "http://192.168.1.50:8765/doorbell"   # the TV's Address, plus /doorbell
corner_player_auth: "Bearer abcdefghjkmnpqrstuvwxyz23"            # Bearer, a space, the TV's Token
```

Give the TV a fixed address in your router so this keeps working.

### 2. configuration.yaml: the request

```yaml
rest_command:
  corner_doorbell:
    url: !secret corner_player_doorbell_url
    method: post
    headers:
      Authorization: !secret corner_player_auth
    content_type: "application/json"
    timeout: 10
    payload: >-
      {{ {"event": event | default("ring"),
          "device": device | default("Front Door"),
          "camera": camera | default(""),
          "url": url | default(""),
          "seconds": seconds | default(45)} | to_json }}
```

Restart Home Assistant (or reload YAML) once after adding it.

### 3. The automation: ring → popup with the door's picture

```yaml
automation:
  - alias: "Doorbell: popup on the TV"
    mode: queued
    max: 3
    variables:
      ring_event: event.front_door_ding            # your doorbell's ring event entity
      door_camera: camera.front_door_live_view     # your doorbell's camera entity
      ha_url: "http://192.168.1.10:8123"           # Home Assistant, as the TV reaches it
    triggers:
      - trigger: state
        entity_id: event.front_door_ding           # the same as ring_event
        not_from: ["unavailable"]
        not_to: ["unavailable", "unknown"]
    conditions:
      - condition: template
        value_template: "{{ trigger.to_state.attributes.event_type == 'ding' }}"
    actions:
      - action: rest_command.corner_doorbell
        data:
          event: ring
          device: "Front Door"
          url: >-
            {% set token = state_attr(door_camera, 'access_token') %}
            {{ ha_url ~ '/api/camera_proxy/' ~ door_camera ~ '?token=' ~ token if token else '' }}
          seconds: 45
```

`not_from: unavailable` keeps a Home Assistant restart from ringing the TV; the condition keeps other event types out.
If the camera entity has no token (unavailable), `url` is empty and the popup is the banner alone.

The `url` carries the camera's short-lived token. Corner Player keeps it in memory for this popup only and never
saves it; it shows only the address's host on screen and never logs it.

### Optional: motion

Switch **Triggers** to **Ring and motion** on the TV, then:

```yaml
  - alias: "Doorbell motion: popup on the TV"
    mode: single
    variables:
      door_camera: camera.front_door_live_view
      ha_url: "http://192.168.1.10:8123"
    triggers:
      - trigger: state
        entity_id: event.front_door_motion
        not_from: ["unavailable"]
        not_to: ["unavailable", "unknown"]
    conditions:
      - condition: template
        value_template: "{{ trigger.to_state.attributes.event_type == 'motion' }}"
    actions:
      - action: rest_command.corner_doorbell
        data:
          event: motion
          device: "Front Door"
          url: >-
            {% set token = state_attr(door_camera, 'access_token') %}
            {{ ha_url ~ '/api/camera_proxy/' ~ door_camera ~ '?token=' ~ token if token else '' }}
          seconds: 15
```

The TV's Motion cooldown keeps a busy street from popping up again and again; `"cooldown": 120` in the request
overrides it.

### Optional: open the door camera by hand

A dashboard button or script:

```yaml
script:
  tv_show_front_door:
    alias: "TV: show the front door"
    sequence:
      - action: rest_command.corner_doorbell
        data:
          event: test
          device: "Front Door"
          url: >-
            {% set token = state_attr('camera.front_door_live_view', 'access_token') %}
            {{ 'http://192.168.1.10:8123/api/camera_proxy/camera.front_door_live_view?token=' ~ token if token else '' }}
          seconds: 60
```

### Test it without anyone at the door

1. On the TV: **Doorbell → Test doorbell popup**. The popup appears with your settings.
2. In Home Assistant: **Developer tools → Actions**, pick `rest_command.corner_doorbell`, and run it with
   `event: test`. The popup appears; the action's response has the TV's answer.
3. Ring the doorbell. If nothing shows, see [Troubleshooting](#troubleshooting).

Several TVs: one `rest_command` per TV (each has its own address and token), and one action per TV in the automation.

## Other doorbells and cameras

`POST /doorbell` doesn't care where the event comes from. Brand integrations stay in Home Assistant (or your own
automation); the TV only needs an event and, optionally, a camera it can play. Save the camera on the TV (**Add a
camera**, or `config.json`) and send `"camera": "<its name>"`, or send `"url"` each time.

| Doorbell or camera | Event from | Picture in the popup |
|---|---|---|
| **Ring** | Home Assistant Ring integration (event entities) | Snapshot through Home Assistant's camera proxy, as above. No live video. |
| **Google Nest** | Home Assistant Nest integration (doorbell chime and motion events, as device triggers) | Depends on the model: cameras that Home Assistant streams over RTSP have pictures through the camera proxy; WebRTC-only models may have none, and then the banner alone. |
| **Reolink** | Home Assistant Reolink integration (visitor binary sensor) | RTSP straight from the doorbell, live: `rtsp://user:password@192.168.1.30:554/h264Preview_01_sub` |
| **UniFi Protect** | Home Assistant UniFi Protect integration (doorbell event entity) | RTSP once enabled for the camera in Protect, live. Corner Player plays `rtsp://`; if Protect gives `rtsps://`, try its plain RTSP port. |
| **Amcrest / Dahua** | Home Assistant Amcrest integration, or the doorbell's own HTTP events | RTSP, live: `rtsp://user:password@192.168.1.31:554/cam/realmonitor?channel=1&subtype=1`. Their snapshot addresses usually need Digest authentication, which Corner Player doesn't do. |
| **ONVIF cameras** | Home Assistant ONVIF integration | The camera's RTSP address (ONVIF lists it), live. |
| **Any Home Assistant camera entity** | Any Home Assistant trigger | `.../api/camera_proxy/<entity>?token=<access_token>`, a snapshot. |
| **Generic RTSP / HLS / MJPEG cameras** | Anything that can send HTTP | Their address: RTSP and HLS live, MJPEG live, a snapshot address as a snapshot. |
| **Other HTTP doorbells** | The doorbell itself, if it can call a URL with a header | As set on the TV. |

Only RTSP, HLS and Ring snapshots through Home Assistant's proxy were considered when this was written; none of the
rows above were tried with real hardware.

Without Home Assistant, from any trusted machine on the network:

```
curl -X POST http://192.168.1.50:8765/doorbell \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"event": "ring", "device": "Front Door", "camera": "Front door", "seconds": 45}'
```

## The HTTP API

`POST /doorbell` on port 8765, with `Authorization: Bearer <token>`. Parameters in a JSON body, a form body or the
query. All but `event` are optional; anything left out follows the Doorbell settings on the TV.

| Parameter | Values | Default |
|---|---|---|
| `event` | `ring` (also `ding`, `press`), `motion`, `test`, `dismiss` (also `clear`) | `ring` |
| `device` | The name on the banner, up to 40 characters shown | The camera's name, or "Doorbell" |
| `camera` | A saved camera's (or TV input's) name, any case | The Doorbell setting **Camera** |
| `url` | An `rtsp://`, `http://` or `https://` address on your network, used for this popup only and never saved; wins over `camera` | none |
| `seconds` | 5 to 600 | **Duration** |
| `corner` | `top-right`, `top-left`, `bottom-right`, `bottom-left` | **Popup corner** |
| `size` | 15 to 60 (percent of the screen's width) | **Popup size** |
| `sound` | `on` / `off`: the chime | **Chime** |
| `audio` | `on` / `off`: the camera's own sound | off |
| `video` | `off`: the banner alone, even with a camera | on (per **Show**) |
| `cooldown` | 0 to 3600: motion cooldown in seconds, for this request | **Motion cooldown** |

`dismiss` closes a doorbell popup at once and gives the corner back, as its timer would. `POST /stop` closes the
corner window altogether, as before.

Answers (JSON):

| Status | Body | When |
|---|---|---|
| 200 | `{"ok": true, "shown": true, "doorbell": "Front Door", "source": "camera", "seconds": 45}` | Shown. `source` is `camera`, `url` or `notification`. A `warning` says when a named camera wasn't found and the banner shows alone. |
| 200 | `{"ok": true, "shown": false, "reason": "..."}` | A good request that shows nothing: a repeated ring, motion off or cooling down, doorbell popups off, the screen off. |
| 400 | `{"error": "..."}` | A bad parameter, a body that isn't JSON or a form, an address that points at the TV itself. |
| 401 | | No token, or the wrong one. |
| 404 / 405 | | No such path, or the wrong method. |
| 409 | | Display over other apps isn't allowed, or the TV won't open the window from the background. |
| 413 | | Body over 4 KB. |

`GET /status` now also says `"doorbell"` (the doorbell whose popup is up, or `null`) and `"showing"` (`video`,
`mjpeg`, `snapshot`, `input`, `notification`, or `null`).

`/play` is unchanged.

## Troubleshooting

| Symptom | Check |
|---|---|
| Nothing at all | Network control On, and the Address is the TV's current one. Run the action from Developer tools and read its response: 401 is the token; `"shown": false` gives the reason. |
| `Connection refused` / timeout | The TV is off or asleep, network control is off, or the TV's address changed. |
| Banner but no picture (`NO VIDEO`) | Open the camera's `url` from a computer on the same network. Home Assistant's proxy answers 401 to an old token: the automation must build the URL when it runs, as above, not paste a token in. |
| `SNAPSHOT` but the picture is old | Ring sends Home Assistant pictures only so often; Corner Player shows the newest it gets. |
| Two popups for one press | Ring can report one press twice; the TV ignores the second within 5 seconds. With `mode: queued`, events still all arrive. |
| Popup comes up but the app underneath stutters | The TV has one free video decoder at most; use a snapshot or MJPEG address, which needs none. |
| No popup over some app | Some apps or protected (DRM) video may hide overlays; HDMI inputs on some TVs too. See Limitations. |
| `409` | Allow Display over other apps (`install.sh` does), or open Corner Player once after installing. |

## Limitations

- **Not tested with a real doorbell yet.** The popup, the fallbacks, the timer and the corner coming back are covered
  by the JVM tests and the APK builds; a Ring doorbell through Home Assistant, the chime's volume, and the popup over
  YouTube, Netflix, Live TV and HDMI were not tried on a TV yet.
- **No live Ring video.** Ring's live view reaches Home Assistant over WebRTC, which Corner Player doesn't play, and
  Ring has no RTSP. Corner Player doesn't get around this.
- **Over other apps:** the popup is an ordinary overlay window. Protected video (DRM) or a TV's HDMI picture may
  hide or refuse it on some TVs, as for any corner window.
- **The TV is never woken** for a doorbell: with the screen off, nothing shows.
- **Plain HTTP on your network:** the TV's token and a camera proxy token in a `url` can be read by anyone who can read
  your network's traffic. Keep port 8765 off the internet.
- **Digest-only snapshot addresses** (many Amcrest and Dahua) won't send a picture; use their RTSP address.
