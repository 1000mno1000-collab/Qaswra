import React from "react";
import { PersistentTransportBar } from "./components/StudioComponents";
import { StudioProvider, useStudio } from "./context/StudioContext";
import { AuthScreen, HomeScreen } from "./screens/AuthAndHomeScreens";
import {
  RecordingStudioScreen,
  VoicePurifierScreen,
} from "./screens/RecordingAndPurifierScreens";
import {
  EchoDetailScreen,
  EchoReverbScreen,
  EffectDetailScreen,
  EffectsChainScreen,
} from "./screens/EchoAndEffectsScreens";
import { AudioEditorScreen } from "./screens/AudioEditorScreen";
import {
  PresetsScreen,
  ProjectsExportScreen,
} from "./screens/PresetsAndProjectsScreens";
import {
  AiStudioScreen,
  AiToolDetailScreen,
  SettingsScreen,
  TextToSpeechStudioScreen,
  VoiceTransformerScreen,
} from "./screens/AiAndVoiceScreens";

const StudioWorkspaceShell: React.FC = () => {
  const { authSession, destination, statusBanner, clearBanner } = useStudio();

  if (authSession.type === "SIGNED_OUT") {
    return (
      <div
        className="min-h-screen w-full"
        style={{
          background:
            "linear-gradient(180deg, #070B14 0%, #0C1220 55%, #070B14 100%)",
        }}
      >
        <AuthScreen />
      </div>
    );
  }

  const renderScreen = () => {
    switch (destination.screen) {
      case "HOME":
        return <HomeScreen />;
      case "RECORD":
        return <RecordingStudioScreen />;
      case "VOICE_PURIFIER":
        return <VoicePurifierScreen />;
      case "ECHO_SPACE":
        return <EchoReverbScreen />;
      case "ECHO_DETAIL":
        return <EchoDetailScreen group={destination.group} />;
      case "EFFECTS_CHAIN":
        return <EffectsChainScreen />;
      case "EFFECT_DETAIL":
        return <EffectDetailScreen effect={destination.effect} />;
      case "EDITOR":
        return <AudioEditorScreen />;
      case "PRESETS":
        return <PresetsScreen />;
      case "PROJECTS_EXPORT":
        return <ProjectsExportScreen />;
      case "AI_STUDIO":
        return <AiStudioScreen />;
      case "AI_TOOL_DETAIL":
        return <AiToolDetailScreen tool={destination.tool} />;
      case "TTS_STUDIO":
        return <TextToSpeechStudioScreen />;
      case "VOICE_TRANSFORMER":
        return <VoiceTransformerScreen />;
      case "SETTINGS":
        return <SettingsScreen />;
    }
  };

  return (
    <div
      className="min-h-screen w-full pb-32"
      style={{
        background:
          "linear-gradient(180deg, #070B14 0%, #0C1220 55%, #070B14 100%)",
      }}
    >
      <main className="max-w-[760px] mx-auto px-4 pt-4">
        {statusBanner && (
          <div className="mb-3 rounded-[14px] bg-[#1B2640] border border-[#22D3EE]/50 px-3.5 py-2.5 flex items-center justify-between gap-2 text-xs font-semibold text-[#22D3EE]">
            <span>{statusBanner}</span>
            <button
              type="button"
              onClick={clearBanner}
              className="text-[#94A3B8] hover:text-[#F1F5F9] font-bold cursor-pointer"
            >
              ×
            </button>
          </div>
        )}

        {renderScreen()}
      </main>

      <PersistentTransportBar />
    </div>
  );
};

export function App() {
  return (
    <StudioProvider>
      <StudioWorkspaceShell />
    </StudioProvider>
  );
}

export default App;
