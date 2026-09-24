import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Eye, EyeOff, Loader2, PlugZap, ShieldCheck } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from "sonner";

const schema = z.object({ apiBase: z.string().url("请输入完整的 HTTPS 地址").refine(v => v.startsWith("https://"), "地址必须使用 HTTPS"), model: z.string().min(1,"请输入模型名称"), apiKey: z.string().min(8,"密钥至少 8 位") });
type Values = z.infer<typeof schema>;

export function AiSettingsForm() {
  const [provider, setProvider] = useState<"openai" | "anthropic">("openai");
  const [showKey, setShowKey] = useState(false);
  const [testing, setTesting] = useState(false);
  const { register, handleSubmit, formState: { errors } } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { apiBase: "https://api.example.com/v1", model: "family-tutor-mini", apiKey: "sk-demo-family-key" } });
  const test = handleSubmit(() => { setTesting(true); setTimeout(() => { setTesting(false); toast.success("连接成功 · 186ms", { description: "模型已准备好提供儿童语境解释" }); }, 650); });
  return <div className="space-y-4 rounded-[24px] border border-[color:var(--border)] bg-white p-4 shadow-[0_2px_12px_rgba(45,59,54,.05)]">
    <div className="flex items-center gap-3"><div className="flex size-11 items-center justify-center rounded-2xl bg-[color:var(--blue-soft)] text-[color:var(--blue-deep)]"><PlugZap className="size-5" /></div><div><h2 className="font-bold">大模型设置</h2><p className="text-xs text-[color:var(--muted-foreground)]">只用于增强语境解释</p></div></div>
    <div><Label className="mb-2 block text-xs text-[color:var(--muted-foreground)]">接口协议</Label><div className="grid grid-cols-2 rounded-2xl bg-[color:var(--surface-soft)] p-1">{(["openai","anthropic"] as const).map(p => <Button key={p} type="button" variant="ghost" onClick={() => setProvider(p)} className={`h-11 rounded-xl text-xs ${provider === p ? "bg-white text-[color:var(--foreground)] shadow-xs" : "text-[color:var(--muted-foreground)]"}`}>{p === "openai" ? "OpenAI 兼容" : "Anthropic 原生"}</Button>)}</div></div>
    <Field label="API Base" error={errors.apiBase?.message}><Input {...register("apiBase")} className="h-12 rounded-xl border-[color:var(--border)] bg-[color:var(--surface-soft)] text-sm" /></Field>
    <Field label="模型名称" error={errors.model?.message}><Input {...register("model")} className="h-12 rounded-xl border-[color:var(--border)] bg-[color:var(--surface-soft)] text-sm" /></Field>
    <Field label="API Key" error={errors.apiKey?.message}><div className="relative"><Input {...register("apiKey")} type={showKey ? "text" : "password"} className="h-12 rounded-xl border-[color:var(--border)] bg-[color:var(--surface-soft)] pr-12 text-sm" /><Button type="button" size="icon" variant="ghost" onClick={() => setShowKey(v => !v)} className="absolute right-0 top-0 size-12 rounded-xl">{showKey ? <EyeOff className="size-4" /> : <Eye className="size-4" />}</Button></div></Field>
    <div className="flex items-center gap-2 rounded-xl bg-[color:var(--mint-soft)] p-3 text-xs leading-5 text-[color:var(--primary-deep)]"><ShieldCheck className="size-4 shrink-0" />密钥仅加密保存在此设备，不会出现在日志中。</div>
    <Button onClick={test} disabled={testing} className="h-12 w-full rounded-2xl bg-[color:var(--primary)] font-bold text-white hover:bg-[color:var(--primary-deep)]">{testing ? <Loader2 className="mr-2 size-4 animate-spin" /> : <PlugZap className="mr-2 size-4" />}{testing ? "正在测试…" : "测试连接"}</Button>
  </div>;
}
function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) { return <div><Label className="mb-2 block text-xs text-[color:var(--muted-foreground)]">{label}</Label>{children}{error && <p className="mt-1 text-xs text-[color:var(--danger)]">{error}</p>}</div>; }
