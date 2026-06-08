# Nora Backend Deployment

This project deploys the backend server. The Android app must point to the public backend URL and be rebuilt after the server URL is ready.

## VPS Deploy With Docker

1. Install Docker and the Docker Compose plugin on the server.
2. Clone the repository:

```bash
git clone https://github.com/saadimtiaz000/Mobile-Agent.git
cd Mobile-Agent
```

3. Create the backend environment file:

```bash
cp backend/.env.example backend/.env
nano backend/.env
```

4. Set at least:

```bash
OPENAI_API_KEY=your-real-openai-api-key
OPENAI_REALTIME_MODEL=gpt-realtime-2
NORA_VOICE=marin
PORT=3030
```

5. Start the backend:

```bash
docker compose up -d --build
```

6. Check health:

```bash
curl http://SERVER_IP:3030/api/realtime/status
```

The response should include `"ready":true`.

## Android Server URL

After the backend is reachable, update:

```text
android-app/app/src/main/java/com/nora/agent/config/NoraConfig.kt
```

For a real server, use HTTPS when possible:

```kotlin
const val BACKEND_BASE_URL = "https://your-domain.com"
```

Then rebuild the APK.

## Production Notes

- Keep `backend/.env` only on the server. Do not commit it.
- Use a domain and HTTPS for real mobile testing outside your LAN.
- If using Nginx or Caddy, proxy public HTTPS traffic to `127.0.0.1:3030`.
- Open firewall ports `80` and `443` for HTTPS, or `3030` only for temporary direct testing.

## Render Deploy

The repository includes `render.yaml` for Render Blueprints.

1. Push the latest code to GitHub.
2. Open Render Dashboard.
3. Choose **New** -> **Blueprint**.
4. Select `saadimtiaz000/Mobile-Agent`.
5. Render will read `render.yaml` and create `nora-agent-backend`.
6. When prompted, enter `OPENAI_API_KEY` privately in Render.
7. Deploy.
8. Open:

```text
https://nora-agent-backend.onrender.com/api/realtime/status
```

The response should include `"ready":true`.

Render provides the public `onrender.com` URL. Put that URL into `NoraConfig.kt`, rebuild the APK, and then phones outside your LAN can use Nora.
