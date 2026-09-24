import { Signal, Wifi, BatteryFull } from "lucide-react";
import type { ReactNode } from "react";

/** 小米 15 Ultra 真机外壳：极窄四等边、微曲圆角、顶部打孔、底部手势条 */
export function DeviceFrame({ children }: { children: ReactNode }) {
  return (
    <div className="le-device">
      <div className="le-punch-hole" aria-hidden />
      <div className="le-device-screen">{children}</div>
    </div>
  );
}

/** 状态栏：时间 + 信号/WiFi/电量，顶部安全区 36px */
export function StatusBar() {
  return (
    <div className="le-status-bar" aria-hidden>
      <span className="tabular-nums">9:41</span>
      <div className="flex items-center gap-1.5">
        <Signal className="size-3.5" strokeWidth={2.5} />
        <Wifi className="size-3.5" strokeWidth={2.5} />
        <BatteryFull className="size-4" strokeWidth={2} />
      </div>
    </div>
  );
}

/** 底部手势小白条，安全区 20px */
export function GestureBar() {
  return <div className="le-gesture-bar" aria-hidden />;
}
