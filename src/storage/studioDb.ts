import {
  AppLanguage,
  AudioRecordingEntity,
  AuthSession,
  DspConfig,
  StereoAudioBuffer,
  StudioPreset,
  StudioProject,
  normalizeDspConfig,
} from "../types/studio";

const DB_NAME = "voice_pro_studio_db";
const DB_VERSION = 1;

const STORE_PROJECTS = "projects";
const STORE_RECORDINGS = "recordings";
const STORE_PRESETS = "presets";

interface StoredProjectRecord {
  id?: number;
  name: string;
  durationMs: number;
  sampleRate: number;
  config: DspConfig;
  updatedAt: number;
  left?: ArrayBuffer;
  right?: ArrayBuffer;
}

interface StoredRecordingRecord {
  id?: number;
  title: string;
  timestamp: number;
  durationMs: number;
  sampleRate: number;
  sourceType: "RECORDING" | "IMPORTED" | "TTS";
  left: ArrayBuffer;
  right: ArrayBuffer;
}

interface StoredPresetRecord {
  id?: number;
  name: string;
  description: string;
  config: DspConfig;
  createdAt: number;
}

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_PROJECTS)) {
        db.createObjectStore(STORE_PROJECTS, { keyPath: "id", autoIncrement: true });
      }
      if (!db.objectStoreNames.contains(STORE_RECORDINGS)) {
        db.createObjectStore(STORE_RECORDINGS, { keyPath: "id", autoIncrement: true });
      }
      if (!db.objectStoreNames.contains(STORE_PRESETS)) {
        db.createObjectStore(STORE_PRESETS, { keyPath: "id", autoIncrement: true });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function float32ToBuffer(arr: Float32Array): ArrayBuffer {
  const copy = new Float32Array(arr.length);
  copy.set(arr);
  return copy.buffer;
}

export async function listProjects(): Promise<StudioProject[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PROJECTS, "readonly");
    const req = tx.objectStore(STORE_PROJECTS).getAll();
    req.onsuccess = () => {
      const rows = (req.result as StoredProjectRecord[]) ?? [];
      rows.sort((a, b) => b.updatedAt - a.updatedAt);
      resolve(
        rows.map((r) => ({
          id: r.id ?? 0,
          name: r.name,
          durationMs: r.durationMs,
          sampleRate: r.sampleRate,
          config: normalizeDspConfig(r.config),
          updatedAt: r.updatedAt,
          hasAudio: Boolean(r.left && r.right),
        }))
      );
    };
    req.onerror = () => reject(req.error);
  });
}

export async function saveProjectRecord(
  existingId: number,
  name: string,
  buffer: StereoAudioBuffer | null,
  config: DspConfig
): Promise<StudioProject> {
  const db = await openDb();
  const cleanName = name.trim() || "مشروع صوتي";
  const now = Date.now();
  const record: StoredProjectRecord = {
    ...(existingId > 0 ? { id: existingId } : {}),
    name: cleanName,
    durationMs: buffer ? Math.round((buffer.left.length * 1000) / buffer.sampleRate) : 0,
    sampleRate: buffer?.sampleRate ?? 44100,
    config,
    updatedAt: now,
    ...(buffer
      ? {
          left: float32ToBuffer(buffer.left),
          right: float32ToBuffer(buffer.right),
        }
      : {}),
  };

  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PROJECTS, "readwrite");
    const req = tx.objectStore(STORE_PROJECTS).put(record);
    req.onsuccess = () => {
      resolve({
        id: Number(req.result),
        name: cleanName,
        durationMs: record.durationMs,
        sampleRate: record.sampleRate,
        config,
        updatedAt: now,
        hasAudio: Boolean(buffer),
      });
    };
    req.onerror = () => reject(req.error);
  });
}

export async function loadProjectAudioBuffer(
  projectId: number
): Promise<StereoAudioBuffer | null> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PROJECTS, "readonly");
    const req = tx.objectStore(STORE_PROJECTS).get(projectId);
    req.onsuccess = () => {
      const rec = req.result as StoredProjectRecord | undefined;
      if (!rec?.left || !rec?.right) {
        resolve(null);
        return;
      }
      resolve({
        left: new Float32Array(rec.left),
        right: new Float32Array(rec.right),
        sampleRate: rec.sampleRate,
      });
    };
    req.onerror = () => reject(req.error);
  });
}

export async function deleteProjectRecord(projectId: number): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PROJECTS, "readwrite");
    const req = tx.objectStore(STORE_PROJECTS).delete(projectId);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

export async function listRecordings(): Promise<AudioRecordingEntity[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_RECORDINGS, "readonly");
    const req = tx.objectStore(STORE_RECORDINGS).getAll();
    req.onsuccess = () => {
      const rows = (req.result as StoredRecordingRecord[]) ?? [];
      rows.sort((a, b) => b.timestamp - a.timestamp);
      resolve(
        rows.map((r) => ({
          id: r.id ?? 0,
          title: r.title,
          timestamp: r.timestamp,
          durationMs: r.durationMs,
          sampleRate: r.sampleRate,
          sourceType: r.sourceType,
        }))
      );
    };
    req.onerror = () => reject(req.error);
  });
}

export async function indexAudioRecording(
  title: string,
  buffer: StereoAudioBuffer,
  sourceType: "RECORDING" | "IMPORTED" | "TTS"
): Promise<AudioRecordingEntity> {
  const db = await openDb();
  const now = Date.now();
  const cleanTitle =
    title.trim() || (sourceType === "IMPORTED" ? "ملف مستورد" : "تسجيل صوتي");
  const rec: StoredRecordingRecord = {
    title: cleanTitle,
    timestamp: now,
    durationMs: Math.round((buffer.left.length * 1000) / buffer.sampleRate),
    sampleRate: buffer.sampleRate,
    sourceType,
    left: float32ToBuffer(buffer.left),
    right: float32ToBuffer(buffer.right),
  };

  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_RECORDINGS, "readwrite");
    const req = tx.objectStore(STORE_RECORDINGS).add(rec);
    req.onsuccess = () => {
      resolve({
        id: Number(req.result),
        title: cleanTitle,
        timestamp: now,
        durationMs: rec.durationMs,
        sampleRate: rec.sampleRate,
        sourceType,
      });
    };
    req.onerror = () => reject(req.error);
  });
}

export async function loadRecordingBuffer(
  id: number
): Promise<StereoAudioBuffer | null> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_RECORDINGS, "readonly");
    const req = tx.objectStore(STORE_RECORDINGS).get(id);
    req.onsuccess = () => {
      const rec = req.result as StoredRecordingRecord | undefined;
      if (!rec) {
        resolve(null);
        return;
      }
      resolve({
        left: new Float32Array(rec.left),
        right: new Float32Array(rec.right),
        sampleRate: rec.sampleRate,
      });
    };
    req.onerror = () => reject(req.error);
  });
}

export async function deleteRecordingRecord(id: number): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_RECORDINGS, "readwrite");
    const req = tx.objectStore(STORE_RECORDINGS).delete(id);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

export async function listCustomPresets(): Promise<StudioPreset[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PRESETS, "readonly");
    const req = tx.objectStore(STORE_PRESETS).getAll();
    req.onsuccess = () => {
      const rows = (req.result as StoredPresetRecord[]) ?? [];
      rows.sort((a, b) => b.createdAt - a.createdAt);
      resolve(
        rows.map((r) => ({
          id: r.id ?? 0,
          name: r.name,
          description: r.description,
          isBuiltIn: false,
          config: normalizeDspConfig(r.config),
        }))
      );
    };
    req.onerror = () => reject(req.error);
  });
}

export async function saveCustomPresetRecord(
  name: string,
  description: string,
  config: DspConfig
): Promise<void> {
  const db = await openDb();
  const rec: StoredPresetRecord = {
    name: name.trim(),
    description: description.trim() || "قالب صوتي مخصص",
    config,
    createdAt: Date.now(),
  };
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PRESETS, "readwrite");
    const req = tx.objectStore(STORE_PRESETS).add(rec);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

export async function deleteCustomPresetRecord(id: number): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE_PRESETS, "readwrite");
    const req = tx.objectStore(STORE_PRESETS).delete(id);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error);
  });
}

// Local preferences & session helpers
const KEY_LANG = "voice_pro_language";
const KEY_AUTH = "voice_pro_auth_session";
const KEY_AUTO_LOOP = "voice_pro_auto_loop";
const KEY_USERS = "voice_pro_local_accounts";

export function loadSavedLanguage(): AppLanguage {
  const v = localStorage.getItem(KEY_LANG);
  if (v === "ar" || v === "en" || v === "fa") return v;
  return "ar";
}

export function saveLanguagePref(lang: AppLanguage): void {
  localStorage.setItem(KEY_LANG, lang);
}

export function loadAutoLoopPref(): boolean {
  return localStorage.getItem(KEY_AUTO_LOOP) === "true";
}

export function saveAutoLoopPref(val: boolean): void {
  localStorage.setItem(KEY_AUTO_LOOP, val ? "true" : "false");
}

export function loadAuthSession(): AuthSession {
  try {
    const raw = localStorage.getItem(KEY_AUTH);
    if (!raw) return { type: "SIGNED_OUT" };
    return JSON.parse(raw) as AuthSession;
  } catch {
    return { type: "SIGNED_OUT" };
  }
}

export function saveAuthSession(session: AuthSession): void {
  localStorage.setItem(KEY_AUTH, JSON.stringify(session));
}

interface LocalAccount {
  uid: string;
  displayName: string;
  email: string;
  password: string;
}

export function signInLocalEmail(
  email: string,
  password: string
): { ok: true; session: AuthSession } | { ok: false; error: string } {
  const cleanEmail = email.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) {
    return { ok: false, error: "يرجى إدخال بريد إلكتروني صحيح." };
  }
  if (password.length < 6) {
    return { ok: false, error: "يرجى إدخال كلمة المرور (6 أحرف فأكثر)." };
  }
  const accounts: LocalAccount[] = JSON.parse(localStorage.getItem(KEY_USERS) || "[]");
  const found = accounts.find((a) => a.email === cleanEmail);
  if (!found) {
    return { ok: false, error: "لا يوجد حساب مسجل بهذا البريد الإلكتروني." };
  }
  if (found.password !== password) {
    return { ok: false, error: "البريد الإلكتروني أو كلمة المرور غير صحيحة." };
  }
  const session: AuthSession = {
    type: "SIGNED_IN",
    uid: found.uid,
    displayName: found.displayName,
    email: found.email,
  };
  saveAuthSession(session);
  return { ok: true, session };
}

export function registerLocalEmail(
  displayName: string,
  email: string,
  password: string
): { ok: true; session: AuthSession } | { ok: false; error: string } {
  const cleanEmail = email.trim().toLowerCase();
  const cleanName = displayName.trim() || cleanEmail.split("@")[0] || "مستخدم Voice Pro";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(cleanEmail)) {
    return { ok: false, error: "يرجى إدخال بريد إلكتروني صحيح." };
  }
  if (password.length < 6) {
    return { ok: false, error: "كلمة المرور يجب أن تكون 6 أحرف على الأقل." };
  }
  const accounts: LocalAccount[] = JSON.parse(localStorage.getItem(KEY_USERS) || "[]");
  if (accounts.some((a) => a.email === cleanEmail)) {
    return { ok: false, error: "هذا البريد الإلكتروني مستخدم بالفعل بحساب آخر." };
  }
  const newAcc: LocalAccount = {
    uid: `local_${Date.now()}`,
    displayName: cleanName,
    email: cleanEmail,
    password,
  };
  accounts.push(newAcc);
  localStorage.setItem(KEY_USERS, JSON.stringify(accounts));
  const session: AuthSession = {
    type: "SIGNED_IN",
    uid: newAcc.uid,
    displayName: newAcc.displayName,
    email: newAcc.email,
  };
  saveAuthSession(session);
  return { ok: true, session };
}
