# Find Me

A free, no-internet-needed safety app for gold prospectors in the Mauritanian desert.
Phones relay each other's position, SOS alerts, chat, voice and posts hand-to-hand
over Bluetooth/Wi-Fi (Android Nearby Connections) — no SIM signal, no extra
hardware, no subscription needed.

## What's in the app

- **Position sharing** — every phone broadcasts its GPS position (interval scales
  with battery: 30s/60s/120s) to everyone it's connected to; each device caches
  the last known position of everyone else so a teammate still shows on the list
  even after they drop out of direct range.
- **Mesh relay** — messages aren't just sent directly: every phone also forwards
  what it receives to its *own* other neighbours (up to 8 hops), so a message
  can travel further than any single Bluetooth/Wi-Fi link, hand to hand through
  a chain of people.
- **Direction + distance** — the Peers screen shows a compass arrow and distance
  to each teammate, computed from your own last GPS fix.
- **SOS** — one button broadcasts an urgent alert with your position to
  everyone in the mesh.
- **Dead-man's switch** — if a phone sees no touch input for 20 minutes, it
  automatically broadcasts an inactivity alert so teammates know to check on
  that person.
- **Group chat + private 1:1 chat** — a target picker lets you message
  everyone or just one peer directly.
- **Private encrypted groups** — create a group, get a short join code (share
  it verbally or by typing, since there's no internet for an invite link);
  messages are AES-GCM encrypted with the group's key, so only members can
  read them even though everyone still relays the (unreadable) bytes.
- **Push-to-talk voice** — hold a button to record and send a short voice clip
  directly to whoever's in actual Bluetooth/Wi-Fi range right now (not
  relayed further — multi-hop voice isn't practical over this kind of link).
- **Offline posts with threaded comments** — share an update, teammates reply,
  relayed the same way as everything else.
- **Roster + identity** — set your own display name, shown to everyone else
  on the mesh.
- **Full profiles** — name, phone number, a short description, and a photo
  (picked from the gallery, resized small before sending). Shared on the mesh
  the same way everything else is, and visible to teammates.
- **Offline radar map** — a dedicated Map tab draws you in the center and
  every teammate as a dot placed by real bearing + distance (there's no
  internet for real map tiles out there), with range rings for scale. Tap a
  teammate's dot to see their full profile — name, phone, description — with
  one-tap Call and WhatsApp buttons if they've shared a number.
- **Arabic and French translations** — Mauritania's official languages, so
  the app isn't English-only for the people actually using it in the field.
  The UI mirrors right-to-left automatically in Arabic.
- **Visual polish** — a cooler teal-night color palette, a highlighted active
  tab, rounded ripple-backed cards, a pulsing SOS button while an alert is
  active, a slowly rotating sweep + glowing dots on the radar map, and
  smoother slide/fade transitions between screens.

## Not built yet

The online/offline sync bridge — syncing cached messages/positions to a
shared online feed once any phone reaches real internet, and pulling updates
from other groups/sites. This needs picking and standing up a backend
(Supabase/Firebase free tier, or a small Laravel API) and isn't something
that can be finished without a decision from Ghaly about which to use and
credentials to it.

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
  mesh/   — Nearby Connections wrapper, crypto, GPS tracking, voice record/play,
            groups, profile storage, foreground service, permissions,
            dead-man's-switch watchdog
  model/  — data classes (Envelope, PeerPosition, ChatMessage, SosAlert, Group,
            Post, Profile...)
  repo/   — in-memory state shared between the mesh layer and the UI
  ui/     — MainActivity + screens (Peers / Map / Chat / SOS / Posts / Groups),
            the radar map view, profile dialogs
app/src/main/res/values(-ar|-fr)/ — English/Arabic/French strings
```

## Testing status

This was built and pushed without access to a real Android device, so it's
only confirmed to **compile cleanly** (GitHub Actions build is green). The
app has been confirmed to launch on a real phone. It has **not** been
confirmed to actually mesh between two real phones yet — that needs testing
with two devices: install on both, grant permissions, and check that they
find each other, share positions/profiles, and that chat/SOS/voice/groups/map
all actually arrive on the other phone.
