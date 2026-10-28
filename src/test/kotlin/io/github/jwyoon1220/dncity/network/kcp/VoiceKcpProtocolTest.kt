// AGENT-DONE(claude): network-lanes
package io.github.jwyoon1220.dncity.network.kcp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceKcpProtocolTest {
    @Test
    fun `raw datagrams carry the conv and a command byte no KCP segment uses`() {
        val message = VoiceKcpProtocol.encodeAudio(VoiceKcpProtocol.TYPE_VOICE_AUDIO, byteArrayOf(1, 2, 3))
        val datagram = VoiceKcpProtocol.wrapRaw(0x12345678, message)
        // little-endian conv, the same place a KCP segment has it, so the server demuxes both the same way
        assertContentEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), datagram.copyOfRange(0, 4))
        assertTrue(VoiceKcpProtocol.isRaw(datagram))
        assertContentEquals(message, VoiceKcpProtocol.unwrapRaw(datagram))
        // DncityKcp's push (81) and ack (82) segments are never mistaken for raw ones
        for (cmd in listOf(81, 82)) {
            val segment = ByteArray(24).also { it[4] = cmd.toByte() }
            assertFalse(VoiceKcpProtocol.isRaw(segment))
        }
        assertTrue(VoiceKcpProtocol.MAX_RAW_MESSAGE_BYTES + 5 <= 1200)
    }

    @Test
    fun `only audio types count as audio`() {
        for (type in 1..6) assertTrue(VoiceKcpProtocol.isAudio(type.toByte()), "type $type")
        for (type in listOf(0, 7, 8)) assertFalse(VoiceKcpProtocol.isAudio(type.toByte()), "type $type")
    }

    @Test
    fun `the freshness gate drops a frame that arrives behind a newer one of the same stream`() {
        val gate = VoiceKcpProtocol.FreshnessGate()
        val alice = VoiceKcpProtocol.FreshnessGate.key(VoiceKcpProtocol.TYPE_VOICE_AUDIO_RELAY, 1)
        val bob = VoiceKcpProtocol.FreshnessGate.key(VoiceKcpProtocol.TYPE_VOICE_AUDIO_RELAY, 2)
        assertTrue(gate.accept(alice, 1000))
        assertTrue(gate.accept(alice, 1020))
        assertFalse(gate.accept(alice, 1000), "reordered")
        // same millisecond (two frames encoded back to back) is not a reorder
        assertTrue(gate.accept(alice, 1020))
        // other speakers and other message types are separate streams
        assertTrue(gate.accept(bob, 10))
        assertTrue(gate.accept(VoiceKcpProtocol.FreshnessGate.key(VoiceKcpProtocol.TYPE_RADIO_AUDIO_RELAY, 1), 10))
        // a negative entity id must not collide with the type bits
        assertTrue(VoiceKcpProtocol.FreshnessGate.key(1, -1) != VoiceKcpProtocol.FreshnessGate.key(2, -1))
    }

    @Test
    fun `audio messages expose their send time for the gate`() {
        val before = System.currentTimeMillis()
        val message = VoiceKcpProtocol.encodeVoiceRelay(42, byteArrayOf(9))
        val sentAt = VoiceKcpProtocol.sentAtMs(message)!!
        assertTrue(sentAt >= before)
        assertEquals(42, VoiceKcpProtocol.decodeVoiceRelay(message).senderEntityId)
    }

    @Test
    fun `asset chunks round trip and refuse nonsense`() {
        val data = ByteArray(VoiceKcpProtocol.ASSET_CHUNK_BYTES) { it.toByte() }
        val chunk = VoiceKcpProtocol.AssetChunk(2, 7, 3, 10, "ogg", data)
        val decoded = VoiceKcpProtocol.decodeAssetChunk(VoiceKcpProtocol.encodeAssetChunk(chunk))
        assertEquals(2, decoded.kind)
        assertEquals(7, decoded.transferId)
        assertEquals(3, decoded.chunkIndex)
        assertEquals(10, decoded.totalChunks)
        assertEquals("ogg", decoded.extension)
        assertContentEquals(data, decoded.data)
        // one chunk is one reliable message the receiver can reassemble
        assertTrue(VoiceKcpProtocol.encodeAssetChunk(chunk).size <= VoiceKcpProtocol.MAX_RELIABLE_MESSAGE_BYTES)

        val bad = VoiceKcpProtocol.encodeAssetChunk(VoiceKcpProtocol.AssetChunk(2, 7, 0, 1, "", byteArrayOf(1)))
        bad[9] = 5 // chunk index (bytes 6..9, big-endian) 5 of 1
        assertFailsWith<Exception> { VoiceKcpProtocol.decodeAssetChunk(bad) }
    }

    @Test
    fun `reliable messages fit DncityKcp's receive window`() {
        // kRcvWnd 128 segments of kMss 1176 bytes; a bigger message never reassembles
        assertTrue(VoiceKcpProtocol.MAX_RELIABLE_MESSAGE_BYTES <= 128 * 1176)
        assertFailsWith<IllegalArgumentException> { VoiceKcpProtocol.encodeLodData(ByteArray(VoiceKcpProtocol.MAX_LOD_PAYLOAD_BYTES + 1)) }
        VoiceKcpProtocol.decodeLodData(VoiceKcpProtocol.encodeLodData(ByteArray(VoiceKcpProtocol.MAX_LOD_PAYLOAD_BYTES)))
    }
}
