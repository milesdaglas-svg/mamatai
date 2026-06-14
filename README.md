# MAMA.TAI 📡
### WiFi Hotspot Voucher Manager for Android

---

## What This App Does

- Turns your phone into a WiFi hotspot manager
- When neighbours connect, they see a login page asking for a voucher code
- You generate codes from the admin dashboard
- Internet forwards ONLY to users with valid codes
- You can pause/resume anyone with a toggle
- Vouchers auto-expire by data or time

---

## Project Structure

```
mamatai/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mamatai/
│       │   ├── model/
│       │   │   ├── Voucher.kt           ← Voucher data
│       │   │   └── ConnectedUser.kt     ← User data
│       │   ├── util/
│       │   │   └── DataStore.kt         ← Saves all data
│       │   ├── service/
│       │   │   ├── MamaTaiVpnService.kt ← Controls internet per device ⭐
│       │   │   ├── PortalServerService.kt ← Serves login page ⭐
│       │   │   ├── HotspotService.kt    ← Scans connected devices
│       │   │   └── BootReceiver.kt      ← Auto-start on reboot
│       │   └── ui/
│       │       ├── splash/SplashActivity.kt
│       │       └── admin/
│       │           ├── AdminActivity.kt ← Main dashboard
│       │           └── UserAdapter.kt   ← User list
│       └── res/
│           ├── layout/                  ← All screen designs
│           ├── values/                  ← Colors, strings, themes
│           └── drawable/               ← Backgrounds
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## How to Build the APK

### Step 1 — Go to a cyber cafe (costs ~1,000 UGX for 20 mins)

### Step 2 — Download and install Android Studio (free)
👉 https://developer.android.com/studio

### Step 3 — Open the project
- File → Open → select the `mamatai` folder

### Step 4 — Wait for Gradle to sync (2-3 mins, needs internet)

### Step 5 — Build the APK
- Build → Build Bundle/APK → Build APK
- APK saved to: `app/build/outputs/apk/debug/app-debug.apk`

### Step 6 — Copy APK to your phone and install it!

---

## How to Use MAMA.TAI

1. **Open the app** — it starts automatically
2. **Turn on your phone hotspot** from Android Settings
3. **The app asks for VPN permission** — tap Allow (this is how it controls internet)
4. **Go to Vouchers tab** — create a voucher (set data, time, price)
5. **Give the code** to your neighbour (WhatsApp it, say it verbally)
6. **Neighbour connects** to your hotspot WiFi
7. **A login page pops up** automatically in their browser
8. **They type the code** → internet turns ON for them ✅
9. **You see them** in the Users/Dashboard tab
10. **Toggle them off** anytime from the dashboard

---

## How the Internet Control Works

```
Neighbour's phone
      ↓ (connects to your hotspot)
Your phone's VPN layer (MamaTaiVpnService)
      ↓ checks: does this IP have an active voucher?
   YES → forwards internet to them ✅
   NO  → shows login page ❌
      ↓
Real internet (MTN)
```

No root needed. Uses Android's built-in VPN API.

---

## Troubleshooting

- **Login page doesn't show**: Make sure the app is running and VPN is active
- **App crashes**: Check you gave VPN permission when asked
- **Users can't connect**: Make sure your phone hotspot is ON in Android Settings

---

Built with ❤️ for MAMA.TAI
