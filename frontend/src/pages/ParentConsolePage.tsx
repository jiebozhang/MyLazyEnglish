import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Users, Film, HardDrive, ShieldCheck, ChevronRight, Clock3, Database, BrainCircuit } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { PinGate } from "@/components/lazyeng/PinGate";
import { AiSettingsForm } from "@/components/lazyeng/AiSettingsForm";
import { FadeIn, Stagger, motion, fadeUp } from "@/components/MotionPrimitives";

export default function ParentConsolePage() {
  const [unlocked, setUnlocked] = useState(false); const [aiEnabled, setAiEnabled] = useState(true); const nav = useNavigate();
  if (!unlocked) return <PinGate onUnlock={() => setUnlocked(true)} />;
  return <div className="min-h-full bg-[color:var(--background)] pb-5">
    <header className="sticky top-0 z-10 flex h-14 items-center justify-between border-b border-[color:var(--border)] bg-[color:var(--background)]/92 px-3 backdrop-blur-xl"><Button size="icon" variant="ghost" onClick={() => nav(-1)} className="size-12 rounded-full"><ArrowLeft className="size-5" /></Button><div className="text-center"><h1 className="text-sm font-extrabold">家长控制台</h1><p className="text-[10px] text-[color:var(--muted-foreground)]">已通过 PIN 安全验证</p></div><div className="flex size-12 items-center justify-center"><ShieldCheck className="size-5 text-[color:var(--primary-deep)]" /></div></header>
    <main className="space-y-5 px-5 pt-4"><FadeIn><div className="rounded-[24px] bg-[color:var(--blue-soft)] p-4"><div className="flex items-center gap-3"><div className="flex size-11 items-center justify-center rounded-2xl bg-white text-[color:var(--blue-deep)]"><BrainCircuit className="size-5" /></div><div className="flex-1"><h2 className="font-bold">AI 语境增强</h2><p className="text-xs text-[color:var(--muted-foreground)]">关闭后基础词典仍可使用</p></div><Switch checked={aiEnabled} onCheckedChange={setAiEnabled} /></div></div></FadeIn>
      <Stagger className="grid grid-cols-3 gap-2" stagger={.07}>{[{i:Film,v:"5",l:"可学视频",c:"var(--blue-soft)",f:"var(--blue-deep)"},{i:Users,v:"2",l:"家庭成员",c:"var(--mint-soft)",f:"var(--primary-deep)"},{i:HardDrive,v:"1.8G",l:"本地占用",c:"#FFF7DF",f:"#B7791F"}].map(x => <motion.div key={x.l} variants={fadeUp}><Card className="rounded-[20px] border-0 p-3 text-center shadow-[0_2px_10px_rgba(45,59,54,.05)]"><div className="mx-auto flex size-9 items-center justify-center rounded-xl" style={{background:x.c,color:x.f}}><x.i className="size-4" /></div><p className="mt-2 text-lg font-black">{x.v}</p><p className="text-[10px] text-[color:var(--muted-foreground)]">{x.l}</p></Card></motion.div>)}</Stagger>
      <FadeIn><div className={!aiEnabled ? "pointer-events-none opacity-50" : ""}><AiSettingsForm /></div></FadeIn>
      <section><h2 className="mb-3 text-[16px] font-extrabold">更多管理</h2><div className="overflow-hidden rounded-[22px] bg-white shadow-[0_2px_12px_rgba(45,59,54,.05)]"><ConsoleRow icon={Film} label="内容与字幕" value="1 个待处理"/><ConsoleRow icon={Users} label="家庭成员" value="2 个档案"/><ConsoleRow icon={Clock3} label="学习关怀" value="V1.1"/><ConsoleRow icon={Database} label="数据与存储" value="1.8 GB" last/></div></section>
    </main>
  </div>;
}
function ConsoleRow({icon:Icon,label,value,last=false}:{icon:typeof Film;label:string;value:string;last?:boolean}) { return <Button variant="ghost" className={`flex h-16 w-full justify-start rounded-none px-4 ${last?"":"border-b border-[color:var(--border)]"}`}><span className="mr-3 flex size-10 items-center justify-center rounded-xl bg-[color:var(--surface-soft)] text-[color:var(--primary-deep)]"><Icon className="size-5" /></span><span className="flex-1 text-left text-sm font-semibold">{label}</span><span className="text-xs font-normal text-[color:var(--muted-foreground)]">{value}</span><ChevronRight className="ml-2 size-4 text-[color:var(--muted-foreground)]" /></Button>; }
