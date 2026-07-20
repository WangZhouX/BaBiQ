package com.wzx.huitai.desktop.runtime

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BusinessAttachmentIdFactoryTest {
    @Test
    fun `creates canonical UUID and safe display id`() {
        val factory = BusinessAttachmentIdFactory()

        val identity = factory.create()

        assertEquals(identity.id, UUID.fromString(identity.id).toString())
        assertTrue(Regex("^A-[A-HJ-NP-Z2-9]{6}$").matches(identity.displayId))
    }

    @Test
    fun `retries both UUID and display id collisions from draft and current thread`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val third = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val supplied = ArrayDeque(listOf(first, second, third))
        val encoded = mapOf(
            first to "A-BCDEFG",
            second to "A-BCDEFG",
            third to "A-HJKLMN",
        )
        val factory = BusinessAttachmentIdFactory(
            uuidSource = { supplied.removeFirst() },
            displayIdEncoder = encoded::getValue,
        )

        val identity = factory.create(
            existingIds = setOf(first.toString()),
            existingDisplayIds = setOf("A-BCDEFG"),
        )

        assertEquals(third.toString(), identity.id)
        assertEquals("A-HJKLMN", identity.displayId)
        assertTrue(supplied.isEmpty())
    }

    @Test
    fun `fails safely when the collision retry budget is exhausted`() {
        val duplicate = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val factory = BusinessAttachmentIdFactory(
            uuidSource = { duplicate },
            displayIdEncoder = { "A-BCDEFG" },
            maximumAttempts = 2,
        )

        val failure = assertFailsWith<IllegalStateException> {
            factory.create(
                existingIds = setOf(duplicate.toString()),
                existingDisplayIds = setOf("A-BCDEFG"),
            )
        }

        assertEquals("Unable to allocate a unique attachment identifier", failure.message)
    }
}
