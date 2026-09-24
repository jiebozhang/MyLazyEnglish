import { useNavigate } from "react-router-dom";
import { ChevronDown, ShieldCheck, Play, Clock3, Search, BookOpenCheck, Sparkles, Captions, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Progress } from "@/components/ui/progress";
import { Card } from "@/components/ui/card";
import { currentProfile, continueVideo, videos } from "@/data/mock";
import { VideoArtwork } from "@/components/lazyeng/VideoArtwork";
import { FadeIn, HoverLift, Stagger, fadeUp, motion } from "@/components/MotionPrimitives";

const achievements = [
  { value: "12", unit: "分钟", label: "今天观看", icon: Clock3, color: "var(--blue-deep)", bg: "var(--blue-soft)" },
  { value: "7", unit: "个", label: "查过新词", icon: Search, color: "#B7791F", bg: "#FFF7DF" },
  { value: "3", unit: "个", label: "等你复习", icon: BookOpenCheck, color: "var(--primary-deep)", bg: "var(--mint-soft)" },
];

export default function HomePage() {
  const navigate = useNavigate();
  return <div className="min-h-full bg-[color:var(--background)] pb-5">
    <header className="px-5 pb-3 pt-2">
      <FadeIn variants={fadeUp} className="flex items-center justify-between">
        <Button variant="ghost" className="h-12 gap-2 rounded-2xl px-2 hover:bg-[color:var(--mint-soft)]">
          <span className="flex size-10 items-center justify-center rounded-2xl text-sm font-extrabold text-white shadow-[0_3px_10px_rgba(52,211,153,.25)]" style={{ background: currentProfile.avatarGradient }}>{currentProfile.initial}</span>
          <span className="text-left"><span className="block text-[11px] font-medium text-[color:var(--muted-foreground)]">早上好</span><span className="block text-[15px] font-bold">{currentProfile.nickname}</span></span><ChevronDown className="size-4 text-[color:var(--muted-foreground)]" />
        </Button>
        <div className="flex h-11 items-center gap-2 rounded-2xl border border-[color:var(--mint-border)] bg-[color:var(--mint-soft)] px-3 text-[color:var(--primary-deep)]"><ShieldCheck className="size-4" /><span className="text-xs font-bold">今日 12 / 40 分钟</span></div>
      </FadeIn>
    </header>

    <main className="space-y-5 px-5">
      <FadeIn>
        <div className="mb-3 flex items-end justify-between"><div><p className="text-xs font-bold tracking-wide text-[color:var(--primary-deep)]">继续你的故事</p><h1 className="mt-0.5 text-[24px] font-extrabold leading-tight">再看一小段吧</h1></div><Sparkles className="size-5 text-[color:var(--warning)]" /></div>
        <Card className="overflow-hidden rounded-[24px] border-0 p-0 shadow-[0_6px_24px_rgba(45,59,54,.10)]">
          <VideoArtwork video={continueVideo} onPlay={() => navigate("/player/v1")} />
          <div className="p-4"><div className="flex items-start justify-between gap-3"><div><h2 className="text-[17px] font-extrabold">{continueVideo.title}</h2><p className="mt-0.5 text-xs text-[color:var(--muted-foreground)]">{continueVideo.titleZh} · 估算 {continueVideo.cefrLevel}</p></div><span className="shrink-0 rounded-full bg-[color:var(--blue-soft)] px-2.5 py-1 text-[11px] font-bold text-[color:var(--blue-deep)]">Power Up 2</span></div>
            <div className="mt-3"><div className="mb-1.5 flex justify-between text-[11px] text-[color:var(--muted-foreground)]"><span>已看 62%</span><span>{continueVideo.remainingLabel}</span></div><Progress value={62} className="h-2 bg-[color:var(--surface-soft)] [&>div]:bg-[color:var(--primary)]" /></div>
            <Button onClick={() => navigate("/player/v1")} className="mt-4 h-12 w-full rounded-2xl bg-[color:var(--primary)] text-[15px] font-bold text-white shadow-[0_4px_14px_rgba(52,211,153,.28)] hover:bg-[color:var(--primary-deep)]"><Play className="mr-2 size-5 fill-current" />继续播放</Button>
          </div>
        </Card>
      </FadeIn>

      <section><FadeIn className="mb-3 flex items-center justify-between"><h2 className="text-[18px] font-extrabold">今天的小收获</h2><span className="text-xs font-semibold text-[color:var(--muted-foreground)]">做得不错</span></FadeIn>
        <Stagger className="grid grid-cols-3 gap-2" stagger={0.07}>{achievements.map(item => <motion.div key={item.label} variants={fadeUp}><HoverLift lift={-2}><Card className="rounded-[20px] border-0 p-3 shadow-[0_2px_12px_rgba(45,59,54,.05)]"><div className="mb-2 flex size-9 items-center justify-center rounded-xl" style={{ background: item.bg, color: item.color }}><item.icon className="size-4" /></div><p><span className="text-[23px] font-extrabold">{item.value}</span><span className="ml-0.5 text-[10px] text-[color:var(--muted-foreground)]">{item.unit}</span></p><p className="mt-0.5 text-[11px] font-medium text-[color:var(--muted-foreground)]">{item.label}</p></Card></HoverLift></motion.div>)}</Stagger>
      </section>

      <section><FadeIn className="mb-3 flex items-center justify-between"><h2 className="text-[18px] font-extrabold">为你精选</h2><Button variant="ghost" onClick={() => navigate("/library")} className="h-10 rounded-xl px-2 text-xs text-[color:var(--primary-deep)]">全部 <ArrowRight className="ml-1 size-3.5" /></Button></FadeIn>
        <Stagger className="grid grid-cols-2 gap-3" stagger={0.08}>{videos.slice(1,5).map(video => <motion.div key={video.id} variants={fadeUp}><HoverLift><Card onClick={() => video.subtitleReady && navigate(`/player/${video.id}`)} className="cursor-pointer overflow-hidden rounded-[20px] border-0 p-0 shadow-[0_2px_12px_rgba(45,59,54,.06)]"><VideoArtwork video={video} compact /><div className="p-3"><h3 className="truncate text-[13px] font-bold">{video.title}</h3><div className="mt-2 flex items-center justify-between"><span className="rounded-full bg-[color:var(--mint-soft)] px-2 py-1 text-[10px] font-bold text-[color:var(--primary-deep)]">{video.levelType === "estimated" ? "估算 " : ""}{video.cefrLevel}</span><span className={`flex items-center gap-1 text-[10px] ${video.subtitleReady ? "text-[color:var(--blue-deep)]" : "text-[color:var(--muted-foreground)]"}`}><Captions className="size-3" />{video.subtitleReady ? "就绪" : "准备中"}</span></div></div></Card></HoverLift></motion.div>)}</Stagger>
      </section>
    </main>
  </div>;
}
