package dev.auden.dj

import dev.auden.dj.voice.FishVoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FishVoiceTest {
    private val key = "KEY"

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun theRequestIsAPostToTheEndpointWithTheKeyAndTheFreeModel() {
        val request = FishVoice.request("Hola", "es", FishVoice.WENDY, key, FishVoice.ENDPOINT)
        assertEquals("POST", request.method)
        assertEquals(FishVoice.ENDPOINT, request.url.toString())
        assertEquals("Bearer KEY", request.header("Authorization"))
        assertEquals("s2.1-pro-free", request.header("model"))
        assertEquals("application/json", request.body!!.contentType()!!.run { "$type/$subtype" })
    }

    @Test
    fun theBodyCarriesTheLineTheVoiceAndTheFormat() {
        val request = FishVoice.request("Good evening.", "en", FishVoice.CARA, key, FishVoice.ENDPOINT)
        val body = Json.parseToJsonElement(bodyOf(request)).jsonObject
        assertEquals("Good evening.", body["text"]!!.jsonPrimitive.content)
        assertEquals(FishVoice.CARA.referenceId, body["reference_id"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["format"]!!.jsonPrimitive.content)
        assertEquals(24_000, body["sample_rate"]!!.jsonPrimitive.int)
        assertEquals("en", body["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun eachLanguageHasItsVoice() {
        assertEquals("3de8512d76484ddb8f91593c4cfbbc42", FishVoice.voiceFor("en")!!.referenceId)
        assertEquals(FishVoice.CARA.referenceId, FishVoice.voiceFor("en")!!.referenceId)
        assertEquals("1060f143d4444d9b8cc8ab45e7f6520e", FishVoice.voiceFor("es")!!.referenceId)
        assertEquals(FishVoice.WENDY.referenceId, FishVoice.voiceFor("es")!!.referenceId)
        assertNull(FishVoice.voiceFor("fr"))
    }

    @Test
    fun pcmIsDecodedAsLittleEndianSixteenBitSamples() {
        val samples = FishVoice.decode(byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0x7F))
        assertEquals(2, samples.size)
        assertEquals(0.5f, samples[0], 1e-4f)
        assertEquals(-0.5f, samples[1], 1e-4f)
    }

    @Test
    fun noBytesDecodeToNoSamples() {
        assertEquals(0, FishVoice.decode(ByteArray(0)).size)
    }

    @Test
    fun withoutAKeyTheVoiceIsNotConfiguredAndSpeakingFails() {
        val voice = FishVoice(apiKey = "")
        assertFalse(voice.configured)
        val e = assertThrows(FishVoice.FishException::class.java) { voice.say("en", "hi") }
        assertEquals("no_key", e.message)
    }
}
