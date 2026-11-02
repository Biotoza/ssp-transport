package sh.haven.mosh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.transport.MoshTransport

/**
 * Policy tests for the #421 dead-session escalation.
 *
 * Reported symptom: a mosh session stalls (often while scrolling), the "no
 * server contact — retrying" banner appears and never resolves, and only a
 * manual close-and-reconnect — which starts a fresh mosh-server — recovers.
 * The transport deliberately never gives up, on the assumption that silence
 * only ever means the network is away; when the server side is gone instead,
 * that assumption strands the session forever.
 *
 * Silence alone must never kill a session (#92): a dead network looks
 * identical to a dead server, and device connectivity sensors lie during
 * interface transitions. The transport therefore escalates only on the host's
 * out-of-band verdict — a probe over the profile's SSH channel that verified
 * the mosh-server process is gone. Unknown (probe unreachable) or "alive"
 * keep the session waiting, however long the silence.
 */
class DeadSessionEscalationTest {

    private val dead: () -> Boolean? = { true }
    private val alive: () -> Boolean? = { false }
    private val unknown: () -> Boolean? = { null }

    /** The reported case: the server-side session verified gone. */
    @Test
    fun `declares dead when the probe confirms the server session is gone`() {
        assertTrue(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS + 1,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD,
                serverSessionDead = dead,
            ),
        )
    }

    /**
     * The #92 regression guard: a phone in a pocket, in a tunnel, or in doze is
     * offline — the probe cannot reach the server and reports unknown.
     * However long the silence, an unreachable probe must never declare the
     * session dead; mosh's whole value is that such a session resumes.
     */
    @Test
    fun `never declares dead while the probe is unknown, however long the silence`() {
        assertFalse(
            "an unreachable probe must keep waiting — this is the #92 regression",
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS * 100,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD * 10,
                serverSessionDead = unknown,
            ),
        )
    }

    /** A probe that reached the server and found the session alive: keep waiting. */
    @Test
    fun `never declares dead while the server session is provably alive`() {
        assertFalse(
            "a live server-side session must keep waiting for the network",
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS * 100,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD * 10,
                serverSessionDead = alive,
            ),
        )
    }

    /** A transport without a probe (the default) must never kill on silence. */
    @Test
    fun `never declares dead on silence when no probe is wired`() {
        assertFalse(
            "the default of no probe must be the safe wait-forever behaviour",
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS * 100,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD * 10,
            ),
        )
    }

    /** A brief stall (a lost packet, a roam in progress) must not kill the session. */
    @Test
    fun `does not declare dead before the silence threshold even with a dead verdict`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS - 1,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD * 5,
                serverSessionDead = dead,
            ),
        )
    }

    /**
     * Recovery must have been attempted and ignored first: one rebind landing
     * badly (e.g. mid-interface-change) is not evidence the session is gone.
     */
    @Test
    fun `does not declare dead until enough rebinds have gone unanswered`() {
        assertFalse(
            MoshTransport.shouldDeclareDead(
                recvAgeMs = MoshTransport.DEAD_SESSION_MS * 10,
                rebindsSinceReceive = MoshTransport.MIN_REBINDS_BEFORE_DEAD - 1,
                serverSessionDead = dead,
            ),
        )
    }

    /**
     * The threshold is deliberately far above the rebind cadence, so a real
     * roam gets several recovery attempts before we ever consider giving up.
     */
    @Test
    fun `threshold leaves room for several rebinds before giving up`() {
        assertTrue(
            "DEAD_SESSION_MS must span at least MIN_REBINDS_BEFORE_DEAD rebind cycles",
            MoshTransport.DEAD_SESSION_MS >=
                MoshTransport.NETWORK_STALL_MS * MoshTransport.MIN_REBINDS_BEFORE_DEAD,
        )
    }
}
