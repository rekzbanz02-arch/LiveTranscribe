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
- Whether a free xAI key includes speech-to-text is not stated in xAI's docs. A 400/401/403/429 error in the status line means the key, its permissions or its credits.

## Troubleshooting: "Expected HTTP 101 response but was '400 Bad Request'"

xAI answers HTTP 400 (not only 401) when the API key is incorrect, so this error means the server refused the connection before any audio was sent, almost always because of the key. Since v1.3 the app stops right away (no endless reconnects) and, after a refusal, asks xAI directly what is wrong with the key. The status line then shows one of:

- *xAI says this API key is invalid or incorrect*: copy the key again from console.x.ai. Tick **Show key** to check what was pasted: it must start with `xai-` and have no extra text.
- *The key is recognised, but ... blocked or disabled*: check the key and your credits in the xAI console.
- *xAI accepts this key, so the key is not the problem*: the server's own message is shown on the line above; send it back so the request can be adjusted.

The app also cleans the pasted key automatically (spaces, line breaks, quotes, a leading `Bearer `).
