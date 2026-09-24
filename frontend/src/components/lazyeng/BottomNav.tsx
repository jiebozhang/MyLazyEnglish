import { NavLink } from "react-router-dom";
import { Home, Clapperboard, BookMarked, UserRound } from "lucide-react";
import { motion } from "framer-motion";

const tabs = [
  { to: "/", label: "首页", icon: Home, key: "Home" },
  { to: "/library", label: "视频库", icon: Clapperboard, key: "Library" },
  { to: "/vocabulary", label: "生词本", icon: BookMarked, key: "Vocab" },
  { to: "/me", label: "我的", icon: UserRound, key: "Me" },
];

/** 底部导航：4 个高频入口，触控热区 ≥48px，激活态薄荷绿 */
export function BottomNav() {
  return (
    <nav className="shrink-0 border-t border-[color:var(--border)] bg-[color:var(--card)]/95 backdrop-blur-md">
      <div className="grid grid-cols-4">
        {tabs.map(({ to, label, icon: Icon, key }) => (
          <NavLink
            key={key}
            to={to}
            end={to === "/"}
            className="relative flex min-h-[56px] flex-col items-center justify-center gap-1 transition-colors"
            style={({ isActive }) => ({
              color: isActive ? "var(--primary)" : "var(--muted-foreground)",
            })}
          >
            {({ isActive }) => (
              <>
                {isActive && (
                  <motion.span
                    layoutId="nav-pill"
                    className="absolute top-1.5 h-1 w-8 rounded-full"
                    style={{ background: "var(--primary)" }}
                    transition={{ type: "spring", damping: 22, stiffness: 320 }}
                  />
                )}
                <Icon className="size-[22px]" strokeWidth={isActive ? 2.5 : 2} />
                <span className="text-[11px] font-medium">{label}</span>
              </>
            )}
          </NavLink>
        ))}
      </div>
    </nav>
  );
}
