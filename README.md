# Live Transcribe

Android app that transcribes speech to text in real time, in English, Tagalog, Cebuano or a mix. It streams audio to xAI's speech-to-text API (`grok-voice-transcribe-2.0`) with your own xAI API key.

Audio sources:
- **Microphone**
- **Phone audio**: what other apps play (YouTube, Facebook, browser, etc.), via Android's audio playback capture (Android 10+)
- **Microphone + phone audio**: both mixed together

## Get the APK (no Android Studio needed)

1. Create a new GitHub repository and upload everything in this folder, including the hidden `.github` folder. If your uploader skips it, create the file `.github/workflows/build-apk.yml` by hand with the same contents.
2. Open the repo's **Actions** tab, select **Build APK**, then **Run workflow**. A build also starts on every push to `main`.
3. When it finishes (a few minutes), open the run and download the `LiveTranscribe-apk` artifact. Unzip it and install `LiveTranscribe.apk` on the phone (allow "install unknown apps" when asked).

If the build fails, copy the red error lines from the run log and send them back; the code has not been compiled yet.

## Build locally instead

Open the folder in Android Studio and run, or from a terminal with JDK 17 and the Android SDK installed: `./gradlew assembleDebug`. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Using it

1. Paste your xAI API key and tap **Start transcribing**. The key is stored on the phone only.
2. For phone audio, Android shows a capture prompt. Choose **Entire screen**, then confirm. Nothing on the screen is recorded; only audio is used.
3. Keep **Auto-detect** for mixed-language speech. Use *Filipino / Tagalog* or *English* if you want to force a language.
4. The transcript stays live while you use other apps; the latest line also shows in the notification. Use **Copy** or **Share** to export.

## Limits to know

- xAI documents `fil` (Filipino) and English. Cebuano is not on its published language list, so Cebuano may come out approximated or in the wrong language. Auto-detect gives it the best chance.
- Some apps block audio capture (phone calls, DRM-protected streaming). They will stay silent in *Phone audio* mode.
- With *Microphone + phone audio*, use headphones, otherwise the mic re-records the speaker and lines repeat.
- Whether a free xAI key includes speech-to-text is not stated in xAI's docs. A 401/403/429 error in the status line means the key, its permissions or its credits.
