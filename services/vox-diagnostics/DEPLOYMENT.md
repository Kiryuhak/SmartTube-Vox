# SmartTube VOX Diagnostics Service — Deployment & Operations Guide

## 1. Overview & Architecture

The `vox-diagnostics` service is an ephemeral, privacy-first ingestion backend for anonymous SmartTube VOX hardware and codec compatibility diagnostics reports.

### Key Principles
- **Zero PII Policy**: No personal identifying information (PII), credentials, cookies, tokens, video titles, search queries, IP addresses, or permanent hardware identifiers are ever accepted or stored.
- **Fail-Closed Validation**: Incoming reports with unknown/unsupported schemas or forbidden keys (e.g. `access_token`, `password`, `cookie`, `email`, `mac`, `serial`) are rejected immediately with HTTP 400.
- **Strict Payload Limits**: Maximum payload size is 256 KB; maximum log events count is 50.
- **Automated Retention**: 30-day retention TTL; expired reports are automatically purged.
- **Source Privacy**: Client IP addresses are hashed using SHA-256 for sliding-window rate limiting; raw IPs are never persisted or logged.

---

## 2. API Endpoints

### `GET /`
Returns service status card in clean HTML:
`SmartTube VOX Diagnostics is running`
No user report data or sensitive details are exposed.

### `GET /health`
Standard service health endpoint.

**Response (200 OK):**
```json
{
  "status": "ok",
  "service": "SmartTube VOX Diagnostics"
}
```

### `GET /healthz`
Returns service health status and supported schema versions.

**Response (200 OK):**
```json
{
  "status": "healthy",
  "service": "vox-diagnostics",
  "supportedSchemas": [
    "vox-diagnostic-report-v1",
    "vox-diagnostic-report-v2"
  ]
}
```

### `GET /admin`
Protected developer administration panel.
Requires `ADMIN_SECRET` via login prompt, query param `?token=...`, or header `Authorization: Bearer <ADMIN_SECRET>`.

### `POST /v1/report`
Validates, sanitizes, and ingests a client diagnostic report.

**Success Response (201 Created):**
```json
{
  "status": "ok",
  "reportId": "VOX-A1B2C3",
  "schema": "vox-diagnostic-report-v2",
  "receivedAt": 1728086400000
}
```

**Error Responses:**
- `400 Bad Request`: Schema unsupported, forbidden sensitive keys detected, missing required hardware fields, or malformed JSON.
- `413 Payload Too Large`: Payload exceeds 256 KB.
- `429 Too Many Requests`: Client exceeded sliding-window rate limit (20 reports / minute per hashed origin).
- `405 Method Not Allowed`: HTTP method other than GET (for healthz) or POST (for report).

---

## 3. Deployment Options

### Option A: Cloudflare Workers (Recommended for Global Edge)
1. Install Wrangler CLI:
   ```bash
   npm install -g wrangler
   ```
2. Deploy to Cloudflare:
   ```bash
   cd services/vox-diagnostics
   wrangler deploy
   ```

### Option B: Node.js / Containerized Deployment
1. Build and run locally or inside a Docker container:
   ```bash
   cd services/vox-diagnostics
   npm test
   node src/diagnostics.mjs
   ```

2. Environment Variables:
   - `PORT`: HTTP listener port (default: 8080).
   - `RETENTION_DAYS`: Retention period in days (default: 30).
   - `RATE_LIMIT_MAX`: Max requests per minute per origin (default: 20).

---

## 4. Operational & Privacy Safeguards

1. **Explicit User Consent**: Reports are generated and transmitted ONLY when explicitly triggered by the user via the in-app "Send Diagnostics" dialog. No automatic or background reporting occurs.
2. **Server-Only Notifications**: In-app push notifications are not used for diagnostics; confirmations are displayed solely in the dialog UI upon submission.
3. **Data Retention & Expiry**: Stored reports expire after 30 days (`isExpiredReport(timestamp)`). No permanent user profiling is possible.
