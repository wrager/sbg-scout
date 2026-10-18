package com.github.wrager.sbgscout.script.updater

import com.github.wrager.sbgscout.script.model.ScriptVersion

/**
 * Загружает и агрегирует release notes скрипта из GitHub Releases API.
 *
 * Для каждого скрипта определяет owner/repo из sourceUrl, загружает список релизов,
 * фильтрует те, что новее текущей версии, и объединяет их body в единый текст.
 */
class ScriptReleaseNotesProvider(
    private val githubReleaseProvider: GithubReleaseProvider,
) {
    /**
     * Загружает агрегированные release notes для скрипта.
     *
     * @param sourceUrl URL загрузки скрипта (для определения owner/repo)
     * @param currentVersion текущая установленная версия
     * @return агрегированные release notes или null, если заметок нет
     */
    suspend fun fetchReleaseNotes(
        sourceUrl: String,
        currentVersion: ScriptVersion,
    ): String? {
        val (owner, repository) =
            GithubReleaseProvider.extractOwnerAndRepository(sourceUrl)
                ?: return null
        val releases = githubReleaseProvider.fetchReleases(owner, repository)
        return aggregateNotes(releases, currentVersion)
    }

    companion object {
        /**
         * Объединяет release notes всех релизов новее [currentVersion].
         *
         * GitHub API возвращает релизы в обратном хронологическом порядке,
         * поэтому итоговый текст идёт от новейшего к старейшему.
         */
        internal fun aggregateNotes(
            releases: List<GithubRelease>,
            currentVersion: ScriptVersion,
        ): String? {
            val notes =
                releases
                    .filter {
                        val tagVersion = it.tagName.removePrefix("v")
                        // Пропускаем теги, не являющиеся семантическими версиями
                        tagVersion.firstOrNull()?.isDigit() == true &&
                            ScriptVersion(tagVersion) > currentVersion
                    }.mapNotNull { release ->
                        val body = release.body?.trim()
                        if (body.isNullOrEmpty()) null else "${release.tagName}\n$body"
                    }
            return notes.joinToString("\n\n").ifEmpty { null }
        }
    }
}
