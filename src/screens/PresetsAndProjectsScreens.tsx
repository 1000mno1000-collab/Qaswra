import React, { useState } from "react";
import {
  BookmarkPlus,
  CheckCircle2,
  Download,
  FilePlus2,
  FolderOpen,
  Save,
  Trash2,
} from "lucide-react";
import { formatDurationMs } from "../audio/audioEditorOps";
import { ExportFormatDialog } from "../components/Dialogs";
import { StudioFocusTopBar } from "../components/StudioComponents";
import { useStudio } from "../context/StudioContext";
import { t } from "../i18n/translations";
import { ExportAudioFormat } from "../types/studio";

export const PresetsScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    allPresets,
    activePresetId,
    applyPreset,
    saveCustomPreset,
    deleteCustomPreset,
  } = useStudio();

  const [newName, setNewName] = useState("");
  const [newDesc, setNewDesc] = useState("");

  const customList = allPresets.filter((p) => !p.isBuiltIn);
  const builtInList = allPresets.filter((p) => p.isBuiltIn);

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newName.trim()) return;
    saveCustomPreset(newName, newDesc);
    setNewName("");
    setNewDesc("");
  };

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_presets_title")}
        subtitle={t(language, "section_presets_subtitle")}
        onBack={navigateBack}
      />

      <form
        onSubmit={handleSave}
        className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3"
      >
        <h2 className="text-sm font-bold text-[#10B981]">
          {t(language, "save_custom_preset")}
        </h2>
        <input
          type="text"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          placeholder={t(language, "preset_name_hint")}
          className="w-full h-11 rounded-[12px] bg-[#070B14] border border-[#283759] focus:border-[#10B981] px-3 text-sm text-[#F1F5F9] outline-none"
        />
        <input
          type="text"
          value={newDesc}
          onChange={(e) => setNewDesc(e.target.value)}
          placeholder="وصف مختصر (اختياري)"
          className="w-full h-10 rounded-[12px] bg-[#070B14] border border-[#283759] focus:border-[#10B981] px-3 text-xs text-[#F1F5F9] outline-none"
        />
        <button
          type="submit"
          disabled={!newName.trim()}
          className="w-full h-11 rounded-[14px] bg-[#10B981] hover:bg-[#34D399] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-2 cursor-pointer"
        >
          <BookmarkPlus className="w-4 h-4" />
          <span>{t(language, "save_custom_preset")}</span>
        </button>
      </form>

      {customList.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-xs font-bold text-[#94A3B8] px-1">
            {t(language, "custom_presets")}
          </h3>
          {customList.map((preset) => {
            const active = activePresetId === preset.id;
            return (
              <div
                key={preset.id}
                className={`rounded-[18px] p-3.5 border flex items-center justify-between gap-2 ${
                  active
                    ? "bg-[#131B2E] border-[#10B981]"
                    : "bg-[#131B2E] border-[#283759]"
                }`}
              >
                <button
                  type="button"
                  onClick={() => applyPreset(preset)}
                  className="flex-1 text-start cursor-pointer"
                >
                  <div className="text-sm font-bold text-[#F1F5F9] flex items-center gap-2">
                    <span>{preset.name}</span>
                    {active && <CheckCircle2 className="w-4 h-4 text-[#10B981]" />}
                  </div>
                  <div className="text-xs text-[#94A3B8]">{preset.description}</div>
                </button>
                <button
                  type="button"
                  onClick={() => deleteCustomPreset(preset.id)}
                  className="w-9 h-9 rounded-[10px] bg-[#1B2640] text-[#F43F5E] flex items-center justify-center cursor-pointer"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            );
          })}
        </div>
      )}

      <div className="space-y-2">
        <h3 className="text-xs font-bold text-[#94A3B8] px-1">
          {t(language, "built_in_presets")}
        </h3>
        {builtInList.map((preset) => {
          const active = activePresetId === preset.id;
          return (
            <button
              key={preset.id}
              type="button"
              onClick={() => applyPreset(preset)}
              className={`w-full rounded-[18px] p-4 border text-start flex items-center justify-between gap-3 transition cursor-pointer ${
                active
                  ? "bg-[#131B2E] border-[#22D3EE]"
                  : "bg-[#131B2E] border-[#283759] hover:border-[#384C7A]"
              }`}
            >
              <div>
                <div className="text-sm font-bold text-[#F1F5F9]">{preset.name}</div>
                <div className="text-xs text-[#94A3B8] mt-0.5">{preset.description}</div>
              </div>
              {active && <CheckCircle2 className="w-5 h-5 text-[#22D3EE] shrink-0" />}
            </button>
          );
        })}
      </div>
    </div>
  );
};

export const ProjectsExportScreen: React.FC = () => {
  const {
    language,
    navigateBack,
    activeProjectName,
    setActiveProjectName,
    projects,
    newProject,
    saveCurrentProject,
    loadProject,
    deleteProject,
    hasAudio,
    isBusy,
    exportProgress,
    exportRenderedAudio,
  } = useStudio();

  const [showExportDialog, setShowExportDialog] = useState(false);

  return (
    <div className="space-y-4">
      <StudioFocusTopBar
        title={t(language, "section_projects_title")}
        subtitle={t(language, "section_projects_subtitle")}
        onBack={navigateBack}
      />

      {/* Active Project Card */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <label className="text-xs font-semibold text-[#94A3B8] block">
          {t(language, "active_project")}
        </label>
        <input
          type="text"
          value={activeProjectName}
          onChange={(e) => setActiveProjectName(e.target.value)}
          className="w-full h-11 rounded-[12px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] px-3 text-sm text-[#F1F5F9] outline-none"
        />
        <div className="grid grid-cols-2 gap-2.5">
          <button
            type="button"
            disabled={isBusy}
            onClick={saveCurrentProject}
            className="h-11 rounded-[14px] bg-[#10B981] hover:bg-[#34D399] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
          >
            <Save className="w-4 h-4" />
            <span>{t(language, "save_project")}</span>
          </button>

          <button
            type="button"
            onClick={newProject}
            className="h-11 rounded-[14px] bg-[#1B2640] border border-[#283759] text-[#F1F5F9] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
          >
            <FilePlus2 className="w-4 h-4" />
            <span>{t(language, "new_project")}</span>
          </button>
        </div>
      </div>

      {/* Export Studio Card */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#22D3EE]/50 p-4 space-y-3">
        <h2 className="text-sm font-bold text-[#22D3EE]">
          تصدير الصوت النهائي مع جميع المؤثرات (DSP Mastered)
        </h2>

        {exportProgress !== null && (
          <div className="space-y-1">
            <div className="text-xs text-[#94A3B8]">
              {t(language, "exporting_progress")} ({Math.round(exportProgress * 100)}%)
            </div>
            <div className="w-full h-2 rounded-full bg-[#070B14] overflow-hidden">
              <div
                className="h-full bg-[#22D3EE] transition-all"
                style={{ width: `${Math.round(exportProgress * 100)}%` }}
              />
            </div>
          </div>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
          <button
            type="button"
            disabled={!hasAudio || isBusy}
            onClick={() => exportRenderedAudio(ExportAudioFormat.MP3, activeProjectName)}
            className="h-11 rounded-[14px] bg-[#22D3EE] hover:bg-[#38BDF8] disabled:bg-[#1B2640] disabled:text-[#64748B] text-[#070B14] font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
          >
            <Download className="w-4 h-4" />
            <span>{t(language, "export_mp3")}</span>
          </button>

          <button
            type="button"
            disabled={!hasAudio || isBusy}
            onClick={() => exportRenderedAudio(ExportAudioFormat.WAV, activeProjectName)}
            className="h-11 rounded-[14px] bg-[#1B2640] border border-[#22D3EE]/60 text-[#22D3EE] disabled:opacity-40 font-bold text-xs flex items-center justify-center gap-1.5 cursor-pointer"
          >
            <Download className="w-4 h-4" />
            <span>{t(language, "export_wav")}</span>
          </button>
        </div>

        <button
          type="button"
          disabled={!hasAudio || isBusy}
          onClick={() => setShowExportDialog(true)}
          className="w-full h-11 rounded-[14px] bg-[#A78BFA]/20 border border-[#A78BFA]/60 text-[#A78BFA] disabled:opacity-40 font-bold text-xs cursor-pointer"
        >
          تصدير متقدم (MP3 / WAV / M4A / OGG / FLAC)
        </button>
      </div>

      {/* Saved Projects */}
      <div className="rounded-[22px] bg-[#131B2E] border border-[#283759] p-4 space-y-3">
        <h2 className="text-sm font-bold text-[#F1F5F9]">
          {t(language, "saved_projects")} ({projects.length})
        </h2>
        {projects.length === 0 ? (
          <p className="text-xs text-[#64748B]">
            لا توجد مشاريع محفوظة بعد — اضغط "حفظ المشروع" لحفظ عملك الصوتي.
          </p>
        ) : (
          <div className="space-y-2">
            {projects.map((p) => (
              <div
                key={p.id}
                className="rounded-[16px] bg-[#070B14] border border-[#283759] px-3.5 py-2.5 flex items-center justify-between gap-2"
              >
                <div className="min-w-0">
                  <div className="text-sm font-bold text-[#F1F5F9] truncate">{p.name}</div>
                  <div className="text-[11px] text-[#94A3B8] font-mono-telemetry">
                    {formatDurationMs(p.durationMs)} • {p.sampleRate} Hz
                  </div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <button
                    type="button"
                    onClick={() => loadProject(p)}
                    className="h-9 px-3 rounded-[10px] bg-[#22D3EE]/15 border border-[#22D3EE]/50 text-[#22D3EE] text-xs font-bold flex items-center gap-1 cursor-pointer"
                  >
                    <FolderOpen className="w-3.5 h-3.5" />
                    <span>{t(language, "load")}</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => deleteProject(p)}
                    className="h-9 w-9 rounded-[10px] bg-[#1B2640] text-[#F43F5E] flex items-center justify-center cursor-pointer"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <ExportFormatDialog
        open={showExportDialog}
        language={language}
        initialFileName={activeProjectName}
        onClose={() => setShowExportDialog(false)}
        onConfirm={(fmt, name) => exportRenderedAudio(fmt, name)}
      />
    </div>
  );
};
