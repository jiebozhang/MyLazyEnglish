import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, MoreHorizontal, Play, Pause, Repeat2, Captions, BookOpen, Maximize, Volume2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Slider } from "@/components/ui/slider";
import { toast } from "sonner";
import { subtitleLines, type SubtitleLine, type WordToken, formatTimestamp } from "@/data/mock";
import { usePlayer } from "@/hooks/use-player";
import { SubtitleView } from "@/components/lazyeng/SubtitleView";
import { ContextDictionarySheet } from "@/components/lazyeng/ContextDictionarySheet";
import { motion } from "@/components/MotionPrimitives";

export default function PlayerPage() {
  const nav = useNavigate(); const player = usePlayer();
  const [selected, setSelected] = useState<{ token: WordToken; line: SubtitleLine } | null>(null);
  const active = subtitleLines[player.activeIndex];
  const visible = useMemo(() => subtitleLines.slice(Math.max(0, player.activeIndex - 1), Math.min(subtitleLines.length, player.activeIndex + 4)), [player.activeIndex]);
  const selectWord = (token: WordToken, line: SubtitleLine) => { if (!token.lemma || /^\s+$/.test(token.surface)) return; player.setPlaying(false); setSelected({ token, line }); };
  return <div className="relative flex h-full flex-col overflow-hidden bg-[color:var(--background)]">
    <header className="flex h-14 shrink-0 items-center justify-between px-3"><Button size="icon" variant="ghost" onClick={() => nav(-1)} className="size-12 rounded-full" aria-label="返回"><ArrowLeft className="size-5" /></Button><div className="min-w-0 text-center"><h1 className="truncate text-sm font-bold">The Fox and the Rabbit</h1><p className="text-[10px] text-[color:var(--muted-foreground)]">狐狸与小兔 · 估算 A2</p></div><Button size="icon" variant="ghost" className="size-12 rounded-full" aria-label="更多"><MoreHorizontal className="size-5" /></Button></header>

    <section className="relative aspect-video shrink-0 overflow-hidden bg-[#172c35] le-video-gradient-1">
      <div className="le-scenery le-scenery-player" aria-hidden><span className="le-sun" /><span className="le-hill le-hill-a" /><span className="le-hill le-hill-b" /><span className="le-tree le-tree-a" /><span className="le-tree le-tree-b" /><span className="le-animal le-animal-fox" /><span className="le-animal le-animal-rabbit" /></div>
      <div className="absolute inset-0 bg-gradient-to-t from-black/65 via-transparent to-black/25" />
      <Button onClick={() => player.setPlaying(!player.playing)} size="icon" className="absolute left-1/2 top-1/2 size-14 -translate-x-1/2 -translate-y-1/2 rounded-full border border-white/50 bg-white/90 text-[color:var(--primary-deep)] shadow-lg hover:bg-white">{player.playing ? <Pause className="size-6 fill-current" /> : <Play className="ml-1 size-6 fill-current" />}</Button>
      <div className="absolute inset-x-3 bottom-2 text-white"><Slider value={[player.positionMs]} max={486000} step={1000} onValueChange={v => player.setPositionMs(v[0])} className="[&_[data-slot=slider-range]]:bg-[color:var(--primary)] [&_[data-slot=slider-thumb]]:size-4 [&_[data-slot=slider-thumb]]:border-white [&_[data-slot=slider-thumb]]:bg-white" /><div className="mt-1 flex justify-between text-[10px] font-semibold"><span>{formatTimestamp(player.positionMs)} / 08:06</span><span className="flex items-center gap-3"><Volume2 className="size-3.5" /><Maximize className="size-3.5" /></span></div></div>
    </section>

    <div className="grid shrink-0 grid-cols-3 gap-2 border-b border-[color:var(--border)] bg-white px-4 py-2"><Button variant="ghost" onClick={player.toggleSpeed} className="h-12 rounded-2xl bg-[color:var(--surface-soft)] font-extrabold text-[color:var(--blue-deep)]">{player.speed.toFixed(1)}x 慢速</Button><Button variant="ghost" onClick={() => player.setRepeatLine(!player.repeatLine)} className={`h-12 rounded-2xl ${player.repeatLine ? "bg-[color:var(--mint-soft)] text-[color:var(--primary-deep)]" : "bg-[color:var(--surface-soft)]"}`}><Repeat2 className="mr-1.5 size-4" />复读</Button><Button variant="ghost" onClick={() => player.setBilingual(!player.bilingual)} className="h-12 rounded-2xl bg-[color:var(--surface-soft)]"><Captions className="mr-1.5 size-4" />{player.bilingual ? "双语" : "英文"}</Button></div>

    <div className="min-h-0 flex-1 overflow-y-auto px-3 py-2 le-no-scrollbar"><div className="mb-2 px-2 text-[11px] font-semibold text-[color:var(--muted-foreground)]">点击当前句中的单词，马上看到语境意思</div><motion.div layout className="space-y-1">{visible.map(line => <SubtitleView key={line.id} line={line} active={line.id === active.id} bilingual={player.bilingual} onWordClick={selectWord} />)}</motion.div></div>
    <footer className="flex h-[54px] shrink-0 items-center justify-between border-t border-[color:var(--border)] bg-white px-4"><span className="text-xs text-[color:var(--muted-foreground)]">第 {active.seq} / {subtitleLines.length} 句</span><Button variant="ghost" onClick={() => nav("/vocabulary")} className="h-11 rounded-xl text-xs font-bold text-[color:var(--primary-deep)]"><BookOpen className="mr-2 size-4" />本集重点词 8</Button></footer>

    <ContextDictionarySheet open={!!selected} token={selected?.token ?? null} line={selected?.line ?? null} onClose={() => setSelected(null)} onJumpToTimestamp={line => { player.setPositionMs(line.startMs); player.setPlaying(true); }} onAddToVocab={(token) => toast.success(`“${token.lemma}” 已加入生词本`, { description: "已保存原句和视频时间点" })} />
  </div>;
}
