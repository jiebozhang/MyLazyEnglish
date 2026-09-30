package com.lazyeng.family.core.testing.fixtures

data class ProfileFixture(
    val id: String,
    val familyId: String,
    val nickname: String,
    val englishLevel: String,
    val createdAtEpochMs: Long,
)

data class VideoFixture(
    val id: String,
    val title: String,
    val durationMs: Long,
)

data class SubtitleLineFixture(
    val id: String,
    val videoId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class DictionaryEntryFixture(
    val lemma: String,
    val partOfSpeech: String,
    val meaning: String,
)

data class VocabularyItemFixture(
    val id: String,
    val profileId: String,
    val lemma: String,
    val videoId: String,
    val subtitleLineId: String,
    val sentence: String,
    val timestampMs: Long,
)

object TestFixtures {
    fun profile(
        id: String = "test-profile-1",
        familyId: String = "test-family-1",
        nickname: String = "Learner One",
        englishLevel: String = "Power Up 2",
        createdAtEpochMs: Long = 0L,
    ) = ProfileFixture(id, familyId, nickname, englishLevel, createdAtEpochMs)

    fun video(
        id: String = "test-video-1",
        title: String = "The Red Kite",
        durationMs: Long = 60_000L,
    ) = VideoFixture(id, title, durationMs)

    fun subtitleLine(
        id: String = "test-line-1",
        videoId: String = video().id,
        startMs: Long = 0L,
        endMs: Long = 2_000L,
        text: String = "The red kite flies above the park.",
    ) = SubtitleLineFixture(id, videoId, startMs, endMs, text)

    fun dictionaryEntry(
        lemma: String = "kite",
        partOfSpeech: String = "noun",
        meaning: String = "a toy that flies in the wind",
    ) = DictionaryEntryFixture(lemma, partOfSpeech, meaning)

    fun vocabularyItem(
        id: String = "test-vocabulary-1",
        profileId: String = profile().id,
        lemma: String = dictionaryEntry().lemma,
        videoId: String = video().id,
        subtitleLineId: String = subtitleLine().id,
        sentence: String = subtitleLine().text,
        timestampMs: Long = subtitleLine().startMs,
    ) = VocabularyItemFixture(
        id = id,
        profileId = profileId,
        lemma = lemma,
        videoId = videoId,
        subtitleLineId = subtitleLineId,
        sentence = sentence,
        timestampMs = timestampMs,
    )
}
