const { before, after, beforeEach, test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
let env;
const profile = uid => ({ uid, email: `${uid}@example.test`, displayName: 'Test user', role: 'patient', createdAtUtc: 1 });
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-pulselink-access',
    database: { host: '127.0.0.1', port: 9000, rules: fs.readFileSync(path.resolve(__dirname, '../../database.rules.json'), 'utf8') }
  });
});
after(async () => { if (env) await env.cleanup(); });
beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async context => {
    await context.database().ref().set({
      users: { alice: profile('alice'), bob: profile('bob') },
      notifications: { alice: { notice: { title: 'Report ready' } }, bob: { notice: { title: 'Private' } } },
      accessDirectory: { accounts: { alice: { role: 'patient' } } },
      alerts: { private: { patientId: 'P1' } }
    });
  });
});
test('anonymous profile reads fail', async () => assertFails(env.unauthenticatedContext().database().ref('users/alice').get()));
test('clinical recording paths deny direct client access including claimed administrators', async () => {
  const db = env.authenticatedContext('alice', { role: 'administrator' }).database();
  for (const key of ['recordingsV2', 'recordingChunksV2', 'patientRecordingsV2', 'recordingNotesV2', 'recordingAlertsV2', 'deviceStateV2']) {
    await assertFails(db.ref(`${key}/private`).set({ samples: [2048] }));
    await assertFails(db.ref(`${key}/private`).get());
  }
});
test('a user reads only their own profile', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertSucceeds(db.ref('users/alice').get());
  await assertFails(db.ref('users/bob').get());
});
test('a user edits their own demographic data', async () => assertSucceeds(env.authenticatedContext('alice').database().ref('users/alice/displayName').set('Updated name')));
test('a user cannot edit another profile', async () => assertFails(env.authenticatedContext('alice').database().ref('users/bob/displayName').set('Changed')));
test('a user cannot promote their profile role', async () => assertFails(env.authenticatedContext('alice').database().ref('users/alice/role').set('doctor')));
test('new profiles can be created only as patients', async () => {
  const db = env.authenticatedContext('newUser').database();
  await assertFails(db.ref('users/newUser').set({ ...profile('newUser'), role: 'administrator' }));
  await assertSucceeds(db.ref('users/newUser').set(profile('newUser')));
});
test('a staff request does not grant a role', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertSucceeds(db.ref('users/alice/requestedRole').set('nurse'));
  await assertFails(db.ref('accessDirectory/accounts/alice/role').set('nurse'));
});
test('directory access requires the server even with a client admin claim', async () => {
  const db = env.authenticatedContext('alice', { role: 'administrator' }).database();
  await assertFails(db.ref('accessDirectory').get());
  await assertFails(db.ref('accessDirectory/accounts/alice').set({ role: 'administrator' }));
});
test('notification inbox is recipient-only and server-written', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertSucceeds(db.ref('notifications/alice').get());
  await assertFails(db.ref('notifications/bob').get());
  await assertFails(db.ref('notifications/alice/forged').set({ title: 'Forged' }));
});
test('clinical paths cannot bypass the API', async () => {
  const db = env.authenticatedContext('alice').database();
  for (const node of ['alerts', 'telemetry', 'sessions', 'clinicalCarePlans']) {
    await assertFails(db.ref(node).get());
    await assertFails(db.ref(`${node}/forged`).set({ patientId: 'P1' }));
  }
});
test('oversized and unexpected profile fields are rejected', async () => {
  const db = env.authenticatedContext('alice').database();
  await assertFails(db.ref('users/alice/displayName').set('x'.repeat(161)));
  await assertFails(db.ref('users/alice/isAdmin').set(true));
});
test('legacy clinical notes cannot be changed through profile edits', async () => {
  await env.withSecurityRulesDisabled(context => context.database().ref('users/alice/care_plan').set({ clinicalInstructions: 'Original' }));
  const db = env.authenticatedContext('alice').database();
  await assertFails(db.ref('users/alice/care_plan/clinicalInstructions').set('Changed'));
  await assertFails(db.ref('users/alice/care_plan').remove());
});
