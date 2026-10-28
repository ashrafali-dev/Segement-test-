package com.ashraf.novelstudio

/**
 * Single source of truth for "what is happening below".
 *
 * The Chat ring, the Tasks tray, the top progress line and (later) the immersive ring all read
 * ONLY from this model. MainActivity feeds it from the existing lane source
 * (`activityDotStatuses()` plus job counters); nothing here starts or stops work.
 *
 * The model is deliberately cheap: it is rebuilt at most once per second (or immediately when a
 * real state changes), never in a per-frame path, and it allocates one small immutable list.
 */
class StatusModel {

    enum class State {
        IDLE, WORKING, WAITING, ATTENTION, DONE
    }

    /** One parallel lane of work. Immutable once built. */
    class Lane(
        val name: String,
        val key: String,
        val stateText: String,
        val elapsedSec: Int,
        /** Real determinate progress; null when the lane has no known total (never faked). */
        val n: Int?,
        val total: Int?,
        val state: State,
        /** Right-aligned short value ("7 ready", "2"), null when the lane shows nothing there. */
        val rightText: String?
    ) {
        val hasProgress: Boolean get() = n != null && total != null && total > 0
        val fraction: Float
            get() = if (hasProgress) (n!!.toFloat() / total!!.toFloat()).coerceIn(0f, 1f) else 0f
    }

    /** Snapshot passed to views. Never mutated after construction. */
    class Snapshot(
        val lanes: List<Lane>,
        val needsAttention: Boolean,
        val attentionText: String?,
        /** Chatbot lane progress for the dock ring: count when determinate, else -1. */
        val chatCount: Int,
        val chatTotal: Int,
        val chatHasProgress: Boolean,
        val anyWork: Boolean,
        val detailLine: String
    ) {
        val top: Lane? get() = lanes.firstOrNull()
        val chatLane: Lane? get() = lanes.firstOrNull { it.key.startsWith("chat") }
        val queueLane: Lane? get() = lanes.firstOrNull { it.key == "queue" }
    }

    /**
     * Build a snapshot.
     *
     * @param statuses raw lanes from MainActivity.activityDotStatuses()
     * @param chatFound paragraphs the chatbot has answered so far
     * @param chatTotal paragraphs sent (jobMarkers); 0 when unknown
     * @param chatPhase 1 waiting, 2 translating, 3 checking; 0 idle
     * @param chatElapsedSec seconds the current chatbot job has run
     * @param chatProvider display name of the active chatbot (e.g. "ChatGPT")
     * @param queueSize chapters waiting in the queue
     * @param aheadOn whether "Ahead" is enabled
     * @param aheadReady how many upcoming chapters are already translated
     * @param attentionText a needs-you condition that outranks lane text (login, captcha, failure)
     * @param detailLine the old chip's second line (OCR engine / Detector / AI painter)
     */
    fun build(
        statuses: List<Pair<String, String>>,
        chatFound: Int,
        chatTotal: Int,
        chatPhase: Int,
        chatElapsedSec: Int,
        chatProvider: String,
        queueSize: Int,
        aheadOn: Boolean,
        aheadReady: Int,
        attentionText: String?,
        detailLine: String
    ): Snapshot {
        val lanes = ArrayList<Lane>(4)

        // ---- Chatbot lane (the heartbeat of the dock)
        val hasChat = statuses.any { p ->
            p.first.startsWith("chat") || p.first == "ahead-translation" || p.first == "prefetch-translation"
        }
        val determinate = chatTotal > 0
        if (hasChat || chatPhase > 0) {
            val stateText = when (chatPhase) {
                1 -> Strings.WAITING
                3 -> Strings.CHECKING
                2 -> Strings.TRANSLATING
                else -> Strings.WORKING_ELSE
            }
            val right = if (determinate) "$chatFound / $chatTotal" else null
            lanes.add(
                Lane(
                    name = Strings.ROW_CHATBOT + " · " + chatProvider,
                    key = "chat",
                    stateText = stateText,
                    elapsedSec = chatElapsedSec,
                    n = if (determinate) chatFound else null,
                    total = if (determinate) chatTotal else null,
                    state = if (chatPhase == 1) State.WAITING else State.WORKING,
                    rightText = right
                )
            )
        }

        // ---- Read/OCR lanes (visible screen, read-ahead, next chapter)
        statuses.firstOrNull { it.first == "visible-ocr" }?.let {
            lanes.add(Lane(Strings.READING, "read", it.second, 0, null, null, State.WORKING, null))
        }
        statuses.firstOrNull { it.first == "next-chapter-ocr" || it.first == "next-chapter-load" }?.let {
            lanes.add(Lane(Strings.ROW_NEXT, "next", it.second, 0, null, null, State.WORKING, null))
        }
        statuses.firstOrNull { it.first == "read-ahead-ocr" }?.let {
            lanes.add(Lane(Strings.ROW_AHEAD, "ahead", it.second, 0, null, null, State.WORKING, null))
        }

        // ---- Queue lane
        if (queueSize > 0) {
            lanes.add(
                Lane(
                    name = Strings.ROW_QUEUE,
                    key = "queue",
                    stateText = Strings.WAITING_FOR_CHATBOT,
                    elapsedSec = 0,
                    n = null, total = null,
                    state = State.WAITING,
                    rightText = queueSize.toString()
                )
            )
        }

        // ---- Quiet "Ahead" lane: only when the feature is on (so the tray explains itself)
        if (aheadOn && aheadReady > 0) {
            lanes.add(
                Lane(
                    name = Strings.ROW_AHEAD,
                    key = "ahead-ready",
                    stateText = Strings.IDLE,
                    elapsedSec = 0,
                    n = null, total = null,
                    state = State.DONE,
                    rightText = "$aheadReady ready"
                )
            )
        }

        val needsAttention = !attentionText.isNullOrBlank()
        val chatHasProgress = determinate && chatPhase > 0
        val anyWork = lanes.any { it.state == State.WORKING || it.state == State.WAITING }
        return Snapshot(
            lanes = lanes,
            needsAttention = needsAttention,
            attentionText = attentionText,
            chatCount = if (chatHasProgress) chatFound else -1,
            chatTotal = if (determinate) chatTotal else 0,
            chatHasProgress = chatHasProgress,
            anyWork = anyWork,
            detailLine = detailLine
        )
    }

    // Lanes arrive as plain (key, label) pairs, so this file never depends on a private
    // MainActivity type. MainActivity maps its ActivityDotStatus list to them cheaply.
}
