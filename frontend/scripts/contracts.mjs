import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { strict as assert } from 'node:assert';
import YAML from 'yaml';
import SwaggerParser from '@apidevtools/swagger-parser';
import openapiTS, { astToString } from 'openapi-typescript';
import { compile } from 'json-schema-to-typescript';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import standaloneCode from 'ajv/dist/standalone/index.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const api = YAML.parse(await readFile(resolve(root, 'contracts/openapi.yaml'), 'utf8'));
await SwaggerParser.validate(structuredClone(api));
const event = JSON.parse(await readFile(resolve(root, 'contracts/events/platform-event.schema.json'), 'utf8'));
const ajv = new Ajv({ strict: true, code: { source: true, esm: true } });
addFormats(ajv);
const validateEvent = ajv.compile(event);
const banner = '/* Generated from contracts. Do not edit manually. */\n';
// Ajv's standalone runtime references are CommonJS even in ESM output. Convert only
// static, generated module references to imports so the browser never needs eval/require.
const imports = new Map();
const validator = standaloneCode(ajv, validateEvent).replace(/require\("([^"]+)"\)/g, (_, module) => {
  if (!imports.has(module)) imports.set(module, `runtime${imports.size}`);
  return imports.get(module);
});
const outputs = {
  'validate-event.js': banner + [...imports].map(([module, name]) => `import * as ${name} from '${module}.js';\n`).join('') + validator,
  'validate-event.d.ts': banner + "import type { PlatformEvent } from './events';\nexport default function validateEvent(value: unknown): value is PlatformEvent;\n",
  'http.ts': banner + astToString(await openapiTS(api)),
  'events.ts': await compile(event, 'PlatformEvent', { bannerComment: banner.trim(), additionalProperties: false }),
  'client.ts': `${banner}import createClient from 'openapi-fetch';\nimport type { ClientOptions } from 'openapi-fetch';\nimport type { paths } from './http';\n\nexport function createPlatformClient(options: ClientOptions) {\n  return createClient<paths>(options);\n}\n`,
};
for (const [name, content] of Object.entries(outputs)) {
  const path = resolve(root, 'frontend/src/shared/api/generated', name);
  if (process.argv.includes('--check')) {
    assert.equal(await readFile(path, 'utf8'), content, `Generated contract drift: ${name}`);
  } else if (!process.argv.includes('--test')) {
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, content, 'utf8');
  }
}

const fixture = {
  schemaVersion: 1, eventId: '0199db83-0000-7000-8000-000000000001', spaceId: '0199db83-0000-7000-8000-000000000002',
  conversationId: '0199db83-0000-7000-8000-000000000003', seq: 1, type: 'task.accepted',
  occurredAt: '2026-10-13T00:00:00Z', entityVersion: 1,
  payload: { taskId: '0199db83-0000-7000-8000-000000000004', status: 'QUEUED' },
};
assert(validateEvent(fixture));
for (const patch of [{ type: 'unknown' }, { schemaVersion: 2 }, { seq: 0 }, { seq: 1.5 }, { spaceId: 'invalid' }, { occurredAt: 'yesterday' }, { entityVersion: 0 }]) {
  assert(!validateEvent({ ...fixture, ...patch }), `Invalid fixture accepted: ${JSON.stringify(patch)}`);
}
const missing = { ...fixture };
delete missing.conversationId;
assert(!validateEvent(missing));
for (const payload of [{}, { ...fixture.payload, apiKey: 'forbidden' }, { ...fixture.payload, status: 'RUNNING' }]) {
  assert(!validateEvent({ ...fixture, payload }), 'Invalid or secret-bearing task payload accepted');
}
const delta = { ...fixture, type: 'message.delta', payload: { messageId: fixture.payload.taskId, blockId: fixture.payload.taskId, offset: 1, text: '😀' } };
assert(validateEvent(delta));
assert(!validateEvent({ ...delta, payload: { ...delta.payload, offset: -1 } }));
assert(!validateEvent({ ...delta, payload: { ...delta.payload, reasoning: 'hidden' } }));

const health = ajv.compile(api.components.schemas.HealthResponse);
const readiness = ajv.compile(api.components.schemas.ReadinessResponse);
const problem = ajv.compile(api.components.schemas.Problem);
const login = ajv.compile(api.components.schemas.LoginRequest);
const csrf = ajv.compile(api.components.schemas.CsrfResponse);
assert(login({ username: 'alice', password: 'fixture-only-password' }));
for (const body of [{ username: 'ab', password: 'fixture-only-password' }, { username: 'alice', password: 'short' }, { username: 'alice', password: 'fixture-only-password', userId: 'spoof' }, { username: 'alice' }]) assert(!login(body));
assert(csrf({ csrfToken: 'a'.repeat(43) }));
assert(!csrf({ csrfToken: 'a'.repeat(42) }));
assert(!csrf({ csrfToken: 'a'.repeat(43), session: 'secret' }));
assert(health({ status: 'UP' }));
assert(!health({ status: 'DOWN' }));
assert(!health({}));
assert(readiness({ status: 'READY' }));
assert(readiness({ status: 'NOT_READY' }));
assert(!readiness({ status: 'UP' }));
assert(!readiness({ status: 'READY', databaseHost: 'internal' }));
if (process.env.API_BASE_URL) {
  const response = await fetch(`${process.env.API_BASE_URL}/api/v1/health`);
  assert.equal(response.status, 200);
  assert(health(await response.json()), 'Real health response differs from OpenAPI');
  const ready = await fetch(`${process.env.API_BASE_URL}/api/v1/readiness`);
  assert([200, 503].includes(ready.status));
  const readyBody = await ready.json();
  assert(readiness(readyBody), 'Real readiness response differs from OpenAPI');
  assert.equal(readyBody.status, ready.status === 200 ? 'READY' : 'NOT_READY');
  const failure = await fetch(`${process.env.API_BASE_URL}/api/v1/missing`);
  assert.equal(failure.status, 404);
  assert(failure.headers.get('content-type')?.includes('application/problem+json'));
  assert(problem(await failure.json()), 'Real Problem response differs from OpenAPI');
}
console.log('Contracts valid; generation and normal/invalid fixtures passed.');

// R1.3 preference/metadata bodies reject privilege and secret-bearing response fields.
const preferencePatch = ajv.compile(api.components.schemas.UpdateChatPreferences);
assert(preferencePatch({defaultCredentialSource:'USER'}));
assert(!preferencePatch({defaultCredentialSource:'AUTO'}));
assert(!preferencePatch({scope:'PLATFORM'}));
assert(!preferencePatch({defaultModelId:'invalid'}));
const metadata = ajv.compile(api.components.schemas.ModelCredentials);
assert(metadata({version:0,userConfigured:false,platformAvailable:true}));
assert(!metadata({version:0,userConfigured:true,platformAvailable:true,apiKey:'forbidden'}));
assert(!metadata({version:-1,userConfigured:false,platformAvailable:false}));

// R2.3 metadata must preserve unknown usage as null and exclude internal evidence.
const reconciliationSchema = structuredClone(api.components.schemas.TaskReconciliation);
reconciliationSchema.properties.calls.items = api.components.schemas.CallReconciliation;
const reconciliation = ajv.compile(reconciliationSchema);
const unresolved = { taskId: fixture.payload.taskId, stopStage: 'STOPPED', stopConfirmed: true,
  unknownCalls: 1, suggestedAction: 'CONTACT_OPERATOR_FOR_RECONCILIATION', excludedFromContext: true, version: 2,
  calls: [{ callId: fixture.eventId, status: 'UNKNOWN', reservationState: 'RECONCILING', inputTokens: null, outputTokens: null, costMicros: null, revision: 0 }] };
assert(reconciliation(unresolved));
for (const patch of [{ stopStage: 'GUESS_STOPPED' }, { unknownCalls: -1 }, { evidencePath: '/internal' }, { apiKey: 'forbidden' }]) assert(!reconciliation({ ...unresolved, ...patch }));
assert(!reconciliation({ ...unresolved, calls: [{ ...unresolved.calls[0], inputTokens: -1 }] }));
assert(!reconciliation({ ...unresolved, calls: [{ ...unresolved.calls[0], evidence: 'private' }] }));
