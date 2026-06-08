# Nora Architecture

## Product Flow

Nora is a speech-to-speech Android agent:

1. The user uses earbuds/headset or the phone speaker.
2. The user starts Nora from the app UI, notification, or a media button path while the foreground service is active.
3. If the phone is locked, Android presents system unlock UI.
4. The app asks the backend to exchange a WebRTC SDP offer with OpenAI.
5. The backend sends the SDP offer to OpenAI Realtime and returns the SDP answer.
6. The Android WebRTC peer connection carries microphone audio up and model audio down.
7. The WebRTC data channel carries session events, transcripts, response notifications, and tool-call events.

## Why WebRTC

OpenAI's Realtime docs describe Realtime sessions as the right fit for live audio that needs low latency, and the voice-agent session lifecycle supports user audio, model responses, tool calls, and session events. The docs also recommend WebRTC over WebSocket when a browser or mobile client captures or plays audio directly.

## Backend Responsibilities

The backend keeps privileged secrets off the phone.

- Holds `OPENAI_API_KEY`.
- Builds Nora's Realtime session config.
- Sends the phone's SDP offer to `https://api.openai.com/v1/realtime/calls`.
- Returns OpenAI's SDP answer to the app.
- Provides a future-ready `client-secret` endpoint if direct client connection is needed later.
- Adds `OpenAI-Safety-Identifier` using a stable hashed user identifier.

## Android Responsibilities

The Android app owns the user experience and audio device behavior.

- Jetpack Compose UI for Nora's session state.
- Foreground service for visible microphone work.
- `AudioManager` route preference for Bluetooth earbuds/headsets, with phone speaker fallback.
- `MediaSession` hook for earbud/media actions.
- `KeyguardManager` and `BiometricPrompt` helpers for protected actions.
- WebRTC peer connection setup.
- Data-channel event handling for Realtime events.

## Lock-Screen Constraint

Android does not let a normal third-party app wake from a fully inactive state, listen for an arbitrary hotword, bypass the lock screen, and open a full voice session silently. The safe production pattern is:

- Use a foreground service with a persistent notification when Nora is allowed to listen.
- Use notification, media button, headset action, launcher shortcut, or assistant integration as the activation surface.
- Request system lock-screen dismissal when a protected session needs to start.
- Continue only after Android reports that the device is unlocked.

## Open Questions For Production

- Authentication: Firebase Auth is the fastest path; custom JWT is fine if you already have account infrastructure.
- Database: Firebase is simpler for mobile-first settings and session metadata; PostgreSQL is better for relational history, billing, and admin analytics.
- Wake phrase: true offline "Nora" hotword detection needs a small on-device wake-word engine and a clear privacy UX.
- Tools: calendar, messages, search, app actions, and memory should be server-side tools exposed through Realtime tool calls.
