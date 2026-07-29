# ReelGuard — Midnight Aurora Edition

**Digital wellbeing & reel limiter for Android.** ReelGuard counts every short-form
video scroll (Instagram Reels, YouTube Shorts, TikTok, Facebook, Snapchat), enforces
your daily allowance with a full-screen cooldown lock, and rewards discipline with a
day-streak system — all 100% on-device.

## ✨ What's in this redesign

### 🎨 Midnight Aurora design system
- New obsidian-violet palette (`Ink` / `Surface` layers + `Violet → Aqua` aurora gradients)
- Floating pill navigation bar instead of the stock Material bottom bar
- Hero usage ring showing today's scrolls vs. your *total* daily budget
- Reusable `AuroraComponents` kit (cards, status pills, permission rows, gradient bars)
- Restyled counter bubble, cooldown lock screen, onboarding, and home-screen widget

### 🧠 Backend logic rewrite (`ReelRepository`)
| Area | Before | Now |
|---|---|---|
| Streak | Counted any under-limit day, even with gaps | Calendar-continuity aware — a missed day breaks the run |
| Limit raise | `exceeded` stayed stale | Re-evaluated instantly → user unblocked |
| Cooldown | Restarted full duration after process death | Resumes with the *remaining* time |
| Midnight rollover | Polled every 60s | Re-keys exactly at local midnight |
| Biometric toggle | Ignored by the overlay | Respected — direct override when disabled |
| Cooldown source | Duplicated inline in services | Central `startCooldown()` / `isInCooldown()` |

### 🐛 Fixes
- **CSV export crash fixed** — the `FileProvider` was referenced but never declared
  in the manifest; it's now registered with proper `file_paths.xml`
- Permission checks deduplicated (3 copies → single `util/Permissions` object)
- Emergency override now also ends the active cooldown and is fully audited

## 🏗️ Architecture

```
com.example
├── data/            Room entities, DAO, ReelRepository (backend brain)
├── service/         AccessibilityService (scroll detection),
│                    ReelService (bubble + cooldown overlays),
│                    BiometricOverrideActivity, streak widget
├── ui/
│   ├── components/  AuroraComponents — shared design kit
│   ├── screens/     Dashboard (Insights), Settings (Controls), Onboarding
│   └── theme/       Midnight Aurora palette, typography, theme
└── util/            Permissions — single source for special-access checks
```

## 🚀 Run locally

1. Open the project in [Android Studio](https://developer.android.com/studio)
2. Let it sync; create `.env` from `.env.example` if prompted
3. Remove `signingConfig = signingConfigs.getByName("debugConfig")` from
   `app/build.gradle.kts` if you don't have the debug keystore
4. Run on a device (API 24+); grant the four permissions in onboarding:
   Accessibility · Overlay · Usage access · Battery exemption

## 🔒 Privacy

Scroll detection uses Android's Accessibility framework **exclusively on-device**.
No analytics, no network calls, no data leaves your phone.
