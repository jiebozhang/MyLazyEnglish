import { useState, type MouseEvent } from "react";
import type { SubtitleLine, WordToken } from "@/data/mock";
import { motion, AnimatePresence } from "framer-motion";
import { Button } from "@/components/ui/button";

interface SubtitleViewProps {
  line: SubtitleLine;
  active: boolean;
  bilingual: boolean;
  onWordClick: (token: WordToken, line: SubtitleLine) => void;
}

/** 逐句字幕行：当前句高对比高亮 + 单词可点击 Token（水波纹反馈） */
export function SubtitleView({ line, active, bilingual, onWordClick }: SubtitleViewProps) {
  return (
    <div
      className={`rounded-2xl px-4 py-3 transition-all duration-200 ${
        active ? "le-subtitle-active" : ""
      }`}
    >
      <p
        className="text-[19px] leading-7 font-medium"
        style={{ color: active ? "var(--foreground)" : "var(--muted-foreground)" }}
      >
        {line.words.map((tok, i) => (
          <WordTokenButton key={i} token={tok} disabled={!active} onClick={() => onWordClick(tok, line)} />
        ))}
      </p>
      {bilingual && (
        <p className="mt-1 text-[15px] leading-6 text-[color:var(--muted-foreground)]">
          {line.textZh}
        </p>
      )}
    </div>
  );
}

function WordTokenButton({
  token,
  disabled,
  onClick,
}: {
  token: WordToken;
  disabled: boolean;
  onClick: () => void;
}) {
  const [ripples, setRipples] = useState<{ x: number; y: number; id: number }[]>([]);

  if (/^\s+$/.test(token.surface)) return <span>{token.surface}</span>;

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (disabled) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const id = Date.now();
    setRipples((r) => [...r, { x: e.clientX - rect.left, y: e.clientY - rect.top, id }]);
    setTimeout(() => setRipples((r) => r.filter((rp) => rp.id !== id)), 500);
    onClick();
  };

  return (
    <Button
      type="button"
      variant="ghost"
      onClick={handleClick}
      disabled={disabled}
      className={`le-word-token relative inline-flex h-auto min-h-12 items-center rounded-md px-1 py-0.5 align-baseline text-[inherit] font-[inherit] hover:bg-transparent disabled:opacity-100 ${
        token.lookupTarget ? "le-word-lookup" : ""
      }`}
    >
      {token.surface}
      <AnimatePresence>
        {ripples.map((r) => (
          <motion.span
            key={r.id}
            initial={{ scale: 0, opacity: 0.5 }}
            animate={{ scale: 6, opacity: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.5, ease: "easeOut" }}
            className="pointer-events-none absolute rounded-full"
            style={{
              left: r.x - 4,
              top: r.y - 4,
              width: 8,
              height: 8,
              background: "var(--primary)",
            }}
          />
        ))}
      </AnimatePresence>
    </Button>
  );
}
