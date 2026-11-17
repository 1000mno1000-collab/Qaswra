# Voice Pro — Sonix ✦ (Android)

Advanced Android Audio Engineering Studio built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, **DataStore**, and an on-device **44.1 kHz Stereo DSP Engine**.

## Architecture & Tech Stack
- **UI**: Jetpack Compose + Material 3 (`VoiceProTheme`), edge-to-edge layout (`enableEdgeToEdge()`), dynamic RTL/LTR switching (`ar`, `en`, `fa`), custom interactive `Canvas` components (real-time waveform visualizer, 2D Echo/Reverb spatial pad, 5-band EQ curve, draggable trim/cut editor), and type-safe `@Serializable` Navigation Compose.
- **Audio & DSP Engine (`com.example.audio`)**:
  - Live `AudioRecord` microphone capture (`RECORD_AUDIO` runtime permission) and `AudioTrack` (`ENCODING_PCM_FLOAT`, `MODE_STREAM`) real-time DSP playback with A/B tap-and-hold bypass.
  - 512-pt STFT Wiener spectral denoiser, high-pass filter, de-esser, and clarity boost (`VoicePurifier`).
  - Modulated ping-pong echo, 6-tap early reflections, 4-channel Hadamard FDN, Schroeder combs, and stereo width (`EchoReverbProcessor`).
  - 13-slot reorderable effects chain (`NOISE_REDUCTION`, `EQUALIZER`, `BASS_BOOST`, `TREBLE`, `PITCH_SHIFT`, `TIME_STRETCH`, `CHORUS`, `FLANGER`, `PHASER`, `DISTORTION`, `COMPRESSOR`, `ECHO_REVERB`, `LIMITER`) plus offline WSOLA time-stretch + 1024-pt STFT cepstral formant voice character transformer (`FormantTransformer`).
  - `MediaExtractor` / `MediaCodec` audio import (`ActivityResultContracts.OpenDocument`), pure-Kotlin FLAC / WAV / `MediaMuxer` M4A-AAC export (`ActivityResultContracts.CreateDocument`), and `FileProvider` share sheet.
  - Android `TextToSpeech` engine (`AndroidTtsEngine`) supporting 10 dialects/languages with live preview and file synthesis into the studio timeline.
  - On-device `SmartAutoMaster` spectral & dynamics analyzer.
- **Persistence (`com.example.data`)**: Room database (`projects`, `recordings`, `custom_presets`, `local_accounts`), Preferences DataStore, and internal WAV file storage.
- **Cloud AI (`com.example.data.ai`)**: `AiRepository` interface with `FirebaseUnprovisionedAiRepository` placeholder; cloud transcription and web-grounded AI search require provisioning **Firebase AI Logic (`ai_logic`)** with `google-services.json` and signing SHA certificates.

## Build Configuration
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, JDK 21
- Gradle Kotlin DSL (`build.gradle.kts`) with Version Catalog (`gradle/libs.versions.toml`)
