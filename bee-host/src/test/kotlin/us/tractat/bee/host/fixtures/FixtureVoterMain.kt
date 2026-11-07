package us.tractat.bee.host.fixtures

/**
 * The fixture Raft voter the browser claim test dials (Task 6). bee-web's build starts it
 * before `:bee-web:wasmJsBrowserTest` with `-Pbee.realsocket.tests=true`, reads its port
 * from a stdout line `BEE_FIXTURE_VOTER_PORT=<n>`, and stops it after the task.
 *
 * Placeholder until Task 6: it reports no port and exits 0.
 */
fun main() {
    System.out.println("TODO(Task 6): the fixture voter")
}
