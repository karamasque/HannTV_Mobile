package tv.own.owntv.core.live

object CatchupContinue {
    sealed interface Next {
        data class Programme(val startMs: Long?, val stopMs: Long?) : Next
        data object Live : Next
        data object Stop : Next
    }

    fun decide(nextStartMs: Long?, nextStopMs: Long?, nowMs: Long): Next {
        if (nextStartMs == null || nextStopMs == null) return Next.Live
        if (nowMs >= nextStopMs) return Next.Programme(nextStartMs, nextStopMs)
        return Next.Live
    }
}
