# Nora Mobile AI Agent

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/saadimtiaz000/Mobile-Agent)

Nora is a mobile voice-agent starter app for Android. The app uses Kotlin + Jetpack Compose on device, WebRTC for low-latency microphone/earbud audio, and a NestJS backend that keeps the OpenAI API key off the phone.

## Flow

1. User connects earbuds.
2. User opens Nora, or uses an earbud/media action while Nora's foreground service is active.
3. If the phone is locked, Android shows the system unlock UI before the protected voice session starts.
4. Nora starts a WebRTC voice-agent session.
5. User speaks naturally.
6. OpenAI Realtime responds with speech through the selected audio route.

## Projects

- `android-app/` - Android Kotlin + Jetpack Compose app.
- `backend/` - NestJS backend for OpenAI Realtime session signaling.
- `docs/ARCHITECTURE.md` - implementation notes, constraints, and next steps.

## Backend Quick Start

```powershell
cd backend
copy .env.example .env
npm install
npm run start:dev
```

Set `OPENAI_API_KEY` in `backend/.env` before starting the server.

## Android Quick Start

Open `android-app/` in Android Studio, then set the backend URL in `android-app/app/src/main/java/com/nora/agent/config/NoraConfig.kt`.

For an Android emulator talking to a backend on the same PC, keep the default:

```kotlin
const val BACKEND_BASE_URL = "http://10.0.2.2:3000"
```

On a physical phone, replace it with your machine's LAN IP, for example:

```kotlin
const val BACKEND_BASE_URL = "http://192.168.1.20:3000"
```

## Notes

- Android does not allow arbitrary always-on third-party wake words from a fully inactive app without a persistent microphone surface and user-visible notification. This starter uses a foreground microphone service and app-level controls as the safe implementation path.
- System unlock is handled with Android lock-screen dismissal/biometric UI. Nora does not bypass Android security.
- The default Realtime model is `gpt-realtime-2`; change it in `backend/.env`.
