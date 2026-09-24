import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { Volume2, Clock, RefreshCw, Sparkles, BookPlus, Check, X, AlertCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { SubtitleLine, WordToken } from "@/data/mock";
import { dictionaryEntries, aiContextData, formatTimestamp } from "@/data/mock";

interface Props {
  token: WordToken | null;
  line: SubtitleLine | null;
  open: boolean;
  onClose: () => void;
  onAddToVocab: (token: WordToken, line: SubtitleLine) => void;
  onJumpToTimestamp: (line: SubtitleLine) => void;
}

export function ContextDictionarySheet({ token, line, open, onClose, onAddToVocab, onJumpToTimestamp }: Props) {
  const lemma = token?.lemma ?? "";
  const entry = dictionaryEntries[lemma];
  const ai = aiContextData[lemma];
  const [localReady, setLocalReady] = useState(false);
  const [aiState, setAiState] = useState<"loading" | "success" | "error">("loading");
  const [saved, setSaved] = useState(false);
  const [mastered, setMastered] = useState(false);

  useEffect(() => {
    if (!open || !lemma) return;
    const resetTimer = setTimeout(() => {
      setLocalReady(false); setAiState("loading"); setSaved(false); setMastered(false);
    }, 0);
    const localTimer = setTimeout(() => setLocalReady(true), 300);
    const aiTimer = setTimeout(() => setAiState("success"), 1450);
    return () => { clearTimeout(resetTimer); clearTimeout(localTimer); clearTimeout(aiTimer); };
  }, [open, lemma]);

  if (!token || !line) return null;
  const retry = () => { setAiState("loading"); setTimeout(() => setAiState("success"), 1200); };
  const renderSentence = () => {
    const parts = line.textEn.split(new RegExp(`(${token.surface})`, "i"));
    return parts.map((part, i) => part.toLowerCase() === token.surface.toLowerCase()
      ? <mark key={i} className="rounded-md bg-[color:var(--highlight)] px-1.5 py-0.5 font-bold text-[color:var(--primary-deep)]">{part}</mark>
      : <span key={i}>{part}</span>);
  };

  return <AnimatePresence>{open && <>
    <motion.div className="absolute inset-0 z-40 bg-black/45 backdrop-blur-[2px]" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose} />
    <motion.section className="absolute inset-x-0 bottom-0 z-50 max-h-[88%] overflow-y-auto rounded-t-[28px] bg-[color:var(--card)] shadow-[0_-8px_32px_rgba(45,59,54,.14)] le-no-scrollbar"
      initial={{ y: "100%" }} animate={{ y: 0 }} exit={{ y: "100%" }} transition={{ type: "spring", damping: 28, stiffness: 320 }}>
      <div className="flex justify-center pb-1 pt-2.5"><div className="h-1.5 w-10 rounded-full bg-[color:var(--border)]" /></div>
      <header className="flex items-start justify-between px-5 pb-3">
        <div><h2 className="text-[27px] font-extrabold capitalize">{token.surface}</h2>{entry && <p className="mt-0.5 text-[15px] text-[color:var(--muted-foreground)]"><span className="font-mono">{entry.phonetic}</span><span className="mx-2">·</span>{entry.pos}</p>}</div>
        <div className="flex gap-1"><Button size="icon" variant="ghost" aria-label="播放单词发音" className="size-12 rounded-full text-[color:var(--primary-deep)]"><Volume2 className="size-5" /></Button><Button size="icon" variant="ghost" onClick={onClose} aria-label="关闭词典" className="size-12 rounded-full"><X className="size-5" /></Button></div>
      </header>

      <div className="space-y-3 px-5 pb-4">
        {!localReady ? <div className="h-[76px] animate-pulse rounded-2xl bg-[color:var(--surface-soft)] p-4"><div className="h-4 w-20 rounded bg-[color:var(--border)]" /><div className="mt-3 h-5 w-40 rounded bg-[color:var(--border)]" /></div>
        : <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="rounded-2xl border border-[color:var(--mint-border)] bg-[color:var(--mint-soft)] p-4">
            <div className="flex items-center justify-between"><span className="text-xs font-bold uppercase tracking-wider text-[color:var(--primary-deep)]">本地词典 · 极速释义</span>{entry && <span className="rounded-full bg-white/75 px-2 py-1 text-[11px] font-semibold text-[color:var(--primary-deep)]">估算 {entry.cefrLevel}</span>}</div>
            <p className="mt-2 text-[19px] font-bold">{entry?.meaningZh ?? "本地词典暂无释义"}</p>
          </motion.div>}

        <div className="rounded-2xl border border-[color:var(--border)] bg-white p-4">
          <div className="mb-2 flex items-center gap-2 text-xs font-bold text-[color:var(--muted-foreground)]"><Clock className="size-4" /><span>{formatTimestamp(line.startMs)}</span><span>·</span><span>原句语境</span></div>
          <p className="text-[17px] font-semibold leading-7">{renderSentence()}</p><p className="mt-1 text-sm text-[color:var(--muted-foreground)]">{line.textZh}</p>
          <Button variant="ghost" onClick={() => { onJumpToTimestamp(line); onClose(); }} className="mt-2 h-12 w-full rounded-xl text-[color:var(--primary-deep)] hover:bg-[color:var(--mint-soft)]"><RefreshCw className="mr-2 size-4" />回到原句播放</Button>
        </div>

        <div className="rounded-2xl border border-[color:var(--blue-border)] bg-[color:var(--blue-soft)] p-4">
          <div className="mb-2 flex items-center gap-2 text-sm font-bold text-[color:var(--blue-deep)]"><Sparkles className="size-4" />AI 语境小老师</div>
          {aiState === "loading" && <div className="flex min-h-[76px] items-center gap-3 text-sm text-[color:var(--muted-foreground)]"><Loader2 className="size-5 animate-spin text-[color:var(--accent)]" /><span>正在想一个简单的解释…</span></div>}
          {aiState === "error" && <div className="space-y-2 text-sm"><p className="flex items-center gap-2"><AlertCircle className="size-4" />这次解释没有生成成功，基础释义仍可用。</p><Button variant="outline" onClick={retry} className="h-11 rounded-xl"><RefreshCw className="mr-2 size-4" />再试一次</Button></div>}
          {aiState === "success" && <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="space-y-2"><p className="text-[15px] leading-6">{ai?.whyHere ?? `这里的 ${lemma} 用的是它在这句话里最常见的意思。`}</p><div className="rounded-xl bg-white/80 p-3"><p className="font-semibold text-[15px]">{ai?.exampleEn ?? `This is a simple ${lemma}.`}</p><p className="mt-1 text-sm text-[color:var(--muted-foreground)]">{ai?.exampleZh ?? "这是一个简单的例句。"}</p></div></motion.div>}
        </div>
      </div>

      <footer className="sticky bottom-0 grid grid-cols-[1fr_2fr] gap-2 border-t border-[color:var(--border)] bg-white/95 px-5 py-3 backdrop-blur">
        <Button variant="outline" onClick={() => setMastered(true)} className={`h-12 rounded-2xl ${mastered ? "border-[color:var(--primary)] bg-[color:var(--mint-soft)] text-[color:var(--primary-deep)]" : ""}`}>{mastered ? <Check className="mr-1.5 size-4" /> : null}{mastered ? "已掌握" : "我已会"}</Button>
        <Button onClick={() => { setSaved(true); onAddToVocab(token, line); }} className="h-12 rounded-2xl bg-[color:var(--primary)] font-bold text-white shadow-[0_4px_14px_rgba(52,211,153,.3)] hover:bg-[color:var(--primary-deep)]">{saved ? <Check className="mr-2 size-5" /> : <BookPlus className="mr-2 size-5" />}{saved ? "已加入生词本" : "加入生词本"}</Button>
      </footer>
    </motion.section>
  </>}</AnimatePresence>;
}
