import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Search, SlidersHorizontal, Captions, LockKeyhole, Play } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card } from "@/components/ui/card";
import { videos } from "@/data/mock";
import { VideoArtwork } from "@/components/lazyeng/VideoArtwork";
import { FadeIn, Stagger, HoverLift, motion, fadeUp } from "@/components/MotionPrimitives";

const filters = ["全部", "A1", "A2", "动物", "自然"];
export default function LibraryPage() {
  const [filter, setFilter] = useState("全部"); const navigate = useNavigate();
  const shown = videos.filter(v => filter === "全部" || v.cefrLevel === filter || v.topicTags.includes(filter));
  return <div className="min-h-full bg-[color:var(--background)] pb-5">
    <header className="px-5 pb-3 pt-3"><FadeIn><div className="flex items-center justify-between"><div><p className="text-xs font-bold text-[color:var(--primary-deep)]">本地视频</p><h1 className="text-[24px] font-extrabold">视频小天地</h1></div><Button size="icon" variant="outline" className="size-12 rounded-2xl border-[color:var(--border)] bg-white"><SlidersHorizontal className="size-5" /></Button></div><div className="relative mt-4"><Search className="absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[color:var(--muted-foreground)]" /><Input placeholder="找一找喜欢的故事" className="h-12 rounded-2xl border-0 bg-white pl-11 shadow-[0_2px_10px_rgba(45,59,54,.05)]" /></div></FadeIn></header>
    <main className="px-5"><div className="le-no-scrollbar mb-4 flex gap-2 overflow-x-auto py-1">{filters.map(f => <Button key={f} variant="ghost" onClick={() => setFilter(f)} className={`h-12 shrink-0 rounded-full px-5 ${filter === f ? "bg-[color:var(--primary)] text-white hover:bg-[color:var(--primary-deep)]" : "border border-[color:var(--border)] bg-white"}`}>{f}</Button>)}</div>
      <Stagger className="space-y-3" stagger={0.07}>{shown.map(video => <motion.div key={video.id} variants={fadeUp}><HoverLift lift={-2}><Card onClick={() => video.subtitleReady && navigate(`/player/${video.id}`)} className={`flex cursor-pointer overflow-hidden rounded-[22px] border-0 p-0 shadow-[0_2px_12px_rgba(45,59,54,.06)] ${!video.subtitleReady ? "opacity-70" : ""}`}><div className="w-[142px] shrink-0"><VideoArtwork video={video} compact /></div><div className="flex min-w-0 flex-1 flex-col justify-between p-3"><div><h2 className="truncate text-[14px] font-extrabold">{video.title}</h2><p className="mt-0.5 text-xs text-[color:var(--muted-foreground)]">{video.titleZh}</p></div><div className="flex flex-wrap gap-1">{video.topicTags.slice(0,2).map(t => <span key={t} className="rounded-full bg-[color:var(--surface-soft)] px-2 py-1 text-[10px] text-[color:var(--muted-foreground)]">{t}</span>)}</div><div className="flex items-center justify-between"><span className="text-[10px] font-bold text-[color:var(--primary-deep)]">{video.levelType === "estimated" ? "估算 " : ""}{video.cefrLevel}</span>{video.subtitleReady ? <span className="flex items-center gap-1 text-[10px] font-semibold text-[color:var(--blue-deep)]"><Captions className="size-3" />字幕就绪</span> : <span className="flex items-center gap-1 text-[10px] text-[color:var(--muted-foreground)]"><LockKeyhole className="size-3" />等待字幕</span>}</div></div>{video.subtitleReady && <Play className="mr-3 mt-4 size-4 shrink-0 text-[color:var(--primary)]" />}</Card></HoverLift></motion.div>)}</Stagger>
    </main>
  </div>;
}
