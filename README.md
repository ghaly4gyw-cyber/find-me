# Find Me

A free, no-internet-needed safety app for gold prospectors in the Mauritanian desert.
Phones relay each other's position, SOS alerts and text chat hand-to-hand over
Bluetooth/Wi-Fi (Android Nearby Connections) — no SIM signal, no hardware, no
subscription needed.

## What's in this MVP

- **Position sharing** — every phone broadcasts its GPS position every ~30s to
  everyone it's connected to; each device caches the last known position of
  everyone else so a teammate still shows on the list even after they drop out
  of direct range.
- **Mesh relay** — messages aren't just sent directly: every phone also forwards
  what it receives to its *own* other neighbours (up to 8 hops), so a message
  can travel further than any single Bluetooth/Wi-Fi link, hand to hand through
  a chain of people.
- **SOS** — one button broadcasts an urgent alert with your position to
  everyone in the mesh.
- **Group chat** — simple text chat relayed the same way.

## Not in this MVP yet (next phases, already discussed)

Push-to-talk voice, offline posts/feed, encrypted private groups, the
online/offline sync bridge (SMS or internet handoff between separate groups),
battery-aware beacon frequency, and a direction arrow to each teammate.

## How the app gets built

This repo has a GitHub Actions workflow (`.github/workflows/build.yml`) that
automatically builds a debug APK on every push to `main`, and publishes it to
a "latest" release here:

**https://github.com/ghaly4gyw-cyber/find-me/releases/tag/latest**

Download `app-debug.apk` from there on your phone and install it directly —
no computer, no Android Studio needed. Your phone will warn about installing
from outside the Play Store; that's expected for a sideloaded app, tap
"install anyway".

## Project structure

```
app/src/main/java/com/ghaly/findme/
  mesh/   — Nearby Connections wrapper, GPS tracking, foreground service, permissions
  model/  — data classes (Envelope, PeerPosition, ChatMessage, SosAlert)
  repo/   — in-memory state shared between the mesh layer and the UI
  ui/     — MainActivity + 3 screens (Peers / Chat / SOS)
```
