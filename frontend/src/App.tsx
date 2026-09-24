import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Route, useLocation } from "react-router-dom";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { AnimatedRoutes } from "@/components/AnimatedRoutes";
import { PageTransition } from "@/components/PageTransition";
import { DeviceFrame, StatusBar, GestureBar } from "@/components/lazyeng/DeviceFrame";
import { BottomNav } from "@/components/lazyeng/BottomNav";
import HomePage from "@/pages/HomePage";
import LibraryPage from "@/pages/LibraryPage";
import PlayerPage from "@/pages/PlayerPage";
import VocabularyPage from "@/pages/VocabularyPage";
import MePage from "@/pages/MePage";
import ParentConsolePage from "@/pages/ParentConsolePage";
import NotFound from "@/pages/NotFound";

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 60_000, retry: 1, refetchOnWindowFocus: false } } });

function AppShell() {
  const { pathname } = useLocation();
  const immersive = pathname.startsWith("/player") || pathname.startsWith("/parent");
  return <DeviceFrame>
    <StatusBar />
    <main className="le-main">
      <AnimatedRoutes>
        <Route path="/" data-genie-key="Home" data-genie-title="孩子端首页" element={<PageTransition transition="slide-up"><HomePage /></PageTransition>} />
        <Route path="/library" data-genie-key="VideoLibrary" data-genie-title="视频库" element={<PageTransition transition="slide-fade"><LibraryPage /></PageTransition>} />
        <Route path="/player/:videoId" data-genie-key="Player" data-genie-title="沉浸式学习播放器" element={<PageTransition transition="fade"><PlayerPage /></PageTransition>} />
        <Route path="/vocabulary" data-genie-key="VocabularyReview" data-genie-title="生词本与原句复习" element={<PageTransition transition="slide-fade"><VocabularyPage /></PageTransition>} />
        <Route path="/me" data-genie-key="ProfileProgress" data-genie-title="我的学习进度" element={<PageTransition transition="slide-fade"><MePage /></PageTransition>} />
        <Route path="/parent" data-genie-key="ParentConsole" data-genie-title="家长控制台" element={<PageTransition transition="scale"><ParentConsolePage /></PageTransition>} />
        <Route path="*" data-genie-key="NotFound" data-genie-title="页面未找到" element={<PageTransition><NotFound /></PageTransition>} />
      </AnimatedRoutes>
    </main>
    {!immersive && <BottomNav />}
    <GestureBar />
  </DeviceFrame>;
}

export default function App() {
  return <QueryClientProvider client={queryClient}><TooltipProvider><Toaster position="top-center" richColors /><BrowserRouter><AppShell /></BrowserRouter></TooltipProvider></QueryClientProvider>;
}
