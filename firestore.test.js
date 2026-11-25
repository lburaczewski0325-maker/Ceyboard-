const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_cipher_123";
const BOB_UID = "bob_cipher_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
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

test("Unauthenticated user cannot read or write vault_keys or preferences", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("vault_keys").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("key_1")
      .set({
        userId: ALICE_UID,
        keyTag: "KEY-8F3A",
        fingerprint: "8F3A9C2D",
        publicKeyToken: "CKPUB1ABCDEF1234567890",
        cipherMode: "HYPER",
        ownIdentity: false,
        createdAt: new Date(Date.now() - 1000),
        updatedAt: new Date(Date.now() - 1000),
      })
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("preferences")
      .doc("settings")
      .get()
  );
});

test("Authenticated owner: Kotlin-mirrored vault_keys create, list, partial update, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const keyRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("vault_keys")
    .doc("key_8f3a");

  await assertSucceeds(
    keyRef.set({
      userId: ALICE_UID,
      keyTag: "Partner-Alpha",
      fingerprint: "8F3A9C2D",
      publicKeyToken: "CKPUB1ABCDEF1234567890XYZ",
      cipherMode: "HYPER",
      ownIdentity: false,
      encryptedSharedWrap: "enc_blob_base64",
      createdAt: created,
      updatedAt: created,
    })
  );

  // List subcollection (mirrors observeVaultKeys)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("vault_keys").get()
  );

  // Partial update of mutable fields
  await assertSucceeds(
    keyRef.update({
      keyTag: "Partner-Renamed",
      cipherMode: "STANDARD",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / @DocumentId `id` injection on create
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("key_ghost")
      .set({
        id: "key_ghost",
        userId: ALICE_UID,
        keyTag: "Bad",
        fingerprint: "1234ABCD",
        publicKeyToken: "CKPUB1ABCDEF1234567890XYZ",
        cipherMode: "HYPER",
        ownIdentity: false,
        createdAt: created,
        updatedAt: created,
      })
  );

  // Reject immutable createdAt modification
  await assertFails(
    keyRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("key_future")
      .set({
        userId: ALICE_UID,
        keyTag: "FutureKey",
        fingerprint: "99AABBCC",
        publicKeyToken: "CKPUB1ABCDEF1234567890XYZ",
        cipherMode: "HYPER",
        ownIdentity: false,
        createdAt: new Date(Date.now() + 60000),
        updatedAt: new Date(Date.now() + 60000),
      })
  );

  // Delete succeeds for owner
  await assertSucceeds(keyRef.delete());
});

test("Cross-user isolation: Bob cannot read, update, or delete Alice's vault keys or preferences", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("alice_key")
      .set({
        userId: ALICE_UID,
        keyTag: "AliceSecret",
        fingerprint: "AA11BB22",
        publicKeyToken: "CKPUB1ABCDEF1234567890XYZ",
        cipherMode: "HYPER",
        ownIdentity: true,
        createdAt: new Date(Date.now() - 10000),
        updatedAt: new Date(Date.now() - 10000),
      });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("alice_key")
      .get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("vault_keys").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("alice_key")
      .update({ keyTag: "Hijacked" })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("vault_keys")
      .doc("alice_key")
      .delete()
  );
});

test("Preferences document: valid settings create and update succeed; invalid enum or wrong docId rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const prefsRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("preferences")
    .doc("settings");

  await assertSucceeds(
    prefsRef.set({
      userId: ALICE_UID,
      hyperEncryptionDefault: true,
      translationDirection: "EN_TO_ES",
      numberRowVisible: true,
      hapticFeedbackEnabled: true,
      autoCheckUpdates: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    prefsRef.update({
      translationDirection: "ES_TO_EN",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Invalid translationDirection enum must fail
  await assertFails(
    prefsRef.update({
      translationDirection: "FR_TO_DE",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Non-settings doc ID must fail
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("preferences")
      .doc("other_doc")
      .set({
        userId: ALICE_UID,
        hyperEncryptionDefault: true,
        translationDirection: "OFF",
        numberRowVisible: true,
        hapticFeedbackEnabled: true,
        autoCheckUpdates: true,
        createdAt: created,
        updatedAt: created,
      })
  );
});
