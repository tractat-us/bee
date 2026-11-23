package us.tractat.bee.core

import us.tractat.kuilt.quilter.MonotonicMillis
import kotlin.time.TimeSource

/**
 * This time source as a Quilter's clock: the milliseconds elapsed since now. Invariant: **a
 * Quilter's clock is the time source its caller injected**, so one under a test scheduler's
 * [TimeSource] reads virtual time, and one under [TimeSource.Monotonic] reads what kuilt's own
 * default does.
 *
 * bee takes a [TimeSource] rather than kuilt's default clock because that default is private to
 * kuilt (tractat-us/kuilt#3624), so a caller can't pass it on.
 */
fun TimeSource.asMonotonicMillis(): MonotonicMillis {
    val origin = markNow()
    return MonotonicMillis { origin.elapsedNow().inWholeMilliseconds }
}
