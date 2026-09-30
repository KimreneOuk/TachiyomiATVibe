package eu.kanade.translation.persistence.journal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Binary framing for the shadow journal. Payload meaning is intentionally owned by the caller.
 * Every physical frame, including metadata, consumes `frameSeq` and participates in the durable-prefix ACK.
 * Only state frames consume `commitSeq`; after TERMINAL_LAG the logical legacy sequence may advance without
 * frames, so later captured commits may have a gap while remaining strictly increasing.
 */
internal object ChapterJournalFormat {
    const val FORMAT_VERSION = 2

    // magic + version + segment index + generation + epoch ordinal + UUID.
    const val SEGMENT_HEADER_BYTES = 48

    // payload length + physical frame sequence + legacy-aligned commit sequence + kind/reserved.
    const val FRAME_HEADER_BYTES = 24
    const val FRAME_TRAILER_BYTES = 4
    const val MAX_PAYLOAD_BYTES = 512 * 1024
    const val SEGMENT_BYTE_LIMIT = 1024 * 1024L

    private const val SEGMENT_MAGIC = 0x54414A31 // TAJ1

    /**
     * DEFUNCT marks the eviction boundary, not end-of-stream: frames after it
     * are legal when they use credits acquired before eviction and therefore
     * represent legacy commits already accepted by the store. Replay applies
     * those drain frames. A clean close, DEFUNCT-only epoch, DEFUNCT followed
     * by drain frames, and TERMINAL_LAG each have distinct framing outcomes;
     * process death may leave any outcome at its last valid CRC-framed prefix.
     */
    enum class RecordKind(val code: Int, val hasCommitSequence: Boolean = false) {
        FREE_STATE(1, hasCommitSequence = true),
        PAID_STATE(2, hasCommitSequence = true),
        TERMINAL_LAG(3),
        DEFUNCT(4),
        INVENTORY(5),
        TERMINAL_PAYLOAD(6),
    }

    data class Frame(
        val frameSeq: Long,
        val commitSeq: Long?,
        val kind: RecordKind,
        val payload: ByteArray,
        val endOffset: Int,
    )

    data class ScanResult(
        val segmentIndex: Long,
        val generation: Long,
        val epochOrdinal: Long,
        val sessionId: UUID,
        val frames: List<Frame>,
        val nextFrameSeq: Long,
        val nextCommitSeq: Long,
        val terminalLagSeen: Boolean,
        val terminalPayloadSeen: Boolean,
        val validBytes: Int,
        val validHeader: Boolean,
        val stoppedAtInvalidFrame: Boolean,
    )

    /** Ordering identity for one persisted store-session epoch. */
    data class EpochOrderKey(
        val storeGeneration: Long,
        val epochOrdinal: Long,
        val sessionId: UUID,
    )

    fun segmentHeader(segmentIndex: Long, generation: Long, epochOrdinal: Long, sessionId: UUID): ByteArray =
        ByteBuffer.allocate(SEGMENT_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(SEGMENT_MAGIC)
            .putInt(FORMAT_VERSION)
            .putLong(segmentIndex)
            .putLong(generation)
            .putLong(epochOrdinal)
            .putLong(sessionId.mostSignificantBits)
            .putLong(sessionId.leastSignificantBits)
            .array()

    fun encodeFrame(frameSeq: Long, commitSeq: Long?, kind: RecordKind, payload: ByteArray): ByteArray {
        require(frameSeq > 0) { "frameSeq must be positive" }
        require(kind.hasCommitSequence == (commitSeq != null)) {
            "commitSeq presence must match record kind: kind=$kind commitSeq=$commitSeq"
        }
        require(commitSeq == null || commitSeq > 0) { "commitSeq must be positive" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "journal payload exceeds $MAX_PAYLOAD_BYTES bytes" }
        val header = ByteBuffer.allocate(FRAME_HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .putLong(frameSeq)
            .putLong(commitSeq ?: 0L)
            .put(kind.code.toByte())
            .put(byteArrayOf(0, 0, 0))
            .array()
        val crc = CRC32().apply {
            update(header)
            update(payload)
        }.value.toInt()
        return ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.size + FRAME_TRAILER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .put(payload)
            .putInt(crc)
            .array()
    }

    /** Scan exactly the valid contiguous frame prefix; metadata never consumes commitSeq. */
    fun scanSegment(
        bytes: ByteArray,
        expectedSegmentIndex: Long,
        expectedGeneration: Long,
        expectedEpochOrdinal: Long,
        expectedSessionId: UUID,
        firstExpectedFrameSeq: Long,
        firstExpectedCommitSeq: Long,
        lastCommitSeq: Long = firstExpectedCommitSeq - 1,
        terminalLagSeen: Boolean = false,
        terminalPayloadSeen: Boolean = false,
    ): ScanResult {
        if (bytes.size < SEGMENT_HEADER_BYTES) {
            return invalidHeaderResult(expectedSegmentIndex, expectedGeneration, expectedEpochOrdinal, expectedSessionId)
        }
        val header = ByteBuffer.wrap(bytes, 0, SEGMENT_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        val magic = header.int
        val version = header.int
        val segmentIndex = header.long
        val generation = header.long
        val epochOrdinal = header.long
        val sessionId = UUID(header.long, header.long)
        if (magic != SEGMENT_MAGIC ||
            version != FORMAT_VERSION ||
            segmentIndex != expectedSegmentIndex ||
            generation != expectedGeneration ||
            epochOrdinal != expectedEpochOrdinal ||
            sessionId != expectedSessionId
        ) {
            return invalidHeaderResult(expectedSegmentIndex, expectedGeneration, expectedEpochOrdinal, expectedSessionId)
        }

        val frames = ArrayList<Frame>()
        var offset = SEGMENT_HEADER_BYTES
        var expectedFrameSeq = firstExpectedFrameSeq
        var expectedCommitSeq = firstExpectedCommitSeq
        var previousCommitSeq = lastCommitSeq
        var terminalLag = terminalLagSeen
        var terminalPayload = terminalPayloadSeen
        while (offset < bytes.size) {
            if (bytes.size - offset < FRAME_HEADER_BYTES + FRAME_TRAILER_BYTES) break
            val frameHeader = ByteBuffer.wrap(bytes, offset, FRAME_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            val payloadSize = frameHeader.int
            val frameSeq = frameHeader.long
            val encodedCommitSeq = frameHeader.long
            val kindCode = frameHeader.get().toInt() and 0xff
            val reserved = byteArrayOf(frameHeader.get(), frameHeader.get(), frameHeader.get())
            val kind = RecordKind.entries.firstOrNull { it.code == kindCode }
            if (payloadSize !in 0..MAX_PAYLOAD_BYTES ||
                kind == null ||
                reserved.any { it.toInt() != 0 } ||
                frameSeq != expectedFrameSeq ||
                kind.hasCommitSequence != (encodedCommitSeq > 0) ||
                (!kind.hasCommitSequence && encodedCommitSeq != 0L) ||
                terminalPayload ||
                (terminalLag && kind == RecordKind.INVENTORY) ||
                (kind == RecordKind.TERMINAL_LAG && (terminalLag || terminalPayload)) ||
                (kind == RecordKind.TERMINAL_PAYLOAD && terminalLag)
            ) {
                return ScanResult(
                    segmentIndex,
                    generation,
                    epochOrdinal,
                    sessionId,
                    frames,
                    expectedFrameSeq,
                    expectedCommitSeq,
                    terminalLag,
                    terminalPayload,
                    offset,
                    true,
                    true,
                )
            }
            if (kind.hasCommitSequence) {
                val sequenceValid = if (terminalLag) {
                    encodedCommitSeq > previousCommitSeq
                } else {
                    encodedCommitSeq == expectedCommitSeq
                }
                if (!sequenceValid) {
                    return ScanResult(
                        segmentIndex,
                        generation,
                        epochOrdinal,
                        sessionId,
                        frames,
                        expectedFrameSeq,
                        expectedCommitSeq,
                        terminalLag,
                        terminalPayload,
                        offset,
                        true,
                        true,
                    )
                }
            }
            val frameSize = FRAME_HEADER_BYTES + payloadSize + FRAME_TRAILER_BYTES
            if (bytes.size - offset < frameSize) break
            val payloadOffset = offset + FRAME_HEADER_BYTES
            val payload = bytes.copyOfRange(payloadOffset, payloadOffset + payloadSize)
            val expectedCrc = ByteBuffer.wrap(bytes, payloadOffset + payloadSize, FRAME_TRAILER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .int
            val actualCrc = CRC32().apply {
                update(bytes, offset, FRAME_HEADER_BYTES)
                update(payload)
            }.value.toInt()
            if (actualCrc != expectedCrc) {
                return ScanResult(
                    segmentIndex,
                    generation,
                    epochOrdinal,
                    sessionId,
                    frames,
                    expectedFrameSeq,
                    expectedCommitSeq,
                    terminalLag,
                    terminalPayload,
                    offset,
                    true,
                    true,
                )
            }
            offset += frameSize
            val commitSeq = encodedCommitSeq.takeIf { kind.hasCommitSequence }
            frames += Frame(frameSeq, commitSeq, kind, payload, offset)
            if (commitSeq != null) {
                previousCommitSeq = commitSeq
                expectedCommitSeq = commitSeq + 1
            }
            if (kind == RecordKind.TERMINAL_LAG) terminalLag = true
            if (kind == RecordKind.TERMINAL_PAYLOAD) terminalPayload = true
            expectedFrameSeq++
        }
        return ScanResult(
            segmentIndex,
            generation,
            epochOrdinal,
            sessionId,
            frames,
            expectedFrameSeq,
            expectedCommitSeq,
            terminalLag,
            terminalPayload,
            offset,
            true,
            offset != bytes.size,
        )
    }

    private fun invalidHeaderResult(
        expectedSegmentIndex: Long,
        expectedGeneration: Long,
        expectedEpochOrdinal: Long,
        expectedSessionId: UUID,
    ) = ScanResult(
        expectedSegmentIndex,
        expectedGeneration,
        expectedEpochOrdinal,
        expectedSessionId,
        emptyList(),
        1L,
        1L,
        false,
        false,
        0,
        false,
        true,
    )

    /**
     * Across epochs, generation is authoritative and the persisted ordinal breaks generation ties.
     * Equal generations are possible after a crash skips the defunct-generation bump. Since the
     * registry permits only one active store per chapter, two distinct epoch headers cannot share
     * both fields; replay treats that impossible state as corruption rather than ordering by UUID.
     */
    fun compareEpochOrder(left: EpochOrderKey, right: EpochOrderKey): Int {
        if (left === right) return 0
        val ordering = compareValues(left.storeGeneration, right.storeGeneration).takeIf { it != 0 }
            ?: compareValues(left.epochOrdinal, right.epochOrdinal)
        check(ordering != 0) {
            "distinct journal epochs share storeGeneration=${left.storeGeneration} and epochOrdinal=${left.epochOrdinal}"
        }
        return ordering
    }
}
