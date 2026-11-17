import { readFileSync } from 'node:fs';
import { describe, it, before, after, beforeEach } from 'node:test';
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from '@firebase/rules-unit-testing';
import {
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  query,
  orderBy,
  serverTimestamp,
  Timestamp,
} from 'firebase/firestore';

const PROJECT_ID = 'sonix-rules-test';
const DATABASE_ID = 'ai-studio-android-qaswra-f368630e-45bc-419f-9c35-49ccc76d50c7';

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

function validPresetPayload(userId) {
  return {
    userId,
    localId: 1001,
    name: 'Warm Broadcast Rack',
    description: 'Clean studio vocal chain',
    dspConfig: validDspMap(),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

function validDraftPayload(userId) {
  return {
    userId,
    name: 'Episode 12 Vocal Draft',
    selectedPresetName: 'Warm Studio Broadcast',
    durationMs: 42000,
    sampleRate: 44100,
    dspConfig: validDspMap(),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  };
}

describe('Sonix Firestore Security Rules', () => {
  before(async () => {
    const rules = readFileSync('firestore.rules', 'utf8');
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules,
        host: '127.0.0.1',
        port: 8085,
        databaseId: DATABASE_ID,
      },
    });
  });

  after(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  it('rejects unauthenticated read and write across collections', async () => {
    const unauthDb = testEnv.unauthenticatedContext().firestore(DATABASE_ID);
    await assertFails(getDoc(doc(unauthDb, 'users/userA/custom_presets/p1')));
    await assertFails(setDoc(doc(unauthDb, 'users/userA/custom_presets/p1'), validPresetPayload('userA')));
    await assertFails(getDoc(doc(unauthDb, 'users/userA/drafts/d1')));
    await assertFails(setDoc(doc(unauthDb, 'users/userA/drafts/d1'), validDraftPayload('userA')));
  });

  it('allows owner to create, list, update, and delete custom_presets', async () => {
    const aliceDb = testEnv.authenticatedContext('userA').firestore(DATABASE_ID);
    const presetRef = doc(aliceDb, 'users/userA/custom_presets/p1');

    await assertSucceeds(setDoc(presetRef, validPresetPayload('userA')));
    await assertSucceeds(getDoc(presetRef));
    await assertSucceeds(getDocs(query(collection(aliceDb, 'users/userA/custom_presets'), orderBy('createdAt'))));
    await assertSucceeds(updateDoc(presetRef, { name: 'Updated Broadcast', updatedAt: serverTimestamp() }));
    await assertSucceeds(deleteDoc(presetRef));
  });

  it('allows owner to create, list, update, and delete drafts', async () => {
    const aliceDb = testEnv.authenticatedContext('userA').firestore(DATABASE_ID);
    const draftRef = doc(aliceDb, 'users/userA/drafts/d1');

    await assertSucceeds(setDoc(draftRef, validDraftPayload('userA')));
    await assertSucceeds(getDoc(draftRef));
    await assertSucceeds(getDocs(query(collection(aliceDb, 'users/userA/drafts'), orderBy('updatedAt'))));
    await assertSucceeds(updateDoc(draftRef, { name: 'Renamed Draft', updatedAt: serverTimestamp() }));
    await assertSucceeds(deleteDoc(draftRef));
  });

  it('blocks cross-user access to custom_presets and drafts', async () => {
    const aliceDb = testEnv.authenticatedContext('userA').firestore(DATABASE_ID);
    const bobDb = testEnv.authenticatedContext('userB').firestore(DATABASE_ID);

    await assertSucceeds(setDoc(doc(aliceDb, 'users/userA/drafts/d1'), validDraftPayload('userA')));
    await assertFails(getDoc(doc(bobDb, 'users/userA/drafts/d1')));
    await assertFails(updateDoc(doc(bobDb, 'users/userA/drafts/d1'), { name: 'Hijacked' }));
    await assertFails(deleteDoc(doc(bobDb, 'users/userA/drafts/d1')));
    await assertFails(setDoc(doc(bobDb, 'users/userA/custom_presets/p2'), validPresetPayload('userA')));
  });

  it('rejects oversized strings, ghost fields, future timestamps, and createdAt tampering', async () => {
    const aliceDb = testEnv.authenticatedContext('userA').firestore(DATABASE_ID);
    const draftRef = doc(aliceDb, 'users/userA/drafts/dBad');

    await assertFails(setDoc(draftRef, { ...validDraftPayload('userA'), name: '' }));
    await assertFails(setDoc(draftRef, { ...validDraftPayload('userA'), name: 'a'.repeat(120) }));
    await assertFails(setDoc(draftRef, { ...validDraftPayload('userA'), extraGhostField: 42 }));
    await assertFails(setDoc(draftRef, {
      ...validDraftPayload('userA'),
      createdAt: Timestamp.fromMillis(Date.now() + 3600_000),
    }));

    const goodRef = doc(aliceDb, 'users/userA/drafts/dGood');
    await assertSucceeds(setDoc(goodRef, validDraftPayload('userA')));
    await assertFails(updateDoc(goodRef, {
      createdAt: Timestamp.fromMillis(1000),
      updatedAt: serverTimestamp(),
    }));
  });
});
