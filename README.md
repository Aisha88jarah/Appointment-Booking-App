# Appointment Booking via Telegram (Android admin app)

For clinics, offices, engineering consultancies or any service business. Clients send an appointment
request to your Telegram bot; the app collects the requests; **you** pick the time slot and **review
the confirmation message before it is sent**. Kotlin + Jetpack Compose + Room, Telegram Bot API
(`getMe`, `getUpdates`, `sendMessage`), no cloud services, no external AI. The bot token is encrypted with an
Android Keystore key and is never part of this repository. The UI follows the device language (English / Arabic, RTL).

## How it works
1. Client message (Arabic or English), one request per message, e.g.
   `Name: Ann Lee` / `Service: Consultation` / `Date: 2026-11-05` (or `tomorrow`, `Monday`, `5 November`) / `Time: 10:30` or `3:30 pm` / `Phone: ...` / `Notes: ...`
   (`الاسم`, `الخدمة`, `التاريخ`, `الوقت`, `الهاتف`, `ملاحظات` also work; free text like "tomorrow at 10:30" is understood too).
   Anything not understood stays empty and is shown exactly as written - nothing is guessed.
2. **Requests** tab: new requests appear as *Pending* (notification when monitoring is on).
3. Open a request -> choose service, day and a free time -> **Review confirmation message** -> edit if you like -> **Send**.
   Only after Telegram accepts the message is the appointment marked *Confirmed*. Nothing is ever sent automatically.
   Decline, reschedule and cancel work the same way (each message is reviewed before sending).
4. **Agenda** tab: upcoming confirmed appointments by day. **Export Excel** saves the list shown (Storage Access Framework) and can be shared.

## Admin settings
- **Schedule tab**: *start times every N min*, *gap between appointments*, default duration, "also offer the first free time right after an appointment",
  opening hours per weekday, and services with their own durations (consultation, check-up, site visit...).
- **Settings tab**: bot token, test connection, start/stop monitoring, business name/address and the four message templates
  (placeholders `{name} {service} {date} {time} {duration} {business} {address}`; a line whose placeholders are empty is dropped).

## Build the APK on GitHub (no Android Studio needed)
1. Create an empty GitHub repository (default branch `main`) and upload **all files of this folder**, including the hidden `.github` folder.
2. Open **Actions -> Build debug APK** (runs on every push to `main`; or **Run workflow**).
3. Download the artifact **app-debug-apk** (`app-debug.apk`) and install it. The workflow needs no signing keys; it generates `gradle-wrapper.jar` if it is not committed.

## Limitations
- Monitoring is a foreground service; Android battery savers/OEM task killers may stop it (no 24/7 guarantee). Telegram keeps undelivered updates up to 24 h.
- A client can only be messaged if they have written to the bot (they have, by sending the request). If they blocked the bot, sending fails and the request stays unchanged.
- Dates are read day-first (31/12/2026). Hours 1-6 without am/pm (e.g. `3:30`) are not guessed. Each client message becomes its own request.
- A bot with an active webhook refuses `getUpdates`; the app offers an explicit "delete webhook" action.

## Verification status
Written without access to the Android SDK or network: **not compiled or run yet**. The first GitHub Actions run is the compile check;
unit tests run afterwards (`./gradlew testDebugUnitTest`, reports uploaded as `unit-test-reports`). `PersistenceTest` (Room restart) is instrumented
(`./gradlew connectedDebugAndroidTest`).
