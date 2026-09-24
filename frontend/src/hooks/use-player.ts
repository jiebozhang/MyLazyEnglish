import { useEffect, useMemo, useState } from "react";
import { subtitleLines } from "@/data/mock";

export function usePlayer() {
  const [playing, setPlaying] = useState(false);
  const [positionMs, setPositionMs] = useState(8400);
  const [speed, setSpeed] = useState<0.8 | 1>(1);
  const [repeatLine, setRepeatLine] = useState(false);
  const [bilingual, setBilingual] = useState(true);
  const activeIndex = useMemo(() => {
    const index = subtitleLines.findIndex((l) => positionMs >= l.startMs && positionMs < l.endMs);
    return index >= 0 ? index : 0;
  }, [positionMs]);

  useEffect(() => {
    if (!playing) return;
    const timer = setInterval(() => setPositionMs((pos) => {
      const active = subtitleLines.find((l) => pos >= l.startMs && pos < l.endMs) ?? subtitleLines[0];
      const next = pos + 160 * speed;
      if (repeatLine && next >= active.endMs) return active.startMs;
      return next >= 486000 ? 0 : next;
    }), 160);
    return () => clearInterval(timer);
  }, [playing, speed, repeatLine]);

  return {
    playing, setPlaying, positionMs, setPositionMs, speed,
    toggleSpeed: () => setSpeed((s) => s === 1 ? 0.8 : 1),
    repeatLine, setRepeatLine, bilingual, setBilingual, activeIndex,
  };
}
