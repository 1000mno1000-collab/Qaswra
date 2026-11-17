# Voice Pro (Sonix ✦)

Advanced Web Audio Engineering Studio built with React, TypeScript, Tailwind CSS, Web Audio API DSP, and server-side Gemini AI.

## Features
- **Recording Studio & Archive**: Real-time microphone recording (pause/resume/finish), live input meter & waveform monitor, audio file import (WAV/MP3/M4A/OGG/FLAC), and IndexedDB recordings archive.
- **Voice Purifier & Denoise**: 512-pt STFT Wiener spectral denoiser, high-pass rumble filter, hum notch, dynamic de-esser, vocal clarity boost, 4 presets, and deep offline purification.
- **Echo & Reverb Spatial Pad**: Interactive 2D acoustic field pad controlling modulated ping-pong echo, 6-tap early reflections, 4-channel Hadamard FDN, Schroeder combs, and stereo width, with Simple & Pro modes.
- **13-Slot Reorderable Effects Chain**: Noise Reduction, 5-Band EQ (with frequency curve), Bass Boost, Treble, Pitch Shift, Time Stretch (WSOLA), Chorus, Flanger, Phaser, Distortion, Compressor (with live gain-reduction meter), Echo/Reverb, and Peak Limiter.
- **Audio Editor & Mixing**: Draggable waveform selection, Keep/Cut/Normalize, Fade In/Out, Dual-Track Mix, Clip Gain, sequential clip merge, 12-step Undo/Redo, and Multi-format Export (MP3, WAV, M4A, OGG, FLAC).
- **Audio AI Studio**: Streaming Audio Transcription (`gemini-3.5-transcribe`), Smart Auto-Master offline analyzer, and Google Search grounded Audio Engineering Assistant (`gemini-4-hydrogen`).
- **Text-to-Speech Studio (10 Dialects) & Offline Formant Voice Transformer**: Dialect preview & studio synthesis (`gemini-3.8-flash-lite-tts`), plus offline WSOLA + 1024-pt STFT cepstral formant voice character transformation.
- **Trilingual UI**: Instant RTL/LTR switching between Arabic (`ar`), English (`en`), and Persian (`fa`).

## Running
```bash
npm install
npm run dev
```
The server listens on `http://0.0.0.0:3000`.
