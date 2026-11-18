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
const OWNER_UID = 'owner_uid_999';
const OWNER_EMAIL = '1000iraq1000@gmail.com';

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

test('Unauthenticated user: rejects read and write across all user and admin collections', async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p1').get());
  await assertFails(unauthDb.collection('admin_settings').doc('finance').get());
  await assertFails(unauthDb.collection('credit_accounts').get());
  await assertFails(unauthDb.collection('payments').get());
  await assertFails(unauthDb.collection('finance_ledger').get());
  await assertFails(unauthDb.collection('dev_changelog').get());
  await assertFails(unauthDb.collection('admin_test_runs').get());
});

test('Owner user workflows: custom_presets & drafts still pass cleanly', async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const presetRef = aliceDb.collection(`users/${ALICE_UID}/custom_presets`).doc('p1');
  await assertSucceeds(presetRef.set(validPresetPayload(ALICE_UID)));
  await assertSucceeds(presetRef.get());

  const draftRef = aliceDb.collection(`users/${ALICE_UID}/drafts`).doc('d1');
  await assertSucceeds(draftRef.set(validDraftPayload(ALICE_UID)));
  await assertSucceeds(draftRef.update({ name: 'Renamed Draft', updatedAt: new Date(Date.now() - 1000) }));
});

test('Zero-trust admin guard: blocks regular users, unverified spoofed emails, and self-promotion to /admins', async () => {
  const regularDb = testEnv.authenticatedContext(BOB_UID, { email: 'user@example.com', email_verified: true }).firestore();
  const spoofedDb = testEnv.authenticatedContext(BOB_UID, { email: OWNER_EMAIL, email_verified: false }).firestore();

  // Regular user cannot self-promote to /admins
  await assertFails(regularDb.collection('admins').doc(BOB_UID).set({ uid: BOB_UID, role: 'OWNER' }));
  // Regular user cannot list or write admin collections
  await assertFails(regularDb.collection('credit_accounts').get());
  await assertFails(regularDb.collection('credit_transactions').get());
  await assertFails(regularDb.collection('payments').get());
  await assertFails(regularDb.collection('finance_ledger').get());
  await assertFails(regularDb.collection('dev_changelog').get());
  await assertFails(regularDb.collection('admin_test_runs').get());
  await assertFails(regularDb.collection('admin_settings').doc('finance').get());

  // Unverified spoofed owner email must also be blocked!
  await assertFails(spoofedDb.collection('finance_ledger').get());
  await assertFails(spoofedDb.collection('credit_accounts').get());

  // Regular user cannot inflate their own credits
  const ts = new Date(Date.now() - 1000);
  await assertFails(
    regularDb.collection('credit_accounts').doc(BOB_UID).set({
      userId: BOB_UID,
      email: 'user@example.com',
      freeCredits: 9999,
      paidCredits: 9999,
      totalSpentCredits: 0,
      totalRefundedCredits: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test('Verified owner account: manages credits, refunds, payments, finance ledger, changelog, and test runs', async () => {
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    ownerDb.collection('admin_settings').doc('finance').set({
      partnerSharePercent: 25,
      partnerName: 'Co-Founder',
      currencyCode: 'USD',
      updatedByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    ownerDb.collection('credit_accounts').doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: 'alice@example.com',
      freeCredits: 50,
      paidCredits: 100,
      totalSpentCredits: 10,
      totalRefundedCredits: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  const txRef = ownerDb.collection('credit_transactions').doc('tx_1');
  await assertSucceeds(
    txRef.set({
      userId: ALICE_UID,
      creditType: 'PAID',
      operation: 'FAILED_CHARGE',
      amount: 10,
      status: 'FAILED',
      reason: 'GPU request timeout',
      engineId: 'voxcpm2',
      performedByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    txRef.update({
      status: 'REFUNDED',
      reason: 'Refunded failed charge to user',
      performedByUid: OWNER_UID,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    ownerDb.collection('payments').doc('pay_1').set({
      userId: ALICE_UID,
      paymentType: 'PURCHASE',
      productId: 'pro_credits_100',
      amountUsd: 9.99,
      status: 'SUCCEEDED',
      provider: 'google_play',
      recordedByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    ownerDb.collection('finance_ledger').doc('fin_1').set({
      entryType: 'HOSTING_COST',
      source: 'Modal L4',
      amountUsd: 4.5,
      periodLabel: '2026-11',
      notes: 'GPU test hours',
      createdByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    ownerDb.collection('dev_changelog').doc('chg_1').set({
      title: 'Developer Panel Owner Guard',
      category: 'IMPLEMENTED',
      status: 'DONE',
      details: 'Server-verified admin rules deployed',
      changeDate: '2026-11-18',
      createdByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    ownerDb.collection('admin_test_runs').doc('run_1').set({
      testType: 'SERVER_CONNECTION',
      engineId: 'voxcpm2',
      passed: false,
      latencyMs: 120,
      summary: 'Backend URL not configured yet',
      errorDetail: 'No GPU server URL configured',
      executedByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Regular user CAN read only their own credit_accounts doc and own filtered transactions/payments
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: 'alice@example.com', email_verified: true }).firestore();
  await assertSucceeds(aliceDb.collection('credit_accounts').doc(ALICE_UID).get());
  await assertSucceeds(aliceDb.collection('credit_transactions').where('userId', '==', ALICE_UID).get());
  await assertSucceeds(aliceDb.collection('payments').where('userId', '==', ALICE_UID).get());
  // Regular user CANNOT read Bob's balance or unfiltered payments
  await assertFails(aliceDb.collection('credit_accounts').doc(BOB_UID).get());
  await assertFails(aliceDb.collection('payments').get());
});
