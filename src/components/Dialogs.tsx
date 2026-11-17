import React, { useState } from "react";
import { CheckCircle2, FileAudio, Globe, X } from "lucide-react";
import {
  AppLanguage,
  EXPORT_FORMAT_META,
  ExportAudioFormat,
} from "../types/studio";
import { APP_LANGUAGES, t } from "../i18n/translations";

export const LanguageBadgeButton: React.FC<{
  currentLanguage: AppLanguage;
  onClick: () => void;
}> = ({ currentLanguage, onClick }) => {
  const current = APP_LANGUAGES.find((l) => l.tag === currentLanguage) ?? APP_LANGUAGES[0];
  return (
    <button
      type="button"
      onClick={onClick}
      className="h-8 px-2.5 rounded-[12px] bg-[#1B2640]/90 hover:bg-[#243252] border border-[#22D3EE]/45 text-[#22D3EE] flex items-center gap-1.5 text-xs font-bold transition cursor-pointer"
    >
      <Globe className="w-3.5 h-3.5" />
      <span>{current.shortBadge}</span>
    </button>
  );
};

export const LanguageSelectorDialog: React.FC<{
  open: boolean;
  currentLanguage: AppLanguage;
  onSelect: (lang: AppLanguage) => void;
  onClose: () => void;
}> = ({ open, currentLanguage, onSelect, onClose }) => {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-md rounded-[24px] bg-[#131B2E] border border-[#283759] p-5 space-y-4 shadow-2xl">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-[#22D3EE]">
            <Globe className="w-5 h-5" />
            <h2 className="text-lg font-bold text-[#F1F5F9]">
              {t(currentLanguage, "choose_language_title")}
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-full bg-[#1B2640] text-[#94A3B8] hover:text-[#F1F5F9] flex items-center justify-center cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <p className="text-xs text-[#94A3B8]">
          {t(currentLanguage, "language_direction_hint")}
        </p>

        <div className="space-y-2.5">
          {APP_LANGUAGES.map((lang) => {
            const selected = lang.tag === currentLanguage;
            return (
              <button
                key={lang.tag}
                type="button"
                onClick={() => {
                  onSelect(lang.tag);
                  onClose();
                }}
                className={`w-full rounded-[16px] px-4 py-3 flex items-center justify-between border transition cursor-pointer ${
                  selected
                    ? "bg-[#10B981]/15 border-[#10B981] text-[#10B981]"
                    : "bg-[#070B14] border-[#283759] text-[#F1F5F9] hover:border-[#3B4E7A]"
                }`}
              >
                <div className="text-start">
                  <div className="font-bold text-sm">{lang.nativeName}</div>
                  <div className="text-[11px] text-[#94A3B8]">
                    {lang.isRtl ? "RTL" : "LTR"}
                  </div>
                </div>
                {selected && <CheckCircle2 className="w-5 h-5 text-[#10B981]" />}
              </button>
            );
          })}
        </div>

        <div className="flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 rounded-[12px] text-xs font-semibold text-[#94A3B8] hover:text-[#F1F5F9] cursor-pointer"
          >
            {t(currentLanguage, "cancel")}
          </button>
        </div>
      </div>
    </div>
  );
};

export const ExportFormatDialog: React.FC<{
  open: boolean;
  language: AppLanguage;
  initialFileName: string;
  onClose: () => void;
  onConfirm: (format: ExportAudioFormat, fileName: string) => void;
}> = ({ open, language, initialFileName, onClose, onConfirm }) => {
  const [fileName, setFileName] = useState(initialFileName || "VoicePro_Export");
  const [selectedFormat, setSelectedFormat] = useState<ExportAudioFormat>(
    ExportAudioFormat.MP3
  );

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
      <div className="w-full max-w-md rounded-[24px] bg-[#131B2E] border border-[#283759] p-5 space-y-4 shadow-2xl max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-[#22D3EE]">
            {t(language, "choose_export_format_title")}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="w-8 h-8 rounded-full bg-[#1B2640] text-[#94A3B8] hover:text-[#F1F5F9] flex items-center justify-center cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-semibold text-[#94A3B8] block">
            اسم الملف النهائي
          </label>
          <input
            type="text"
            value={fileName}
            onChange={(e) => setFileName(e.target.value)}
            className="w-full h-11 rounded-[12px] bg-[#070B14] border border-[#283759] focus:border-[#22D3EE] px-3 text-sm text-[#F1F5F9] outline-none"
          />
        </div>

        <div className="space-y-2">
          {Object.values(ExportAudioFormat).map((fmt) => {
            const meta = EXPORT_FORMAT_META[fmt];
            const selected = selectedFormat === fmt;
            return (
              <button
                key={fmt}
                type="button"
                onClick={() => setSelectedFormat(fmt)}
                className={`w-full rounded-[14px] px-3.5 py-2.5 flex items-center gap-3 border text-start transition cursor-pointer ${
                  selected
                    ? "bg-[#22D3EE]/15 border-[#22D3EE]"
                    : "bg-[#070B14] border-[#283759] hover:border-[#3B4E7A]"
                }`}
              >
                {selected ? (
                  <CheckCircle2 className="w-5 h-5 text-[#22D3EE] shrink-0" />
                ) : (
                  <FileAudio className="w-5 h-5 text-[#94A3B8] shrink-0" />
                )}
                <div className="flex-1 min-w-0">
                  <div
                    className={`text-sm font-bold ${
                      selected ? "text-[#22D3EE]" : "text-[#F1F5F9]"
                    }`}
                  >
                    {meta.displayName} (.{meta.extension})
                  </div>
                  <div className="text-xs text-[#94A3B8] truncate">{meta.subtitle}</div>
                </div>
              </button>
            );
          })}
        </div>

        <div className="flex items-center justify-end gap-2 pt-1">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 rounded-[12px] text-xs font-semibold text-[#94A3B8] hover:text-[#F1F5F9] cursor-pointer"
          >
            {t(language, "cancel")}
          </button>
          <button
            type="button"
            onClick={() => {
              onClose();
              onConfirm(selectedFormat, fileName);
            }}
            className="h-10 px-5 rounded-[14px] bg-[#10B981] hover:bg-[#34D399] text-[#070B14] font-bold text-xs cursor-pointer"
          >
            {t(language, "save_export_audio")}
          </button>
        </div>
      </div>
    </div>
  );
};
