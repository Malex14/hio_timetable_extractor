package de.mbehrmann.hio_timetable_extractor

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.exists
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

internal fun initGit(path: Path, url: String) {
    val pathAsFile = path.toFile()

    logger.info { "Checking if GIT repository is initialized at '$path'" }
    if (!path.resolve(".git").exists()) {
        if (Files.newDirectoryStream(path).any()) {
            logger.error { "'EXPORT_DIR' is no git repository, but has files in it" }
            exitProcess(-1)
        }

        logger.info { "Cloning GIT repository to $path" }
        run(pathAsFile, "git", "clone", url, path.toString()).also(checkStatus(0))
    }

    val eventsDirPath = path.resolve("events")
    if (!eventsDirPath.exists()) {
        Files.createDirectory(eventsDirPath)
    }

    logger.info { "GIT successfully initialized at '$path'" }
}

internal fun pushDirToGit(path: Path) {
    val pathAsFile = path.toFile()

    logger.info { "Updating GIT repository at '$path' and preparing push " }
    run(pathAsFile, "git", "pull", "--ff-only").also(checkStatus(0))
    logger.debug { "Adding changes to GIT repository" }
    run(pathAsFile, "git", "add", "--all").also(checkStatus(0))

    logger.debug { "Committing changes to GIT repository" }
    val commitMessage = LocalDateTime.now().toString()
    run(pathAsFile, "git", "commit", "-m", commitMessage) // this is allowed to fail
    logger.debug { "Pushing changes to GIT repository" }
    run(pathAsFile, "git", "push").also(checkStatus(0))
    logger.info { "GIT push completed" }
}

private fun checkStatus(expected: Int): (Int) -> Unit {
    return { status: Int -> if (status != expected) throw IllegalStateException("git returned unexpected code $status") }
}

private fun run(workdir: File, vararg command: String): Int {
    val proc = ProcessBuilder(*command)
        .directory(workdir)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    return proc.waitFor() //TODO: this may cause a hang, if the process never exits
}