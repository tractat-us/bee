package us.tractat.bee.host.fixtures

/**
 * Writes the Playwright fixture surfaces onto a running host through a JVM `BoardClient`
 * (Task 4). Run as `:bee-host:e2eSeed -Pbee.e2e.host=<url>` from Playwright's globalSetup,
 * which passes `--host <url>`. It seeds every fixture by default; `--fixture <name>` may
 * narrow it to one.
 *
 * Placeholder until Task 4: it writes nothing and exits 0.
 */
fun main(args: Array<String>) {
    val host = args.toList().zipWithNext().firstOrNull { it.first == "--host" }?.second ?: "<no --host given>"
    System.out.println("TODO(Task 4): seed the e2e surfaces on $host")
}
