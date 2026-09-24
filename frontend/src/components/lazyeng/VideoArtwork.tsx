import { Play, Captions, Clock3 } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { VideoItem } from "@/data/mock";

export function VideoArtwork({ video, compact = false, onPlay }: { video: VideoItem; compact?: boolean; onPlay?: () => void }) {
  return (
    <div className={`relative overflow-hidden ${compact ? "h-[116px]" : "aspect-video"} ${video.coverClass}`}>
      <div className="le-scenery" aria-hidden>
        <span className="le-sun" /><span className="le-hill le-hill-a" /><span className="le-hill le-hill-b" />
        <span className="le-tree le-tree-a" /><span className="le-tree le-tree-b" />
        <span className="le-animal le-animal-fox" /><span className="le-animal le-animal-rabbit" />
      </div>
      <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-black/40 to-transparent" />
      {onPlay && (
        <Button aria-label={`播放 ${video.title}`} size="icon" onClick={onPlay}
          className="absolute left-1/2 top-1/2 size-12 -translate-x-1/2 -translate-y-1/2 rounded-full border border-white/60 bg-white/90 text-[color:var(--primary-deep)] shadow-lg backdrop-blur hover:bg-white">
          <Play className="ml-0.5 size-5 fill-current" />
        </Button>
      )}
      <div className="absolute inset-x-3 bottom-2.5 flex items-center justify-between text-[11px] font-semibold text-white">
        <span className="flex items-center gap-1"><Clock3 className="size-3" />{video.durationLabel}</span>
        {video.subtitleReady && <span className="flex items-center gap-1 rounded-full bg-black/30 px-2 py-1 backdrop-blur"><Captions className="size-3" />英文字幕</span>}
      </div>
    </div>
  );
}
