package io.github.jwyoon1220.dncity.voice

/**
 * Per-speaker jitter-queue mixer for concurrently-speaking close-range voices, drained by
 * [VoiceClientLoop] into `NativeAudio.writePlayback`.
 *
 * Each speaker gets its own FIFO of decoded, gain-scaled frames -- [submit] enqueues rather than
 * summing in place, so a speaker whose frames arrive faster than they're drained gets them
 * played back in order instead of overlapping different instants of their own voice into one
 * garbled blob (an earlier single-shared-accumulator version had exactly that bug). [drainFrame]
 * pops one frame per active speaker and additively mixes those -- summing *different* speakers'
 * simultaneous frames is correct; it's only summing the *same* speaker's sequential frames that
 * isn't. Still no resampling/reordering and no real jitter-buffer smoothing beyond the small
 * per-source cap below; adequate for this tier's short range and low expected speaker count.
 */
object VoiceAudioMixer {
    // ~150ms of headroom per speaker -- enough to absorb a burst of frames arriving together
    // (e.g. a sender's capture pump draining several 20ms frames in one 50ms tick) without
    // growing unbounded if a speaker's audio backs up.
    private const val MAX_QUEUED_FRAMES_PER_SOURCE = 8

    private val queues = HashMap<Int, ArrayDeque<ShortArray>>()
    private val lock = Any()

    fun submit(sourceId: Int, samples: ShortArray, gain: Float) {
        val scaled = ShortArray(samples.size) { i ->
            (samples[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        synchronized(lock) {
            val queue = queues.getOrPut(sourceId) { ArrayDeque() }
            queue.addLast(scaled)
            while (queue.size > MAX_QUEUED_FRAMES_PER_SOURCE) {
                queue.removeFirst()
            }
        }
    }

    /**
     * Pops and mixes one frame from every speaker that currently has one queued. Returns `null`
     * when nobody has anything queued -- callers should stop draining rather than write silence
     * themselves, since `NativeAudio`'s ring buffer already fills silence on underrun natively.
     * Call this in a loop (draining everything available) rather than once per tick: this tier's
     * 50ms game tick is more than double a 20ms voice frame, so draining only one frame per tick
     * chronically underfeeds playback and sounds like a rapid on/off chop.
     */
    fun drainFrame(): ShortArray? {
        synchronized(lock) {
            var mixed: IntArray? = null
            val emptySources = ArrayList<Int>()
            for ((sourceId, queue) in queues) {
                if (queue.isEmpty()) {
                    emptySources.add(sourceId)
                    continue
                }
                val frame = queue.removeFirst()
                val acc = mixed ?: IntArray(OpusCodec.FRAME_SIZE).also { mixed = it }
                val n = minOf(frame.size, acc.size)
                for (i in 0 until n) acc[i] += frame[i]
            }
            emptySources.forEach { queues.remove(it) }

            val acc = mixed ?: return null
            return ShortArray(acc.size) { i -> acc[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
        }
    }

    /** Called when a speaker's decoder is released (see [ClientVoiceReceiver.releaseAll]). */
    fun releaseSource(sourceId: Int) {
        synchronized(lock) { queues.remove(sourceId) }
    }

    fun clear() {
        synchronized(lock) { queues.clear() }
    }
}
