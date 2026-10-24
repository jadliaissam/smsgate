// Static content of the API docs page. Every value mirrors the backend
// (backend/internal/{messages,devices,httpx,config,ratelimit}); the limits are
// the server defaults from the README Configuration table.

export const limits = {
  ttlDefault: 300, // MSG_TTL_DEFAULT
  ttlMin: 10, // MSG_TTL_MIN
  ttlMax: 3600, // MSG_TTL_MAX
  maxSegments: 3, // MSG_MAX_SEGMENTS
  gsmChars: 459, // 3 × 153 GSM-7 characters
  unicodeChars: 201, // 3 × 67 UCS-2 code units
  listDefault: 50, // LIST_LIMIT_DEFAULT
  listMax: 200, // LIST_LIMIT_MAX
  sendPerMin: 60, // RL_SEND_PER_MIN
  maxBodyKB: 16, // MAX_BODY_BYTES
  idemKeyMax: 255,
  clientRefMax: 128,
  deviceLostMin: 15, // DEVICE_LOST_AFTER
  onlineWindowMin: 2, // DEVICE_ONLINE_WINDOW
} as const

export const API_KEY_PLACEHOLDER = 'YOUR_API_KEY'
export const DEVICE_PLACEHOLDER = 'YOUR_DEVICE_ID'
export const SAMPLE_TO = '+37060000000'
const SAMPLE_BODY = 'Your code is 123456'

export type Lang = 'curl' | 'js' | 'python'
export const langs: { id: Lang; label: string }[] = [
  { id: 'curl', label: 'curl' },
  { id: 'js', label: 'JavaScript' },
  { id: 'python', label: 'Python' },
]

export type Samples = Record<Lang, string>

const auth = `Authorization: Bearer ${API_KEY_PLACEHOLDER}`

export function sendSamples(base: string, deviceId: string): Samples {
  return {
    curl: [
      `curl -X POST ${base}/v1/messages \\`,
      `  -H "${auth}" \\`,
      `  -H "Content-Type: application/json" \\`,
      `  -H "Idempotency-Key: $(uuidgen)" \\`,
      `  -d '{`,
      `    "device_id": "${deviceId}",`,
      `    "to": "${SAMPLE_TO}",`,
      `    "body": "${SAMPLE_BODY}",`,
      `    "ttl_seconds": ${limits.ttlDefault}`,
      `  }'`,
    ].join('\n'),
    js: [
      `const res = await fetch("${base}/v1/messages", {`,
      `  method: "POST",`,
      `  headers: {`,
      `    Authorization: "Bearer ${API_KEY_PLACEHOLDER}",`,
      `    "Content-Type": "application/json",`,
      `    // Reuse the same key when you retry this exact request.`,
      `    "Idempotency-Key": crypto.randomUUID(),`,
      `  },`,
      `  body: JSON.stringify({`,
      `    device_id: "${deviceId}",`,
      `    to: "${SAMPLE_TO}",`,
      `    body: "${SAMPLE_BODY}",`,
      `    ttl_seconds: ${limits.ttlDefault},`,
      `  }),`,
      `});`,
      `if (!res.ok) {`,
      `  const { error } = await res.json();`,
      `  throw new Error(\`\${error.code}: \${error.message}\`);`,
      `}`,
      `const message = await res.json(); // { id, status: "queued", expires_at, ... }`,
    ].join('\n'),
    python: [
      `import uuid`,
      `import requests  # pip install requests`,
      ``,
      `res = requests.post(`,
      `    "${base}/v1/messages",`,
      `    headers={`,
      `        "Authorization": "Bearer ${API_KEY_PLACEHOLDER}",`,
      `        # Reuse the same key when you retry this exact request.`,
      `        "Idempotency-Key": str(uuid.uuid4()),`,
      `    },`,
      `    json={`,
      `        "device_id": "${deviceId}",`,
      `        "to": "${SAMPLE_TO}",`,
      `        "body": "${SAMPLE_BODY}",`,
      `        "ttl_seconds": ${limits.ttlDefault},`,
      `    },`,
      `    timeout=10,`,
      `)`,
      `res.raise_for_status()`,
      `message = res.json()  # {"id": ..., "status": "queued", "expires_at": ...}`,
    ].join('\n'),
  }
}

export function getSamples(base: string): Samples {
  return {
    curl: [`curl ${base}/v1/messages/MESSAGE_ID \\`, `  -H "${auth}"`].join('\n'),
    js: [
      `const res = await fetch("${base}/v1/messages/" + messageId, {`,
      `  headers: { Authorization: "Bearer ${API_KEY_PLACEHOLDER}" },`,
      `});`,
      `const message = await res.json();`,
      `console.log(message.status); // queued, dispatched, sent, delivered, failed or expired`,
    ].join('\n'),
    python: [
      `res = requests.get(`,
      `    f"${base}/v1/messages/{message_id}",`,
      `    headers={"Authorization": "Bearer ${API_KEY_PLACEHOLDER}"},`,
      `    timeout=10,`,
      `)`,
      `message = res.json()`,
      `print(message["status"])`,
    ].join('\n'),
  }
}

export function listSamples(base: string, deviceId: string): Samples {
  const q = `device_id=${deviceId}&status=delivered&limit=${limits.listDefault}`
  return {
    curl: [`curl "${base}/v1/messages?${q}" \\`, `  -H "${auth}"`].join('\n'),
    js: [
      `// Walk every page, newest first.`,
      `let before = null;`,
      `do {`,
      `  const url = new URL("${base}/v1/messages");`,
      `  url.searchParams.set("device_id", "${deviceId}");`,
      `  url.searchParams.set("limit", "${limits.listMax}");`,
      `  if (before) url.searchParams.set("before", before);`,
      `  const res = await fetch(url, {`,
      `    headers: { Authorization: "Bearer ${API_KEY_PLACEHOLDER}" },`,
      `  });`,
      `  const page = await res.json();`,
      `  for (const m of page.data) console.log(m.id, m.status);`,
      `  before = page.next_before;`,
      `} while (before);`,
    ].join('\n'),
    python: [
      `# Walk every page, newest first.`,
      `params = {"device_id": "${deviceId}", "limit": ${limits.listMax}}`,
      `while True:`,
      `    page = requests.get(`,
      `        "${base}/v1/messages",`,
      `        headers={"Authorization": "Bearer ${API_KEY_PLACEHOLDER}"},`,
      `        params=params,`,
      `        timeout=10,`,
      `    ).json()`,
      `    for m in page["data"]:`,
      `        print(m["id"], m["status"])`,
      `    if not page["next_before"]:`,
      `        break`,
      `    params["before"] = page["next_before"]`,
    ].join('\n'),
  }
}

export function deviceSamples(base: string): Samples {
  return {
    curl: [`curl ${base}/v1/devices \\`, `  -H "${auth}"`].join('\n'),
    js: [
      `const res = await fetch("${base}/v1/devices", {`,
      `  headers: { Authorization: "Bearer ${API_KEY_PLACEHOLDER}" },`,
      `});`,
      `const { data: devices } = await res.json();`,
      `const online = devices.filter((d) => d.online);`,
    ].join('\n'),
    python: [
      `devices = requests.get(`,
      `    "${base}/v1/devices",`,
      `    headers={"Authorization": "Bearer ${API_KEY_PLACEHOLDER}"},`,
      `    timeout=10,`,
      `).json()["data"]`,
      `online = [d for d in devices if d["online"]]`,
    ].join('\n'),
  }
}

// ---- response examples ----------------------------------------------------

const exampleMessageId = '0192f1b8-3d4e-7a01-b2c3-d4e5f6a7b8c9'

export function messageExample(deviceId: string): string {
  return JSON.stringify(
    {
      id: exampleMessageId,
      device_id: deviceId,
      sim_slot: null,
      to: SAMPLE_TO,
      status: 'queued',
      client_ref: null,
      error_code: null,
      error_message: null,
      created_at: '2026-10-24T09:30:00Z',
      expires_at: '2026-10-24T09:35:00Z',
      dispatched_at: null,
      sent_at: null,
      delivered_at: null,
      failed_at: null,
    },
    null,
    2,
  )
}

export function deliveredExample(deviceId: string): string {
  return JSON.stringify(
    {
      id: exampleMessageId,
      device_id: deviceId,
      sim_slot: 0,
      to: SAMPLE_TO,
      status: 'delivered',
      client_ref: 'order-4721',
      error_code: null,
      error_message: null,
      created_at: '2026-10-24T09:30:00Z',
      expires_at: '2026-10-24T09:35:00Z',
      dispatched_at: '2026-10-24T09:30:01Z',
      sent_at: '2026-10-24T09:30:03Z',
      delivered_at: '2026-10-24T09:30:07Z',
      failed_at: null,
    },
    null,
    2,
  )
}

export function listExample(deviceId: string): string {
  return [
    '{',
    '  "data": [',
    `    { "id": "${exampleMessageId}", "device_id": "${deviceId}", "status": "delivered", … },`,
    '    …',
    '  ],',
    `  "next_before": "${exampleMessageId}"`,
    '}',
  ].join('\n')
}

export function devicesExample(deviceId: string, name: string): string {
  return JSON.stringify(
    {
      data: [
        {
          id: deviceId,
          name,
          online: true,
          sims: [
            { slot: 0, subscription_id: 1, carrier: 'Telia', number: SAMPLE_TO },
            { slot: 1, subscription_id: 2, carrier: 'Tele2' },
          ],
          battery: 82,
          charging: false,
          last_seen_at: '2026-10-24T09:29:41Z',
        },
      ],
    },
    null,
    2,
  )
}

export const errorExample = JSON.stringify(
  { error: { code: 'invalid_to', message: `to must be an E.164 number such as ${SAMPLE_TO}` } },
  null,
  2,
)

// ---- reference tables -----------------------------------------------------

export interface Field {
  name: string
  type: string
  required?: boolean
  description: string
}

export const sendFields: Field[] = [
  { name: 'device_id', type: 'uuid', required: true, description: 'The phone that sends the SMS. Find it on Devices or with GET /v1/devices.' },
  {
    name: 'to',
    type: 'string',
    required: true,
    description: `Recipient in E.164 format, e.g. ${SAMPLE_TO}. Validated with libphonenumber.`,
  },
  {
    name: 'body',
    type: 'string',
    required: true,
    description: `Message text, at most ${limits.maxSegments} SMS segments: ${limits.gsmChars} GSM-7 characters, or ${limits.unicodeChars} when the text needs Unicode (emoji, Cyrillic, …).`,
  },
  {
    name: 'ttl_seconds',
    type: 'integer',
    description: `How long the message may wait for the phone, ${limits.ttlMin}–${limits.ttlMax}. Default ${limits.ttlDefault} (5 minutes). After that it expires instead of sending late.`,
  },
  {
    name: 'sim_slot',
    type: 'integer',
    description: "SIM slot to send from (the slot values of the device's sims). Omit to use the phone's default SMS SIM.",
  },
  {
    name: 'client_ref',
    type: 'string',
    description: `Your own reference (up to ${limits.clientRefMax} characters), returned on every read of the message.`,
  },
]

export const listParams: Field[] = [
  { name: 'device_id', type: 'uuid', description: 'Only messages of this device.' },
  { name: 'status', type: 'string', description: 'One of queued, dispatched, sent, delivered, failed, expired.' },
  { name: 'since', type: 'RFC 3339', description: 'Only messages created at or after this time, e.g. 2026-10-24T00:00:00Z.' },
  { name: 'before', type: 'uuid', description: 'Cursor: pass the previous page’s next_before to get the next (older) page.' },
  { name: 'limit', type: 'integer', description: `Page size, 1–${limits.listMax}. Default ${limits.listDefault}.` },
]

export interface ErrorCode {
  status: number
  code: string
  meaning: string
}

export const errorCodes: ErrorCode[] = [
  { status: 400, code: 'invalid_json', meaning: 'The body is not valid JSON, has unknown fields or trailing data.' },
  { status: 401, code: 'unauthorized', meaning: 'Missing, malformed or revoked API key.' },
  { status: 404, code: 'device_not_found', meaning: 'No such device on your account (or it was revoked).' },
  { status: 404, code: 'message_not_found', meaning: 'No such message on your account.' },
  { status: 404, code: 'route_not_found', meaning: 'Unknown path, or a method the path does not support.' },
  { status: 409, code: 'idempotency_mismatch', meaning: 'This Idempotency-Key was already used with a different request.' },
  { status: 413, code: 'body_too_large', meaning: `Request body over ${limits.maxBodyKB} KB.` },
  { status: 415, code: 'unsupported_media_type', meaning: 'POST without Content-Type: application/json.' },
  { status: 422, code: 'invalid_to', meaning: 'to is not a valid E.164 number.' },
  { status: 422, code: 'body_empty', meaning: 'body is empty.' },
  { status: 422, code: 'invalid_body', meaning: 'body contains NUL characters.' },
  { status: 422, code: 'body_too_long', meaning: `body needs more than ${limits.maxSegments} SMS segments.` },
  { status: 422, code: 'invalid_ttl', meaning: `ttl_seconds is outside ${limits.ttlMin}–${limits.ttlMax}.` },
  { status: 422, code: 'invalid_sim_slot', meaning: 'sim_slot is not one of the device’s SIMs.' },
  { status: 422, code: 'invalid_client_ref', meaning: `client_ref is longer than ${limits.clientRefMax} characters.` },
  { status: 422, code: 'invalid_idempotency_key', meaning: `Idempotency-Key is longer than ${limits.idemKeyMax} characters or not UTF-8.` },
  { status: 422, code: 'invalid_limit', meaning: `limit is not between 1 and ${limits.listMax}.` },
  { status: 422, code: 'invalid_device_id', meaning: 'device_id query parameter is not a UUID.' },
  { status: 422, code: 'invalid_status', meaning: 'status query parameter is not a known status.' },
  { status: 422, code: 'invalid_since', meaning: 'since is not an RFC 3339 timestamp.' },
  { status: 422, code: 'invalid_before', meaning: 'before is not a message id.' },
  { status: 429, code: 'rate_limited', meaning: 'Too many sends with this key; wait for Retry-After seconds.' },
  { status: 500, code: 'internal', meaning: 'Something broke on our side. Safe to retry with the same Idempotency-Key.' },
]
