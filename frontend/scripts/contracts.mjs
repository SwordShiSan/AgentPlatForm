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

const workspaceEvent = { ...fixture, conversationId: null, type: 'workspace.state_changed', payload: {
  workspaceId: fixture.spaceId, operationId: fixture.eventId, state: 'READY', environmentState: 'NOT_CREATED', errorCode: null,
} };
assert(validateEvent(workspaceEvent), 'Storage events must not fabricate a conversation');
assert(!validateEvent({ ...fixture, conversationId: null }), 'Chat events must retain conversation identity');
for (const patch of [{ environmentState: 'RUNNING' }, { state: 'UNKNOWN' }, { workspaceId: 'invalid' }, { hostPath: '/private' }, { apiKey: 'forbidden' }]) {
  assert(!validateEvent({ ...workspaceEvent, payload: { ...workspaceEvent.payload, ...patch } }), 'Unsafe workspace payload accepted');
}
const workspaceSchema = ajv.compile(api.components.schemas.Workspace);
const workspaceFixture = { id: fixture.eventId, spaceId: fixture.spaceId, name: '项目 😀', state: 'READY', environmentState: 'NOT_CREATED',
  quotaBytes: 2147483648, quotaInodes: 100000, version: 1, operationId: fixture.eventId, errorCode: null, createdAt: fixture.occurredAt, fileAccess: false, shellAccess: false };
assert(workspaceSchema(workspaceFixture));
assert(workspaceSchema({ ...workspaceFixture, environmentState: 'READY' }), 'Independent environment readiness is valid without public file/shell access');
for (const patch of [{ shellAccess: true }, { fileAccess: true }, { environmentState: 'EXECUTABLE' }, { quotaBytes: 0 }, { version: 0 }, { path: '/private' }]) {
  assert(!workspaceSchema({ ...workspaceFixture, ...patch }), 'Invalid storage-only workspace accepted');
}

const environmentEvent = { ...fixture, conversationId: null, type: 'workspace.environment_changed', payload: {
  workspaceId: fixture.spaceId, state: 'PREPARING', generation: 1, version: 1, operationId: fixture.eventId, errorCode: null,
} };
assert(validateEvent(environmentEvent), 'Environment events have their own workspace identity');
for (const patch of [{ containerId: 'private' }, { hostPath: '/private' }, { apiKey: 'secret' }, { generation: 0 }, { state: 'RUNNING' }]) {
  assert(!validateEvent({ ...environmentEvent, payload: { ...environmentEvent.payload, ...patch } }), 'Unsafe environment event accepted');
}
assert(!validateEvent({ ...environmentEvent, taskId: fixture.eventId }), 'Environment operations must not fabricate task identities');
const environmentSchema = ajv.compile(api.components.schemas.WorkspaceEnvironment);
const environmentFixture = { workspaceId: fixture.spaceId, state: 'NOT_CREATED', phase: 'NONE', generation: 0, version: 0, operationId: null, errorCode: null, allowedActions: ['PREPARE'] };
assert(environmentSchema(environmentFixture));
for (const patch of [{ containerId: 'private' }, { path: '/private' }, { version: -1 }, { allowedActions: ['SHELL'] }, { allowedActions: ['PREPARE', 'RETRY'] }]) {
  assert(!environmentSchema({ ...environmentFixture, ...patch }), 'Unsafe environment projection accepted');
}
assert(!environmentSchema({ ...environmentFixture, state: 'BLOCKED', phase: 'BLOCKED', allowedActions: ['RETRY'] }), 'Unknown environments cannot expose retry');
const existingEnvironment = { ...environmentFixture, generation: 1, version: 2, operationId: fixture.eventId };
for (const [state, phase, allowedActions] of [['READY', 'READY', ['SLEEP', 'DELETE']], ['STOPPED', 'STOPPED', ['WAKE', 'DELETE']],
  ['DELETED', 'DELETED', ['PREPARE']], ['DELETING', 'DELETING', []], ['STOPPING', 'STOPPING', []]]) {
  assert(environmentSchema({ ...existingEnvironment, state, phase, allowedActions }), 'Lifecycle projection rejected');
}
for (const patch of [{ state: 'READY', phase: 'READY', allowedActions: ['RETRY'] },
  { state: 'DELETING', phase: 'DELETING', allowedActions: ['DELETE'] }, { generation: 0 }, { operationId: null }]) {
  assert(!environmentSchema({ ...existingEnvironment, state: 'READY', phase: 'READY', allowedActions: [], ...patch }), 'Unsafe lifecycle projection accepted');
}
const environmentOperationSchema = ajv.compile(api.components.schemas.EnvironmentOperation);
const environmentOperation = { operationId: fixture.eventId, workspaceId: fixture.spaceId, intent: 'DELETE', status: 'DRAINING', generation: 1, revision: 1, errorCode: null };
assert(environmentOperationSchema(environmentOperation));
for (const patch of [{ containerId: 'private' }, { hostPath: '/private' }, { status: 'GUESS_STOPPED' }, { generation: 0 }, { intent: 'SHELL' }]) {
  assert(!environmentOperationSchema({ ...environmentOperation, ...patch }), 'Unsafe original operation metadata accepted');
}
for (const state of ['STOPPING', 'DELETING', 'DELETED']) {
  assert(validateEvent({ ...environmentEvent, payload: { ...environmentEvent.payload, state } }), 'Lifecycle event rejected');
}
const emptyEnvironmentCommand = ajv.compile(api.components.schemas.EmptyEnvironmentCommand);
assert(emptyEnvironmentCommand({}));
for (const input of [null, { image: 'mutable' }, { command: 'shell' }, { path: '/host' }]) assert(!emptyEnvironmentCommand(input));

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

// R4.1 rejects secret/permission fields and closed interaction decisions at the public boundary.
const expanded = await SwaggerParser.dereference(structuredClone(api));
const answerInteraction = ajv.compile(expanded.components.schemas.AnswerInteraction);
const answerFixture = { expectedVersion: 1, decision: 'EXECUTE_PLAN', text: '', optionIds: [] };
assert(answerInteraction(answerFixture));
for (const patch of [{ expectedVersion: 0 }, { decision: 'ALLOW_SHELL' }, { permissions: ['FULL'] }, { apiKey: 'forbidden' }, { optionIds: ['same', 'same'] }]) {
  assert(!answerInteraction({ ...answerFixture, ...patch }));
}
for (const type of ['interaction.opened', 'interaction.resolved', 'plan.revised']) {
  assert(!validateEvent({ ...fixture, type, payload: { apiKey: 'forbidden' } }));
}

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

// Recovery is a metadata-only projection, never a state export or execution command.
const recovery = ajv.compile(api.components.schemas.TaskRecovery);
const candidate = {taskId: fixture.payload.taskId, eligible: true, stage: 'AVAILABLE',
  reasonCode: 'OPERATOR_VALIDATION_REQUIRED', revision: 0, taskVersion: 1,
  checkpointId: fixture.eventId, traceId: fixture.eventId};
assert(recovery(candidate));
assert(recovery({...candidate, eligible: false, checkpointId: null, reasonCode: null}));
for (const patch of [{stage: 'AUTO_REPLAY'}, {revision: -1}, {taskVersion: 0},
  {checkpointId: 'bad-id'}, {state: 'private'}, {manifest: {}}, {apiKey: 'forbidden'}]) {
  assert(!recovery({...candidate, ...patch}));
}
