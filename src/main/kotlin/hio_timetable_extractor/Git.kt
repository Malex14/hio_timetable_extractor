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

    if (!path.resolve(".git").exists()) {
        if (Files.newDirectoryStream(path).any()) {
            logger.error { "'EXPORT_DIR' is no git repository, but has files in it" }
            exitProcess(-1)
        }
        run(pathAsFile, "git", "clone", url, path.toString())
    }
}

internal fun pushDirToGit(path: Path) {
    val pathAsFile = path.toFile()

    val checkStatus = { expected: Int ->
        { status: Int -> if (status != expected) throw IllegalStateException("git returned unexpected code $status") }
    }

    run(pathAsFile, "git", "pull", "--ff-only").also(checkStatus(0))
    run(pathAsFile, "git", "add", "--all").also(checkStatus(0))

    val commitMessage = LocalDateTime.now().toString()
    run(pathAsFile, "git", "commit", "-m", commitMessage) // this is allowed to fail
    run(pathAsFile, "git", "push").also(checkStatus(0))
}

private fun run(workdir: File, vararg command: String): Int {
    val proc = ProcessBuilder(*command)
        .directory(workdir)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    return proc.waitFor() //TODO: this may cause a hang, if the process never exits
}