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
