package com.personal.unifiedrecorder.core.logic

import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ParsedFilename
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeIn
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property-based test for [FilenameCodec] (Property 20).
 * Generators/helpers are private to this file; no shared test utilities.
 */
class FilenameCodecPropertyTest : FunSpec({

    val config = PropTestConfig(iterations = 100)

    // ---- Private generators / helpers ---------------------------------------

    val arbParsed: Arb<ParsedFilename> = arbitrary {
        ParsedFilename(
            callType = Arb.enum<CallType>().bind(),
            direction = Arb.enum<Direction>().bind(),
            year = Arb.int(0..9999).bind(),
            month = Arb.int(1..12).bind(),
            day = Arb.int(1..31).bind(),
            hour = Arb.int(0..23).bind(),
            minute = Arb.int(0..59).bind(),
            second = Arb.int(0..59).bind(),
            collisionSuffix = null,
        )
    }

    // Insert a numeric collision suffix before the extension, matching the codec's format.
    fun suffixed(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        val stem = name.substring(0, dot)
        val ext = name.substring(dot + 1)
        return "${stem}_$n.$ext"
    }

    // ---- Property ------------------------------------------------------------

    test("Property 20: filename round-trip and uniqueness") {
        // Feature: unified-call-recorder, Property 20: Filename round-trip and uniqueness
        // Validates: Requirements 6.2, 7.5
        checkAll(config, arbParsed, Arb.enum<AudioFormat>(), Arb.int(0..4)) { parsed, format, collisions ->
            // Round-trip: parse(encode(x)) yields an equivalent value (no suffix, empty store).
            val encoded = FilenameCodec.encode(parsed, format, emptySet())
            FilenameCodec.parse(encoded) shouldBe parsed

            // Uniqueness: against a set that already occupies the base name and some suffixes,
            // the encoder produces a name that is not already present.
            val existing = buildSet {
                add(encoded)
                for (n in 1..collisions) add(suffixed(encoded, n))
            }
            val unique = FilenameCodec.encode(parsed, format, existing)
            unique shouldNotBeIn existing
            FilenameCodec.parse(unique).shouldNotBeNull()
        }
    }
})
