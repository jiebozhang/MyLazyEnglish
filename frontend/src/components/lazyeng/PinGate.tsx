import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { ArrowLeft, Delete, LockKeyhole, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useNavigate } from "react-router-dom";

export function PinGate({ onUnlock }: { onUnlock: () => void }) {
  const [pin, setPin] = useState("");
  const [error, setError] = useState(false);
  const navigate = useNavigate();
  const press = (n: string) => {
    if (pin.length >= 4) return;
    const next = pin + n; setPin(next); setError(false);
    if (next.length === 4) setTimeout(() => next === "2580" ? onUnlock() : (setError(true), setPin("")), 220);
  };
  return <div className="flex h-full flex-col bg-[color:var(--background)] px-6 pb-4">
    <div className="flex h-14 items-center"><Button size="icon" variant="ghost" onClick={() => navigate(-1)} className="size-12 rounded-full" aria-label="返回"><ArrowLeft className="size-5" /></Button></div>
    <div className="flex flex-1 flex-col items-center justify-center -mt-10">
      <div className="mb-5 flex size-16 items-center justify-center rounded-3xl bg-[color:var(--blue-soft)] text-[color:var(--blue-deep)]"><LockKeyhole className="size-7" /></div>
      <h1 className="text-[24px] font-extrabold">家长验证</h1><p className="mt-2 text-center text-sm leading-6 text-[color:var(--muted-foreground)]">请输入 4 位家庭 PIN<br />演示密码：2580</p>
      <div className="mt-6 flex h-6 items-center gap-4">{Array.from({ length: 4 }).map((_, i) => <motion.span key={i} animate={{ scale: pin.length === i ? [1, 1.18, 1] : 1 }} className={`size-3.5 rounded-full border-2 ${i < pin.length ? "border-[color:var(--primary)] bg-[color:var(--primary)]" : "border-[color:var(--border-strong)]"}`} />)}</div>
      <AnimatePresence>{error && <motion.p initial={{ opacity: 0, y: -4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} className="mt-3 text-sm font-semibold text-[color:var(--danger)]">PIN 不正确，请再试一次</motion.p>}</AnimatePresence>
      <div className="mt-6 grid w-[276px] grid-cols-3 gap-3">{["1","2","3","4","5","6","7","8","9","","0","delete"].map((key, i) => key === "" ? <div key={i} /> : <Button key={key} variant="outline" onClick={() => key === "delete" ? setPin((p) => p.slice(0,-1)) : press(key)} className="size-[72px] rounded-[24px] border-[color:var(--border)] bg-white text-xl font-bold shadow-[0_2px_10px_rgba(45,59,54,.04)] active:scale-95">{key === "delete" ? <Delete className="size-5" /> : key}</Button>)}</div>
      <p className="mt-6 flex items-center gap-2 text-xs text-[color:var(--muted-foreground)]"><ShieldCheck className="size-4 text-[color:var(--primary)]" />敏感设置会受到家庭 PIN 保护</p>
    </div>
  </div>;
}
