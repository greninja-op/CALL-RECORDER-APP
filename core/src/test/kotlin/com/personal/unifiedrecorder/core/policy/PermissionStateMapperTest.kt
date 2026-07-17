package com.personal.unifiedrecorder.core.policy

import com.personal.unifiedrecorder.core.model.PermissionDisplay
import com.personal.unifiedrecorder.core.model.RuntimePermission
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.subsequence
import io.kotest.property.checkAll

class PermissionStateMapperTest : StringSpec({

    val allPerms: List<RuntimePermission> = RuntimePermission.entries.toList()
    val permSubsetArb: Arb<Set<RuntimePermission>> = Arb.subsequence(allPerms).map { it.toSet() }
    val apiArb: Arb<Int> = Arb.int(29..40)

    fun expectedRequired(api: Int): Set<RuntimePermission> {
        val base = setOf(
            RuntimePermission.RECORD_AUDIO,
            RuntimePermission.READ_PHONE_STATE,
            RuntimePermission.MODIFY_AUDIO_SETTINGS
        )
        return if (api >= 33) base + RuntimePermission.POST_NOTIFICATIONS else base
    }

    // Feature: unified-call-recorder, Property 36: Required-permission request set
    // Validates: Requirements 8.1
    "Property 36: request set equals required-for-API minus granted" {
        checkAll(PropTestConfig(iterations = 100), apiArb, permSubsetArb) { api, granted ->
            val required = expectedRequired(api)
            val state = PermissionStateMapper.map(api, granted)
            state.requestSet shouldBe (required - granted)
        }
    }

    // Feature: unified-call-recorder, Property 37: Denied-permission messaging names exactly the denied permissions
    // Validates: Requirements 8.3
    "Property 37: denied messages name exactly the denied required permissions" {
        checkAll(PropTestConfig(iterations = 100), apiArb, permSubsetArb) { api, granted ->
            val required = expectedRequired(api)
            val expectedDenied = required - granted
            val state = PermissionStateMapper.map(api, granted)

            state.deniedPermissions shouldBe expectedDenied
            // One message per denied permission, and each denied permission is named by exactly one message.
            state.deniedMessages.size shouldBe expectedDenied.size
            expectedDenied.forEach { denied ->
                state.deniedMessages.count { it.contains(denied.name) } shouldBe 1
            }
            // No message names a permission that is not denied.
            required.filter { it !in expectedDenied }.forEach { notDenied ->
                state.deniedMessages.none { it.contains(notDenied.name) }.shouldBeTrue()
            }
        }
    }

    // Feature: unified-call-recorder, Property 38: Permanently-denied triggers a settings directive regardless of others
    // Validates: Requirements 8.4
    "Property 38: any permanently-denied required permission yields a settings directive" {
        checkAll(
            PropTestConfig(iterations = 100),
            apiArb,
            permSubsetArb,
            permSubsetArb
        ) { api, granted, permanentlyDenied ->
            val required = expectedRequired(api)
            val state = PermissionStateMapper.map(api, granted, permanentlyDenied)
            val expected = required.any { it in permanentlyDenied }
            state.settingsDirective shouldBe expected
        }
    }

    // Feature: unified-call-recorder, Property 39: Permission status is a complete binary mapping
    // Validates: Requirements 8.5
    "Property 39: status map covers exactly the required set with binary GRANTED/NOT_GRANTED values" {
        checkAll(PropTestConfig(iterations = 100), apiArb, permSubsetArb) { api, granted ->
            val required = expectedRequired(api)
            val state = PermissionStateMapper.map(api, granted)

            state.statusMap.keys shouldContainExactlyInAnyOrder required
            required.forEach { perm ->
                val expectedDisplay =
                    if (perm in granted) PermissionDisplay.GRANTED else PermissionDisplay.NOT_GRANTED
                state.statusMap[perm] shouldBe expectedDisplay
            }
            // Every value is exactly one of the two allowed display states.
            state.statusMap.values.all {
                it == PermissionDisplay.GRANTED || it == PermissionDisplay.NOT_GRANTED
            }.shouldBeTrue()
        }
    }
})
