// LazyEng 家庭版 · Mock 数据与实体类型
// 基于 PRD v2.2 数据模型，面向原型演示

/* ===== 类型定义 ===== */

export type Role = "child" | "parent";
export type AgeMode = "child" | "adult";
export type EnglishLevel = "Power Up Starter" | "Power Up 2" | "Pre A1" | "A1" | "A2";
export type VocabStatus = "learning" | "review" | "mastered";
export type ImportStatus = "ready" | "processing" | "needs_subtitle";
export type SubtitleMode = "en" | "bilingual";

export interface Profile {
  id: string;
  nickname: string;
  initial: string;
  avatarGradient: string;
  role: Role;
  ageMode: AgeMode;
  englishLevel: EnglishLevel;
  todayMinutes: number;
  todayLookups: number;
  todayReview: number;
  weeklyMinutes: number;
}

export interface WordToken {
  surface: string;
  lemma: string;
  lookupTarget: boolean;
}

export interface SubtitleLine {
  id: string;
  seq: number;
  startMs: number;
  endMs: number;
  textEn: string;
  textZh: string;
  words: WordToken[];
}

export interface VideoItem {
  id: string;
  title: string;
  titleZh: string;
  coverClass: string;
  durationMs: number;
  durationLabel: string;
  cefrLevel: string;
  levelType: "estimated" | "manual";
  topicTags: string[];
  subtitleReady: boolean;
  importStatus: ImportStatus;
  progressPct: number;
  remainingLabel: string;
  accentTag: string;
  ageFit: "child" | "all";
}

export interface VocabularyItem {
  id: string;
  lemma: string;
  surface: string;
  phonetic: string;
  pos: string;
  meaningZh: string;
  status: VocabStatus;
  videoId: string;
  videoTitle: string;
  sentenceEn: string;
  sentenceZh: string;
  timestampMs: number;
  timestampLabel: string;
  aiWhyHere: string;
  aiExampleEn: string;
  aiExampleZh: string;
  nextReviewLabel: string;
}

export interface DictionaryEntry {
  lemma: string;
  phonetic: string;
  pos: string;
  meaningZh: string;
  cefrLevel: string;
}

/* ===== Mock 数据 ===== */

export const profiles: Profile[] = [
  {
    id: "p1",
    nickname: "小宇",
    initial: "宇",
    avatarGradient: "linear-gradient(135deg, #34d399 0%, #10b981 100%)",
    role: "child",
    ageMode: "child",
    englishLevel: "Power Up 2",
    todayMinutes: 12,
    todayLookups: 7,
    todayReview: 3,
    weeklyMinutes: 42,
  },
  {
    id: "p2",
    nickname: "爸爸",
    initial: "爸",
    avatarGradient: "linear-gradient(135deg, #38bdf8 0%, #0ea5e9 100%)",
    role: "parent",
    ageMode: "adult",
    englishLevel: "A2",
    todayMinutes: 0,
    todayLookups: 0,
    todayReview: 0,
    weeklyMinutes: 18,
  },
];

export const currentProfile = profiles[0];

export const videos: VideoItem[] = [
  {
    id: "v1",
    title: "The Fox and the Rabbit",
    titleZh: "狐狸与小兔",
    coverClass: "le-video-gradient-1",
    durationMs: 486000,
    durationLabel: "8:06",
    cefrLevel: "A2",
    levelType: "estimated",
    topicTags: ["动物", "友谊", "冒险"],
    subtitleReady: true,
    importStatus: "ready",
    progressPct: 62,
    remainingLabel: "还剩 3:05",
    accentTag: "美音",
    ageFit: "child",
  },
  {
    id: "v2",
    title: "A Day at the Farm",
    titleZh: "农场的一天",
    coverClass: "le-video-gradient-2",
    durationMs: 372000,
    durationLabel: "6:12",
    cefrLevel: "A1",
    levelType: "manual",
    topicTags: ["生活", "自然"],
    subtitleReady: true,
    importStatus: "ready",
    progressPct: 0,
    remainingLabel: "6:12",
    accentTag: "英音",
    ageFit: "child",
  },
  {
    id: "v3",
    title: "Under the Sea",
    titleZh: "海底世界",
    coverClass: "le-video-gradient-3",
    durationMs: 540000,
    durationLabel: "9:00",
    cefrLevel: "A2",
    levelType: "estimated",
    topicTags: ["海洋", "动物"],
    subtitleReady: true,
    importStatus: "ready",
    progressPct: 100,
    remainingLabel: "已看完",
    accentTag: "美音",
    ageFit: "child",
  },
  {
    id: "v4",
    title: "Let's Build a Birdhouse",
    titleZh: "一起做鸟屋",
    coverClass: "le-video-gradient-4",
    durationMs: 420000,
    durationLabel: "7:00",
    cefrLevel: "A1",
    levelType: "manual",
    topicTags: ["手工", "自然"],
    subtitleReady: true,
    importStatus: "ready",
    progressPct: 0,
    remainingLabel: "7:00",
    accentTag: "美音",
    ageFit: "child",
  },
  {
    id: "v5",
    title: "The Little Garden",
    titleZh: "小花园",
    coverClass: "le-video-gradient-5",
    durationMs: 360000,
    durationLabel: "6:00",
    cefrLevel: "A2",
    levelType: "estimated",
    topicTags: ["自然", "植物"],
    subtitleReady: false,
    importStatus: "needs_subtitle",
    progressPct: 0,
    remainingLabel: "准备中",
    accentTag: "英音",
    ageFit: "child",
  },
  {
    id: "v6",
    title: "Rainy Day Fun",
    titleZh: "下雨天",
    coverClass: "le-video-gradient-6",
    durationMs: 300000,
    durationLabel: "5:00",
    cefrLevel: "Pre A1",
    levelType: "manual",
    topicTags: ["生活", "天气"],
    subtitleReady: true,
    importStatus: "ready",
    progressPct: 0,
    remainingLabel: "5:00",
    accentTag: "美音",
    ageFit: "child",
  },
];

export const continueVideo = videos[0];

// 字幕逐句序列（狐狸与小兔）—— 每句已拆词，lookup 标记新词
function splitWords(text: string, targets: string[] = []): WordToken[] {
  return text.split(/(\s+)/).map((seg) => {
    if (/^\s+$/.test(seg)) return { surface: seg, lemma: seg, lookupTarget: false };
    const clean = seg.replace(/[^A-Za-z'-]/g, "").toLowerCase();
    return {
      surface: seg,
      lemma: clean,
      lookupTarget: targets.map((t) => t.toLowerCase()).includes(clean),
    };
  });
}

export const subtitleLines: SubtitleLine[] = [
  {
    id: "s1", seq: 1, startMs: 0, endMs: 4200,
    textEn: "The little fox walks through the green forest.",
    textZh: "小狐狸穿过翠绿的森林。",
    words: splitWords("The little fox walks through the green forest.", ["forest"]),
  },
  {
    id: "s2", seq: 2, startMs: 4200, endMs: 8400,
    textEn: "She is looking for her friends today.",
    textZh: "她今天正在寻找她的朋友们。",
    words: splitWords("She is looking for her friends today."),
  },
  {
    id: "s3", seq: 3, startMs: 8400, endMs: 13200,
    textEn: "Look! A rabbit is hopping over the hill.",
    textZh: "看！一只小兔正跳过小山丘。",
    words: splitWords("Look! A rabbit is hopping over the hill.", ["hopping", "hill"]),
  },
  {
    id: "s4", seq: 4, startMs: 13200, endMs: 18000,
    textEn: "Come here, little rabbit! says the fox.",
    textZh: "过来，小兔子！狐狸说。",
    words: splitWords("Come here, little rabbit! says the fox."),
  },
  {
    id: "s5", seq: 5, startMs: 18000, endMs: 22800,
    textEn: "The rabbit smiles and waves her paw.",
    textZh: "小兔微笑着挥挥爪子。",
    words: splitWords("The rabbit smiles and waves her paw.", ["waves", "paw"]),
  },
  {
    id: "s6", seq: 6, startMs: 22800, endMs: 28000,
    textEn: "Let's play together by the river, the fox says.",
    textZh: "我们一起到河边玩吧，狐狸说。",
    words: splitWords("Let's play together by the river, the fox says.", ["river"]),
  },
  {
    id: "s7", seq: 7, startMs: 28000, endMs: 32400,
    textEn: "They run fast across the soft grass.",
    textZh: "它们飞快地跑过柔软的草地。",
    words: splitWords("They run fast across the soft grass.", ["grass"]),
  },
  {
    id: "s8", seq: 8, startMs: 32400, endMs: 37200,
    textEn: "The sun shines warm on their fur.",
    textZh: "阳光温暖地照在它们的毛皮上。",
    words: splitWords("The sun shines warm on their fur.", ["fur"]),
  },
  {
    id: "s9", seq: 9, startMs: 37200, endMs: 42000,
    textEn: "What a wonderful day for new friends!",
    textZh: "交到新朋友，多美好的一天啊！",
    words: splitWords("What a wonderful day for new friends!", ["wonderful"]),
  },
  {
    id: "s10", seq: 10, startMs: 42000, endMs: 48600,
    textEn: "The fox and the rabbit play until the evening.",
    textZh: "狐狸和小兔一直玩到傍晚。",
    words: splitWords("The fox and the rabbit play until the evening.", ["evening"]),
  },
];

export const dictionaryEntries: Record<string, DictionaryEntry> = {
  forest: { lemma: "forest", phonetic: "/ˈfɒrɪst/", pos: "n.", meaningZh: "森林，大片树木", cefrLevel: "A2" },
  hopping: { lemma: "hop", phonetic: "/hɒp/", pos: "v.", meaningZh: "单脚跳，蹦跳", cefrLevel: "A2" },
  hill: { lemma: "hill", phonetic: "/hɪl/", pos: "n.", meaningZh: "小山，山丘", cefrLevel: "A1" },
  waves: { lemma: "wave", phonetic: "/weɪv/", pos: "v.", meaningZh: "挥手，摆动", cefrLevel: "A2" },
  paw: { lemma: "paw", phonetic: "/pɔː/", pos: "n.", meaningZh: "爪子（动物的脚）", cefrLevel: "A2" },
  river: { lemma: "river", phonetic: "/ˈrɪvə/", pos: "n.", meaningZh: "河流，江河", cefrLevel: "A1" },
  grass: { lemma: "grass", phonetic: "/ɡrɑːs/", pos: "n.", meaningZh: "草，青草", cefrLevel: "A1" },
  fur: { lemma: "fur", phonetic: "/fɜː/", pos: "n.", meaningZh: "毛皮，软毛", cefrLevel: "A2" },
  wonderful: { lemma: "wonderful", phonetic: "/ˈwʌndəfəl/", pos: "adj.", meaningZh: "极好的，奇妙的", cefrLevel: "A2" },
  evening: { lemma: "evening", phonetic: "/ˈiːvnɪŋ/", pos: "n.", meaningZh: "傍晚，晚上", cefrLevel: "A1" },
};

// AI 语境解释（模拟）
export const aiContextData: Record<string, { whyHere: string; exampleEn: string; exampleZh: string }> = {
  forest: {
    whyHere: "这里是说狐狸住的地方，很多树长在一起就是 forest，比 woods 更大更密。",
    exampleEn: "An owl lives in the dark forest.",
    exampleZh: "一只猫头鹰住在黑黑的森林里。",
  },
  hopping: { whyHere: "兔子用两只后腿一起蹦着走，这个动作叫 hop，比 walk 更活泼。", exampleEn: "The frog is hopping on the leaf.", exampleZh: "青蛙在叶子上蹦跳。" },
  hill: { whyHere: "小兔子蹦过的小山包叫 hill，比 mountain 矮，孩子也能爬上去。", exampleEn: "We fly a kite on the hill.", exampleZh: "我们在小山上放风筝。" },
  waves: { whyHere: "这里 wave 是挥手打招呼，不是海浪；小兔举起爪子摇晃就是 waves。", exampleEn: "She waves goodbye to her dad.", exampleZh: "她向爸爸挥手告别。" },
  paw: { whyHere: "动物的脚叫 paw，有肉垫和毛，和人的手 hand 不一样。", exampleEn: "The cat put its paw on my hand.", exampleZh: "猫把爪子放在我手上。" },
  river: { whyHere: "河边是它们玩的地方，river 是流动的水，比 stream（小溪）大。", exampleEn: "Fish swim in the clear river.", exampleZh: "鱼在清澈的河里游。" },
  grass: { whyHere: "它们跑过的软软的青草，grass 是不可数名词，泛指草地。", exampleEn: "The sheep eats green grass.", exampleZh: "羊吃着绿油油的草。" },
  fur: { whyHere: "狐狸身上的毛皮叫 fur，保暖又柔软，阳光照上去亮亮的。", exampleEn: "The bear has thick brown fur.", exampleZh: "熊有厚厚的棕色毛皮。" },
  wonderful: { whyHere: "形容这一天太棒了，wonderful 比 good 更惊喜，像有魔法一样。", exampleEn: "We had a wonderful trip to the zoo.", exampleZh: "我们去动物园的旅行棒极了。" },
  evening: { whyHere: "太阳快下山的时候是 evening，比 night 早一点，天还亮着。", exampleEn: "We watch stars in the evening.", exampleZh: "我们在傍晚看星星。" },
};

export const vocabularyItems: VocabularyItem[] = [
  {
    id: "w1", lemma: "forest", surface: "forest", phonetic: "/ˈfɒrɪst/", pos: "n.",
    meaningZh: "森林，大片树木", status: "learning", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "The little fox walks through the green ___.", sentenceZh: "小狐狸穿过翠绿的森林。",
    timestampMs: 1000, timestampLabel: "00:01", aiWhyHere: "狐狸住的地方，很多树长在一起就是 forest。",
    aiExampleEn: "An owl lives in the dark forest.", aiExampleZh: "一只猫头鹰住在黑黑的森林里。",
    nextReviewLabel: "今天稍后",
  },
  {
    id: "w2", lemma: "hop", surface: "hopping", phonetic: "/hɒp/", pos: "v.",
    meaningZh: "单脚跳，蹦跳", status: "learning", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "A rabbit is ___ over the hill.", sentenceZh: "一只小兔正跳过小山丘。",
    timestampMs: 8400, timestampLabel: "00:08", aiWhyHere: "兔子用后腿一起蹦着走，这个动作叫 hop。",
    aiExampleEn: "The frog is hopping on the leaf.", aiExampleZh: "青蛙在叶子上蹦跳。",
    nextReviewLabel: "今天稍后",
  },
  {
    id: "w3", lemma: "paw", surface: "paw", phonetic: "/pɔː/", pos: "n.",
    meaningZh: "爪子（动物的脚）", status: "review", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "The rabbit smiles and waves her ___.", sentenceZh: "小兔微笑着挥挥爪子。",
    timestampMs: 18000, timestampLabel: "00:18", aiWhyHere: "动物的脚叫 paw，有肉垫和毛。",
    aiExampleEn: "The cat put its paw on my hand.", aiExampleZh: "猫把爪子放在我手上。",
    nextReviewLabel: "明天",
  },
  {
    id: "w4", lemma: "river", surface: "river", phonetic: "/ˈrɪvə/", pos: "n.",
    meaningZh: "河流，江河", status: "review", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "Let's play together by the ___.", sentenceZh: "我们一起到河边玩吧。",
    timestampMs: 22800, timestampLabel: "00:22", aiWhyHere: "流动的水，比 stream 大。",
    aiExampleEn: "Fish swim in the clear river.", aiExampleZh: "鱼在清澈的河里游。",
    nextReviewLabel: "明天",
  },
  {
    id: "w5", lemma: "wonderful", surface: "wonderful", phonetic: "/ˈwʌndəfəl/", pos: "adj.",
    meaningZh: "极好的，奇妙的", status: "mastered", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "What a ___ day for new friends!", sentenceZh: "交到新朋友，多美好的一天啊！",
    timestampMs: 37200, timestampLabel: "00:37", aiWhyHere: "比 good 更惊喜，像有魔法一样。",
    aiExampleEn: "We had a wonderful trip to the zoo.", aiExampleZh: "我们去动物园的旅行棒极了。",
    nextReviewLabel: "3 天后",
  },
  {
    id: "w6", lemma: "fur", surface: "fur", phonetic: "/fɜː/", pos: "n.",
    meaningZh: "毛皮，软毛", status: "learning", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "The sun shines warm on their ___.", sentenceZh: "阳光温暖地照在它们的毛皮上。",
    timestampMs: 32400, timestampLabel: "00:32", aiWhyHere: "狐狸身上的毛皮，保暖又柔软。",
    aiExampleEn: "The bear has thick brown fur.", aiExampleZh: "熊有厚厚的棕色毛皮。",
    nextReviewLabel: "今天稍后",
  },
  {
    id: "w7", lemma: "grass", surface: "grass", phonetic: "/ɡrɑːs/", pos: "n.",
    meaningZh: "草，青草", status: "mastered", videoId: "v1", videoTitle: "The Fox and the Rabbit",
    sentenceEn: "They run fast across the soft ___.", sentenceZh: "它们飞快地跑过柔软的草地。",
    timestampMs: 28000, timestampLabel: "00:28", aiWhyHere: "不可数名词，泛指草地。",
    aiExampleEn: "The sheep eats green grass.", aiExampleZh: "羊吃着绿油油的草。",
    nextReviewLabel: "3 天后",
  },
];

export const formatTimestamp = (ms: number): string => {
  const totalSec = Math.floor(ms / 1000);
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
};
