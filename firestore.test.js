const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { test, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');

const PROJECT_ID = process.env.GCP_PROJECT || 'demo-no-project';
const ALICE_UID = 'alice_123';
const BOB_UID = 'bob_456';

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8085'
).split(':');
const emulatorPort = parseInt(emulatorPortStr, 10);

let testEnv;

function validDspMap() {
  return {
    bypassAll: false,
    masterGainDb: 0,
    chain: [
      { type: 'ECHO_REVERB', enabled: true },
      { type: 'EQUALIZER', enabled: false },
    ],
    echoReverb: {
      echoStrength: 0.4,
      echoDepth: 0.5,
      delayTimeMs: 240,
      feedback: 0.3,
      wetDryMix: 0.35,
    },
  };
}

function validPresetPayload(userId, nowOffsetMs = -10000) {
  const ts = new Date(Date.now() + nowOffsetMs);
  return {
    userId,
    localId: 1001,
    name: 'Warm Broadcast Rack',
    description: 'Clean studio vocal chain',
    dspConfig: validDspMap(),
    createdAt: ts,
    updatedAt: ts,
  };
}

function validDraftPayload(userId, nowOffsetMs = -10000) {
  const ts = new Date(Date.now() + nowOffsetMs);
  return {
    userId,
    name: 'Episode 12 Vocal Draft',
    selectedPresetName: 'Warm Studio Broadcast',
    durationMs: 42000,
    sampleRate: 44100,
    dspConfig: validDspMap(),
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync('./firestore.rules', 'utf8');
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test('Unauthenticated user: rejects read and write across collections', async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p1').get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p1').set(validPresetPayload(ALICE_UID))
  );
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/drafts`).doc('d1').get());
  await assertFails(
    unauthDb.collection(`users/${ALICE_UID}/drafts`).doc('d1').set(validDraftPayload(ALICE_UID))
  );
});

test('Owner: creates, lists, updates, and deletes custom_presets', async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const presetRef = aliceDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p1');

  await assertSucceeds(presetRef.set(validPresetPayload(ALICE_UID)));
  await assertSucceeds(presetRef.get());
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/custom_presets`).orderBy('createdAt', 'desc').get()
  );
  await assertSucceeds(
    presetRef.update({
      name: 'Updated Broadcast',
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(presetRef.delete());
});

test('Owner: creates, lists, updates, and deletes drafts', async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const draftRef = aliceDb.collection(`users/${ALICE_UID}/drafts`).doc('d1');

  await assertSucceeds(draftRef.set(validDraftPayload(ALICE_UID)));
  await assertSucceeds(draftRef.get());
  await assertSucceeds(
    aliceDb.collection(`users/${ALICE_UID}/drafts`).orderBy('updatedAt', 'desc').get()
  );
  await assertSucceeds(
    draftRef.update({
      name: 'Renamed Draft',
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(draftRef.delete());
});

test('Cross-user access: blocks Bob from reading or modifying Alice custom_presets and drafts', async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection(`users/${ALICE_UID}/drafts`).doc('d1').set(validDraftPayload(ALICE_UID));
    await context.firestore().collection(`users/${ALICE_UID}/custom_presets`).doc('p1').set(validPresetPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/drafts`).doc('d1').get());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/drafts`).doc('d1').update({ name: 'Hijacked' }));
  await assertFails(bobDb.collection(`users/${ALICE_UID}/drafts`).doc('d1').delete());
  await assertFails(bobDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p1').get());
  await assertFails(
    bobDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p2').set(validPresetPayload(ALICE_UID))
  );
});

test('Validation bounds: rejects empty/oversized strings, ghost fields, future timestamps, and createdAt tampering', async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const badRef = aliceDb.collection(`users/${ALICE_UID}/drafts`).doc('dBad');

  await assertFails(badRef.set({ ...validDraftPayload(ALICE_UID), name: '' }));
  await assertFails(badRef.set({ ...validDraftPayload(ALICE_UID), name: 'a'.repeat(120) }));
  await assertFails(badRef.set({ ...validDraftPayload(ALICE_UID), extraGhostField: 42 }));
  await assertFails(
    badRef.set({
      ...validDraftPayload(ALICE_UID),
      createdAt: new Date(Date.now() + 60000),
    })
  );

  const goodRef = aliceDb.collection(`users/${ALICE_UID}/drafts`).doc('dGood');
  await assertSucceeds(goodRef.set(validDraftPayload(ALICE_UID)));
  await assertFails(
    goodRef.update({
      createdAt: new Date(1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});
