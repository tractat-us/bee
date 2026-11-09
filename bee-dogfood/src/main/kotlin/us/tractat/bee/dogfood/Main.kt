package us.tractat.bee.dogfood

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

private val log = KotlinLogging.logger("us.tractat.bee.dogfood.Main")

/** The command line: where the board is, who joins it, and where the files are. */
data class SeedArgs(
    val host: String = "http://127.0.0.1:8240",
    val name: String = "dogfood",
    val repo: Path = Path("."),
    val reports: Path = repo,
    val github: Boolean = true,
) {
    companion object {
        const val USAGE = "bee-dogfood-seed [--host <url>] [--name <agent>] [--repo <dir>] [--reports <dir>] [--no-github]"

        fun parse(args: List<String>): SeedArgs {
            var parsed = SeedArgs()
            var reports: Path? = null
            var rest = args
            while (rest.isNotEmpty()) {
                val flag = rest.first()
                fun value(): String = requireNotNull(rest.getOrNull(1)) { "$flag needs a value. Usage: $USAGE" }
                rest = when (flag) {
                    "--host" -> { parsed = parsed.copy(host = value()); rest.drop(2) }
                    "--name" -> { parsed = parsed.copy(name = value()); rest.drop(2) }
                    "--repo" -> { parsed = parsed.copy(repo = Path(value())); rest.drop(2) }
                    "--reports" -> { reports = Path(value()); rest.drop(2) }
                    "--no-github" -> { parsed = parsed.copy(github = false); rest.drop(1) }
                    else -> throw IllegalArgumentException("Unknown argument $flag. Usage: $USAGE")
                }
            }
            return parsed.copy(reports = reports ?: parsed.repo)
        }
    }
}

/** The three surfaces, read from the files under [args]' repo and from GitHub. */
fun surfaces(args: SeedArgs): List<SeedSurface> {
    val plan = PlanBoard.surface(PlanYaml.parse(args.repo.resolve("docs/project/plan.yaml").readText()))
    val report = MorningReport.newest(args.reports)
    val morning = MorningReport.surface(report?.readText(), report?.fileName?.toString())
    val decisions = if (args.github) github(args.repo) else Decisions.surface(emptyList(), "GitHub not asked (--no-github).")
    return listOf(plan, morning, decisions)
}

/**
 * The open decisions from `gh`, run in [repo]. Without `gh`, or offline, the surface says so and the
 * other two still draw.
 */
private fun github(repo: Path): SeedSurface = try {
    Decisions.surface(
        listOf("issue", "pr").flatMap { kind ->
            Decisions.LABELS.flatMap { label -> Decisions.parse(gh(repo, kind, "list", "--state", "open", "--label", label, "--limit", "50", "--json", "number,title,url,body,labels")) }
        },
    )
} catch (failed: java.io.IOException) {
    log.warn(failed) { "gh failed" }
    Decisions.surface(emptyList(), "Couldn't ask GitHub: ${failed.message}")
}

/** Runs `gh` in [repo] and returns its stdout. Its output goes to a file, so a hung `gh` can't block the wait. */
private fun gh(repo: Path, vararg args: String): String {
    val out = Files.createTempFile("bee-dogfood-gh-", ".json")
    try {
        val process = ProcessBuilder(listOf("gh", *args)).directory(repo.toFile())
            .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        if (!process.waitFor(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw java.io.IOException("gh ${args.joinToString(" ")} took over $GH_TIMEOUT_SECONDS s")
        }
        if (process.exitValue() != 0) throw java.io.IOException("gh ${args.joinToString(" ")} exited ${process.exitValue()}")
        return out.readText()
    } finally {
        Files.deleteIfExists(out)
    }
}

private const val GH_TIMEOUT_SECONDS = 60L

/**
 * Joins the board as an agent, draws the Plan, Morning report and Decisions surfaces whole, and
 * leaves. Prints one line per surface: the CLI's output is its report, so it goes to stdout.
 */
fun main(argv: Array<String>) {
    val args = try {
        SeedArgs.parse(argv.toList())
    } catch (bad: IllegalArgumentException) {
        System.err.println(bad.message)
        exitProcess(2)
    }
    // The files and gh first, so a bad plan.yaml or a slow gh costs no join and departure.
    val drawn = surfaces(args)
    val outcomes = runBlocking {
        val tools = McpBoardTools.join(args.host, args.name)
        try {
            Seeder(tools).seed(drawn)
        } finally {
            tools.leave()
        }
    }
    outcomes.forEach { (id, outcome) -> println("$id: $outcome") }
    if (Outcome.UpdatedClosed in outcomes.values) {
        println("A closed surface was redrawn in place; reopen it from the board's history strip to see it.")
    }
    println("Board: ${args.host.trimEnd('/')}/")
}
