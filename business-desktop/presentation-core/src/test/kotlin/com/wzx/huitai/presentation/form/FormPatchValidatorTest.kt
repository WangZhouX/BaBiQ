package com.wzx.huitai.presentation.form

import com.wzx.huitai.presentation.context.FieldContext
import com.wzx.huitai.presentation.context.FieldSensitivity
import com.wzx.huitai.presentation.context.PageContextSnapshot
import com.wzx.huitai.presentation.context.PageMode
import com.wzx.huitai.presentation.context.ValidationSummary
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class FormPatchValidatorTest {
    @Test
    fun `page mismatch rejects without exposing an applicable patch`() {
        val validator = validator()

        val result = validator.validate(
            patch = patch(pageId = "other-page"),
            snapshot = snapshot(),
            permissions = setOf("form.write"),
        )

        assertRejected(result, FormPatchErrorCode.PAGE_MISMATCH)
    }

    @Test
    fun `revision mismatch rejects without exposing an applicable patch`() {
        val validator = validator()

        val result = validator.validate(
            patch = patch(baseRevision = 6),
            snapshot = snapshot(revision = 7),
            permissions = setOf("form.write"),
        )

        assertRejected(result, FormPatchErrorCode.CONTEXT_STALE)
    }

    @Test
    fun `missing required permission rejects the field`() {
        val result = validator().validate(
            patch = patch(),
            snapshot = snapshot(),
            permissions = emptySet(),
        )

        val rejection = assertRejected(result, FormPatchErrorCode.MISSING_PERMISSION)
        assertEquals("name", rejection.errors.single().fieldId)
    }

    @Test
    fun `read only snapshot field rejects the change`() {
        val result = validator().validate(
            patch = patch(),
            snapshot = snapshot(editable = false),
            permissions = setOf("form.write"),
        )

        val rejection = assertRejected(result, FormPatchErrorCode.FIELD_READ_ONLY)
        assertEquals("name", rejection.errors.single().fieldId)
    }

    @Test
    fun `previous value mismatch rejects and diagnostics never contain raw values`() {
        val change = change(
            previousValue = "stale-secret",
            newValue = "new-secret",
            reason = "copied from raw-secret",
        )
        val patch = patch(change = change)

        val result = validator().validate(
            patch = patch,
            snapshot = snapshot(value = "current-secret"),
            permissions = setOf("form.write"),
        )

        val rejection = assertRejected(result, FormPatchErrorCode.PREVIOUS_VALUE_MISMATCH)
        val diagnostics = listOf(change.toString(), patch.toString(), rejection.toString())
        listOf("stale-secret", "new-secret", "current-secret", "raw-secret").forEach { raw ->
            assertTrue(diagnostics.none { raw in it })
        }
    }

    @Test
    fun `kotlin null and json null are equivalent previous values`() {
        val optionalSnapshot = typedSnapshot().copy(
            fields = typedSnapshot().fields.map { field ->
                if (field.id == "notes") field.copy(value = null) else field
            },
        )

        val result = typedValidator().validate(
            patch = patch(
                changes = listOf(
                    FieldChange(
                        fieldId = "notes",
                        previousValue = JsonNull,
                        newValue = JsonPrimitive("new notes"),
                        reason = "user supplied source",
                        confidence = 0.9,
                    ),
                ),
            ),
            snapshot = optionalSnapshot,
            permissions = setOf("form.write"),
        )

        assertIs<FormPatchValidationResult.Applicable>(result)
    }

    @Test
    fun `unknown and duplicate fields are rejected`() {
        val unknownResult = validator().validate(
            patch = patch(change = change(fieldId = "unknown")),
            snapshot = snapshot(),
            permissions = setOf("form.write"),
        )
        val duplicateResult = validator().validate(
            patch = patch(changes = listOf(change(), change(newValue = "another"))),
            snapshot = snapshot(),
            permissions = setOf("form.write"),
        )

        assertRejected(unknownResult, FormPatchErrorCode.UNKNOWN_FIELD)
        assertRejected(duplicateResult, FormPatchErrorCode.DUPLICATE_FIELD)
    }

    @Test
    fun type_validation_accepts_all_supported_types_and_preserves_metadata() {
        val source = SourceReference(type = "external-record", id = "source-9", label = "Section 2")
        val changes = listOf(
            change(fieldId = "title", previousValue = "old", newValue = "new", sourceReferences = listOf(source)),
            change(fieldId = "notes", previousValue = "line 1", newValue = "line 1\nline 2"),
            change(fieldId = "amount", previousValue = "1.00", newValue = "1234567890.123456789"),
            change(fieldId = "effectiveDate", previousValue = "2026-07-13", newValue = "2026-07-14"),
            change(fieldId = "status", previousValue = "draft", newValue = "active"),
        )
        val patch = patch(changes = changes)
        val result = typedValidator().validate(
            patch = patch,
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        val applicable = assertIs<FormPatchValidationResult.Applicable>(result)
        assertEquals(patch, applicable.patch)
        assertEquals("user supplied source", applicable.patch.changes.first().reason)
        assertEquals(0.9, applicable.patch.changes.first().confidence)
        assertEquals(listOf(source), applicable.patch.changes.first().sourceReferences)
    }

    @Test
    fun type_validation_rejects_invalid_decimal_enum_date_and_json_shapes() {
        val invalidChanges = listOf(
            change(fieldId = "amount", previousValue = "1.00", newValue = "NaN"),
            change(fieldId = "status", previousValue = "draft", newValue = "unknown"),
            change(fieldId = "effectiveDate", previousValue = "2026-07-13", newValue = "2026-02-30"),
            changeElement(fieldId = "title", previousValue = "old", newValue = JsonPrimitive(true)),
        )

        val result = typedValidator().validate(
            patch = patch(changes = invalidChanges),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        val rejection = assertIs<FormPatchValidationResult.Rejected>(result)
        assertEquals(
            setOf("amount", "status", "effectiveDate", "title"),
            rejection.errors.filter { it.code == FormPatchErrorCode.INVALID_FIELD_VALUE }.mapNotNull { it.fieldId }.toSet(),
        )
    }

    @Test
    fun type_validation_rejects_boolean_object_and_array_decimal_values() {
        listOf(
            JsonPrimitive(true),
            buildJsonObject { put("amount", "1.00") },
            buildJsonArray { add(JsonPrimitive("1.00")) },
        ).forEach { invalidValue ->
            val result = typedValidator().validate(
                patch = patch(
                    changes = listOf(
                        changeElement(fieldId = "amount", previousValue = "1.00", newValue = invalidValue),
                    ),
                ),
                snapshot = typedSnapshot(),
                permissions = setOf("form.write"),
            )

            assertRejected(result, FormPatchErrorCode.INVALID_FIELD_VALUE)
        }
    }

    @Test
    fun type_validation_treats_empty_string_as_present_but_rejects_required_null() {
        val emptyStringResult = typedValidator().validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = ""))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )
        val requiredNullResult = typedValidator().validate(
            patch = patch(changes = listOf(changeElement(fieldId = "title", previousValue = "old", newValue = JsonNull))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )
        val optionalNullResult = typedValidator().validate(
            patch = patch(changes = listOf(changeElement(fieldId = "notes", previousValue = "line 1", newValue = JsonNull))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        assertIs<FormPatchValidationResult.Applicable>(emptyStringResult)
        assertRejected(requiredNullResult, FormPatchErrorCode.REQUIRED_VALUE_MISSING)
        assertIs<FormPatchValidationResult.Applicable>(optionalNullResult)
    }

    @Test
    fun type_validation_rejects_unknown_or_inconsistent_snapshot_type() {
        val unknownType = typedSnapshot().copy(
            fields = typedSnapshot().fields.map { field ->
                if (field.id == "title") field.copy(type = "money") else field
            },
        )

        val result = typedValidator().validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "new"))),
            snapshot = unknownType,
            permissions = setOf("form.write"),
        )

        assertRejected(result, FormPatchErrorCode.FIELD_TYPE_MISMATCH)
    }

    @Test
    fun business_rule_receives_complete_candidate_values_and_aggregates_all_violations() {
        val observedCandidates = mutableListOf<Map<String, kotlinx.serialization.json.JsonElement?>>()
        val firstRule = FormBusinessRule { context ->
            observedCandidates += context.candidateValues
            if (context.candidateValues.getValue("status") == JsonPrimitive("active") &&
                context.candidateValues.getValue("amount") == JsonPrimitive("0")
            ) {
                listOf(FormBusinessRuleViolation(ruleId = "active-amount", fieldId = "amount"))
            } else {
                emptyList()
            }
        }
        val secondRule = FormBusinessRule {
            listOf(FormBusinessRuleViolation(ruleId = "page-policy"))
        }
        val validator = typedValidator(rules = listOf(firstRule, secondRule))

        val result = validator.validate(
            patch = patch(
                changes = listOf(
                    change(fieldId = "status", previousValue = "draft", newValue = "active"),
                    change(fieldId = "amount", previousValue = "1.00", newValue = "0"),
                ),
            ),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        val rejection = assertIs<FormPatchValidationResult.Rejected>(result)
        assertEquals(setOf("active-amount", "page-policy"), rejection.errors.mapNotNull { it.ruleId }.toSet())
        assertEquals(JsonPrimitive("active"), observedCandidates.single().getValue("status"))
        assertEquals(JsonPrimitive("0"), observedCandidates.single().getValue("amount"))
        assertEquals(JsonPrimitive("line 1"), observedCandidates.single().getValue("notes"))
    }

    @Test
    fun `business rules run with structurally valid changes and aggregate structural errors`() {
        var observedCandidates: Map<String, kotlinx.serialization.json.JsonElement?> = emptyMap()
        val validator = typedValidator(
            rules = listOf(
                FormBusinessRule { context ->
                    observedCandidates = context.candidateValues
                    listOf(FormBusinessRuleViolation(ruleId = "combined-check", fieldId = "amount"))
                },
            ),
        )

        val result = validator.validate(
            patch = patch(
                changes = listOf(
                    changeElement(fieldId = "title", previousValue = "old", newValue = JsonPrimitive(true)),
                    change(fieldId = "amount", previousValue = "1.00", newValue = "2.00"),
                ),
            ),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        val rejection = assertIs<FormPatchValidationResult.Rejected>(result)
        assertTrue(rejection.errors.any { it.code == FormPatchErrorCode.INVALID_FIELD_VALUE && it.fieldId == "title" })
        assertTrue(rejection.errors.any { it.code == FormPatchErrorCode.BUSINESS_RULE_VIOLATION && it.ruleId == "combined-check" })
        assertEquals(JsonPrimitive("old"), observedCandidates.getValue("title"))
        assertEquals(JsonPrimitive("2.00"), observedCandidates.getValue("amount"))
    }

    @Test
    fun `business rule context diagnostics never contain candidate raw values`() {
        var diagnostic = ""
        val validator = typedValidator(
            rules = listOf(
                FormBusinessRule { context ->
                    diagnostic = context.toString()
                    emptyList()
                },
            ),
        )

        validator.validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "raw-secret"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        assertFalse("raw-secret" in diagnostic)
        assertFalse("line 1" in diagnostic)
    }

    @Test
    fun `business rule failures are sanitized and do not prevent later rules`() {
        val validator = typedValidator(
            rules = listOf(
                FormBusinessRule { throw IllegalStateException("raw-secret") },
                FormBusinessRule {
                    listOf(FormBusinessRuleViolation(ruleId = "raw-secret", fieldId = "title"))
                },
            ),
        )

        val result = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "raw-secret"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        val rejection = assertIs<FormPatchValidationResult.Rejected>(result)
        assertTrue(rejection.errors.any { it.code == FormPatchErrorCode.BUSINESS_RULE_FAILURE })
        assertTrue(rejection.errors.any { it.code == FormPatchErrorCode.BUSINESS_RULE_VIOLATION })
        assertFalse("raw-secret" in rejection.toString())
    }

    @Test
    fun suggestion_state_replaces_by_field_removes_after_user_edit_and_builds_scoped_patches() {
        val first = change(fieldId = "title", previousValue = "old", newValue = "first")
        val replacement = change(fieldId = "title", previousValue = "old", newValue = "second")
        val amount = change(fieldId = "amount", previousValue = "1.00", newValue = "2.00")

        val state = SuggestionState(pageId = "test-page", baseRevision = 7)
            .withSuggestion(first)
            .withSuggestion(replacement)
            .withSuggestion(amount)

        assertEquals(listOf(replacement, amount), state.pendingChanges)
        assertEquals(listOf(replacement), state.patchFor("title")?.changes)
        assertEquals(listOf(replacement, amount), state.patchForAll()?.changes)
        assertNull(state.removeSuggestion("title").patchFor("title"))
    }

    @Test
    fun suggestion_state_and_confidence_reject_ambiguous_invalid_construction() {
        assertFailsWith<IllegalArgumentException> {
            SuggestionState(
                pageId = "test-page",
                baseRevision = 7,
                pendingChanges = listOf(change(), change(newValue = "duplicate")),
            )
        }
        assertFailsWith<IllegalArgumentException> { change(confidence = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { change(confidence = 1.1) }
    }

    @Test
    fun `suggestion state snapshots caller collections`() {
        val callerOwned = mutableListOf(change(fieldId = "title"))
        val state = SuggestionState(pageId = "test-page", baseRevision = 7, pendingChanges = callerOwned)

        callerOwned += change(fieldId = "amount")

        assertEquals(listOf("title"), state.pendingChanges.map(FieldChange::fieldId))
    }

    @Test
    fun `applicable patch deeply freezes caller owned changes references and json backings`() {
        val previousBacking = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "value" to JsonPrimitive("before"),
        )
        val sourceBacking = mutableListOf(SourceReference(type = "user-input", id = "source-1"))
        val arrayPreviousBacking = mutableListOf<kotlinx.serialization.json.JsonElement>(JsonPrimitive("array-before"))
        val changesBacking = mutableListOf(
            FieldChange(
                fieldId = "payloadObject",
                previousValue = JsonObject(previousBacking),
                newValue = JsonPrimitive("after-object"),
                reason = "freeze",
                confidence = 0.8,
                sourceReferences = sourceBacking,
            ),
            FieldChange(
                fieldId = "payloadArray",
                previousValue = JsonArray(arrayPreviousBacking),
                newValue = JsonPrimitive("after-array"),
                reason = "freeze",
                confidence = 0.8,
                sourceReferences = sourceBacking,
            ),
        )
        val patch = FormPatch(pageId = "freeze-page", baseRevision = 3, changes = changesBacking)
        val snapshot = freezeSnapshot(
            objectValue = JsonObject(previousBacking),
            arrayValue = JsonArray(arrayPreviousBacking),
        )
        val validator = FormPatchValidator(
            definitions = listOf(
                FormFieldDefinition("payloadObject", FormFieldType.STRING),
                FormFieldDefinition("payloadArray", FormFieldType.STRING),
            ),
        )

        val applicable = assertIs<FormPatchValidationResult.Applicable>(
            validator.validate(patch, snapshot, emptySet()),
        )
        changesBacking += change(fieldId = "unknown")
        changesBacking += change(fieldId = "payloadObject")
        sourceBacking += SourceReference(type = "external-record", id = "source-2")
        previousBacking["value"] = JsonPrimitive("mutated-before")
        arrayPreviousBacking += JsonPrimitive("mutated-array")

        assertEquals(2, applicable.patch.changes.size)
        assertEquals(listOf(SourceReference(type = "user-input", id = "source-1")), applicable.patch.changes.first().sourceReferences)
        assertEquals(JsonObject(mapOf("value" to JsonPrimitive("before"))), applicable.patch.changes.first().previousValue)
        assertEquals(JsonArray(listOf(JsonPrimitive("array-before"))), applicable.patch.changes.last().previousValue)
    }

    @Test
    fun `canonical patch deeply freezes object and array new value backings`() {
        val objectBacking = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "value" to JsonPrimitive("object-new"),
        )
        val arrayBacking = mutableListOf<kotlinx.serialization.json.JsonElement>(JsonPrimitive("array-new"))
        val patch = FormPatch(
            pageId = "freeze-page",
            baseRevision = 3,
            changes = listOf(
                FieldChange("object", JsonNull, JsonObject(objectBacking), "freeze", 0.8),
                FieldChange("array", JsonNull, JsonArray(arrayBacking), "freeze", 0.8),
            ),
        )

        val canonical = requireNotNull(canonicalizePatch(patch))
        objectBacking["value"] = JsonPrimitive("mutated-object")
        arrayBacking += JsonPrimitive("mutated-array")

        assertEquals(JsonObject(mapOf("value" to JsonPrimitive("object-new"))), canonical.changes.first().newValue)
        assertEquals(JsonArray(listOf(JsonPrimitive("array-new"))), canonical.changes.last().newValue)
    }

    @Test
    fun `suggestion state deeply freezes field changes on construction and replacement`() {
        val firstSourceBacking = mutableListOf(SourceReference(type = "user-input", id = "source-1"))
        val firstJsonBacking = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "value" to JsonPrimitive("first"),
        )
        val first = FieldChange(
            fieldId = "payload",
            previousValue = JsonNull,
            newValue = JsonObject(firstJsonBacking),
            reason = "first",
            confidence = 0.7,
            sourceReferences = firstSourceBacking,
        )
        val state = SuggestionState(pageId = "freeze-page", baseRevision = 3, pendingChanges = listOf(first))

        firstSourceBacking += SourceReference(type = "external-record", id = "source-2")
        firstJsonBacking["value"] = JsonPrimitive("mutated-first")

        val replacementSourceBacking = mutableListOf(SourceReference(type = "user-input", id = "source-3"))
        val replacementJsonBacking = mutableListOf<kotlinx.serialization.json.JsonElement>(JsonPrimitive("replacement"))
        val replacement = FieldChange(
            fieldId = "payload",
            previousValue = JsonNull,
            newValue = JsonArray(replacementJsonBacking),
            reason = "replacement",
            confidence = 0.9,
            sourceReferences = replacementSourceBacking,
        )
        val replaced = state.withSuggestion(replacement)

        replacementSourceBacking += SourceReference(type = "external-record", id = "source-4")
        replacementJsonBacking += JsonPrimitive("mutated-replacement")

        assertEquals(JsonObject(mapOf("value" to JsonPrimitive("first"))), state.patchForAll()?.changes?.single()?.newValue)
        assertEquals(1, state.pendingChanges.single().sourceReferences.size)
        assertEquals(JsonArray(listOf(JsonPrimitive("replacement"))), replaced.patchForAll()?.changes?.single()?.newValue)
        assertEquals(1, replaced.pendingChanges.single().sourceReferences.size)
    }

    @Test
    fun `validator snapshots mutable definitions permissions enum values and rule list`() {
        val permissions = mutableSetOf("form.write")
        val enumValues = mutableSetOf("draft", "active")
        val definitions = mutableListOf(
            FormFieldDefinition(
                fieldId = "status",
                type = FormFieldType.ENUM,
                requiredPermissions = permissions,
                enumAllowedValues = enumValues,
            ),
        )
        val rules = mutableListOf<FormBusinessRule>()
        val validator = FormPatchValidator(definitions = definitions, businessRules = rules)

        permissions.clear()
        enumValues += "injected"
        definitions.clear()
        rules += FormBusinessRule {
            listOf(FormBusinessRuleViolation(ruleId = "late-rule", fieldId = "status"))
        }

        val missingPermission = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "status", previousValue = "draft", newValue = "active"))),
            snapshot = typedSnapshot(),
            permissions = emptySet(),
        )
        val injectedEnum = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "status", previousValue = "draft", newValue = "injected"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )
        val valid = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "status", previousValue = "draft", newValue = "active"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        assertRejected(missingPermission, FormPatchErrorCode.MISSING_PERMISSION)
        assertRejected(injectedEnum, FormPatchErrorCode.INVALID_FIELD_VALUE)
        assertIs<FormPatchValidationResult.Applicable>(valid)
    }

    @Test
    fun `applicable patch collections cannot be modified through jvm mutable casts`() {
        val result = typedValidator().validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "new"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )
        val applicable = assertIs<FormPatchValidationResult.Applicable>(result)
        val changes = applicable.patch.changes as MutableList<FieldChange>
        val sources = applicable.patch.changes.single().sourceReferences as MutableList<SourceReference>

        assertFailsWith<UnsupportedOperationException> { changes.clear() }
        assertFailsWith<UnsupportedOperationException> { changes += change(fieldId = "unknown") }
        assertFailsWith<UnsupportedOperationException> {
            sources += SourceReference(type = "user-input", id = "late-source")
        }
        assertEquals(1, applicable.patch.changes.size)
        assertEquals(1, applicable.patch.changes.single().sourceReferences.size)
        assertTrue(FormPatchValidationResult.Applicable::class.java.isInterface)
        assertTrue(FormPatchValidationResult.Applicable::class.java.declaredMethods.none { it.name == "copy" })
    }

    @Test
    fun `suggestion state pending changes cannot be modified through jvm mutable cast`() {
        val state = SuggestionState(
            pageId = "test-page",
            baseRevision = 7,
            pendingChanges = listOf(change()),
        )
        val pending = state.pendingChanges as MutableList<FieldChange>

        assertFailsWith<UnsupportedOperationException> { pending.clear() }
        assertFailsWith<UnsupportedOperationException> { pending += change(newValue = "late") }
        assertEquals(1, state.pendingChanges.size)
    }

    @Test
    fun `direct form models freeze caller collections at construction`() {
        val jsonBacking = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "nested" to JsonArray(mutableListOf(JsonPrimitive("before"))),
        )
        val sourceBacking = mutableListOf(SourceReference(type = "user-input", id = "source-1"))
        val change = FieldChange(
            fieldId = "title",
            previousValue = JsonPrimitive("old"),
            newValue = JsonObject(jsonBacking),
            reason = "reason",
            confidence = 0.8,
            sourceReferences = sourceBacking,
        )
        val changesBacking = mutableListOf(change)
        val patch = FormPatch(pageId = "test-page", baseRevision = 7, changes = changesBacking)

        sourceBacking += SourceReference(type = "user-input", id = "source-2")
        changesBacking += change(fieldId = "status")
        jsonBacking["nested"] = JsonPrimitive("mutated")

        assertEquals(1, change.sourceReferences.size)
        assertEquals(1, patch.changes.size)
        assertTrue("before" in change.newValue.toString())
        assertFailsWith<UnsupportedOperationException> {
            (change.sourceReferences as MutableList).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (patch.changes as MutableList).clear()
        }
    }

    @Test
    fun `rejected errors cannot be modified through jvm mutable cast`() {
        val rejected = assertIs<FormPatchValidationResult.Rejected>(
            typedValidator().validate(
                patch(
                    changes = listOf(
                        changeElement("title", "old", JsonPrimitive(true)),
                        change("status", "draft", "unknown"),
                    ),
                ),
                typedSnapshot(),
                setOf("form.write"),
            ),
        )
        val errors = rejected.errors as MutableList<FormPatchError>
        val originalSize = rejected.errors.size

        assertFailsWith<UnsupportedOperationException> { errors.clear() }
        assertFailsWith<UnsupportedOperationException> {
            errors += FormPatchError(FormPatchErrorCode.INVALID_PATCH_ENCODING)
        }
        assertEquals(originalSize, rejected.errors.size)
    }

    @Test
    fun `rules cannot mutate candidate definitions or definition sets`() {
        val observations = mutableListOf<String>()
        val mutatingRule = FormBusinessRule { context ->
            assertFailsWith<UnsupportedOperationException> {
                (context.candidateValues as MutableMap).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (context.definitions as MutableMap).clear()
            }
            val definition = context.definitions.getValue("status")
            assertFailsWith<UnsupportedOperationException> {
                (definition.requiredPermissions as MutableSet).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (definition.enumAllowedValues as MutableSet).add("injected")
            }
            emptyList()
        }
        val observingRule = FormBusinessRule { context ->
            observations += context.candidateValues.getValue("status").toString()
            observations += context.definitions.getValue("status").requiredPermissions.single()
            observations += context.definitions.getValue("status").enumAllowedValues.sorted().joinToString()
            emptyList()
        }
        val validator = typedValidator(rules = listOf(mutatingRule, observingRule))

        val first = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "status", previousValue = "draft", newValue = "active"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )
        val second = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "status", previousValue = "draft", newValue = "active"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        assertIs<FormPatchValidationResult.Applicable>(first)
        assertIs<FormPatchValidationResult.Applicable>(second)
        assertEquals(listOf("\"active\"", "form.write", "active, draft", "\"active\"", "form.write", "active, draft"), observations)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `rule candidate json backings cannot mutate later rules or later validations`() {
        val snapshotBacking = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "nested" to JsonArray(mutableListOf(JsonPrimitive("before"))),
        )
        val observed = mutableListOf<String>()
        val mutatingRule = FormBusinessRule { context ->
            val candidate = context.candidateValues.getValue("payloadObject") as JsonObject
            assertMutationBlocked { (candidate as Any as MutableMap<String, kotlinx.serialization.json.JsonElement>).clear() }
            assertMutationBlocked { (candidate.entries as MutableSet).clear() }
            assertMutationBlocked {
                (candidate.entries.single() as MutableMap.MutableEntry).setValue(JsonPrimitive("mutated"))
            }
            val nested = candidate.getValue("nested") as JsonArray
            assertMutationBlocked { (nested as Any as MutableList<kotlinx.serialization.json.JsonElement>).clear() }
            assertMutationBlocked { (nested.iterator() as MutableIterator).remove() }
            emptyList()
        }
        val observingRule = FormBusinessRule { context ->
            observed += context.candidateValues.getValue("payloadObject").toString()
            emptyList()
        }
        val validator = FormPatchValidator(
            definitions = listOf(
                FormFieldDefinition("payloadObject", FormFieldType.STRING),
                FormFieldDefinition("trigger", FormFieldType.STRING),
            ),
            businessRules = listOf(mutatingRule, observingRule),
        )
        val snapshot = freezeSnapshot(
            objectValue = JsonObject(snapshotBacking),
            arrayValue = JsonArray(listOf(JsonPrimitive("unused"))),
        ).copy(
            fields = listOf(
                freezeSnapshot(
                    objectValue = JsonObject(snapshotBacking),
                    arrayValue = JsonArray(listOf(JsonPrimitive("unused"))),
                ).fields.first(),
                FieldContext(
                    id = "trigger",
                    label = "Trigger",
                    type = "string",
                    value = JsonPrimitive("before"),
                    editable = true,
                    required = false,
                    sensitivity = FieldSensitivity.INTERNAL,
                ),
            ),
        )
        val patch = FormPatch(
            pageId = "freeze-page",
            baseRevision = 3,
            changes = listOf(FieldChange("trigger", JsonPrimitive("before"), JsonPrimitive("after"), "freeze", 0.8)),
        )

        assertIs<FormPatchValidationResult.Applicable>(validator.validate(patch, snapshot, emptySet()))
        assertIs<FormPatchValidationResult.Applicable>(validator.validate(patch, snapshot, emptySet()))
        assertEquals(2, observed.size)
        assertTrue(observed.all { "before" in it })
    }

    @Test
    fun `duplicate or blank snapshot field ids reject invalid page context`() {
        val duplicateSnapshot = typedSnapshot().copy(
            fields = typedSnapshot().fields + typedSnapshot().fields.first().copy(label = "Duplicate"),
        )
        val blankSnapshot = typedSnapshot().copy(
            fields = typedSnapshot().fields.mapIndexed { index, field ->
                if (index == 0) field.copy(id = "  ") else field
            },
        )

        val duplicateResult = typedValidator().validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "new"))),
            snapshot = duplicateSnapshot,
            permissions = setOf("form.write"),
        )
        val blankResult = typedValidator().validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "new"))),
            snapshot = blankSnapshot,
            permissions = setOf("form.write"),
        )

        assertRejected(duplicateResult, FormPatchErrorCode.INVALID_PAGE_CONTEXT)
        assertRejected(blankResult, FormPatchErrorCode.INVALID_PAGE_CONTEXT)
    }

    @Test
    fun `model boundary rejects blank oversized and controlled identifiers without raw diagnostics`() {
        val controlled = "raw\u0000secret"
        val oversized = "x".repeat(257)

        listOf<() -> Unit>(
            { SourceReference(type = " ", id = "source") },
            { SourceReference(type = "user-input", id = controlled) },
            { SourceReference(type = oversized, id = "source") },
            { SourceReference(type = "user-input", id = "source", label = controlled) },
            { FieldChange(" ", JsonNull, JsonPrimitive("new"), "reason", 0.8) },
            { FieldChange("field", JsonNull, JsonPrimitive("new"), " ", 0.8) },
            { FieldChange(controlled, JsonNull, JsonPrimitive("new"), "reason", 0.8) },
            { FieldChange("field", JsonNull, JsonPrimitive("new"), controlled, 0.8) },
            { FieldChange("field", JsonNull, JsonPrimitive("new"), "x".repeat(4_001), 0.8) },
            { FormPatch(" ", 1, listOf(change())) },
            { FormPatch(controlled, 1, listOf(change())) },
            { FormPatch("page", 1, emptyList()) },
            { SuggestionState(" ", 1) },
        ).forEach { constructor ->
            val error = assertFailsWith<IllegalArgumentException> { constructor() }
            assertFalse(controlled in error.message.orEmpty())
            assertFalse(oversized in error.message.orEmpty())
        }

        val safeSource = SourceReference(type = "user-input", id = "raw-id", label = "raw-label")
        assertFalse("raw-id" in safeSource.toString())
        assertFalse("raw-label" in safeSource.toString())
        assertFalse("raw\u0000secret" in FormPatchError(FormPatchErrorCode.UNKNOWN_FIELD, controlled, controlled).toString())
    }

    @Test
    fun `stable identifiers allow common uuid dotted colon slash and dash forms`() {
        val stableId = "scope/123e4567-e89b-12d3-a456-426614174000:v1.field"
        val source = SourceReference(type = "external.record/v1", id = stableId)
        val change = FieldChange(stableId, JsonNull, JsonPrimitive("new"), "normal\nreason", 0.8, listOf(source))
        val patch = FormPatch("page/$stableId", 1, listOf(change))

        assertEquals(stableId, patch.changes.single().fieldId)
    }

    @Test
    fun `untrusted rule outputs become sanitized failures and later rules continue`() {
        val laterRuleRuns = mutableListOf<Boolean>()
        val invalidRules = listOf<FormBusinessRule>(
            FormBusinessRule { listOf(FormBusinessRuleViolation(ruleId = " ")) },
            FormBusinessRule { listOf(FormBusinessRuleViolation(ruleId = "x".repeat(257))) },
            FormBusinessRule { listOf(FormBusinessRuleViolation(ruleId = "raw\u0000secret")) },
            FormBusinessRule { listOf(FormBusinessRuleViolation(ruleId = "valid-rule", fieldId = "unknown-field")) },
        )
        val validator = typedValidator(
            rules = invalidRules + FormBusinessRule {
                laterRuleRuns += true
                emptyList()
            },
        )

        val result = validator.validate(
            patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "new"))),
            snapshot = typedSnapshot(),
            permissions = setOf("form.write"),
        )

        val rejection = assertIs<FormPatchValidationResult.Rejected>(result)
        assertEquals(4, rejection.errors.count { it.code == FormPatchErrorCode.BUSINESS_RULE_FAILURE })
        assertTrue(rejection.errors.none { it.ruleId != null || it.fieldId != null })
        assertEquals(listOf(true), laterRuleRuns)
        assertFalse("unknown-field" in rejection.toString())
        assertFalse("raw" in rejection.toString())
    }

    @Test
    fun `rule cancellation propagates instead of becoming a business rule failure`() {
        val validator = typedValidator(
            rules = listOf(FormBusinessRule { throw CancellationException("raw-secret") }),
        )

        val error = assertFailsWith<CancellationException> {
            validator.validate(
                patch = patch(changes = listOf(change(fieldId = "title", previousValue = "old", newValue = "new"))),
                snapshot = typedSnapshot(),
                permissions = setOf("form.write"),
            )
        }

        assertEquals("raw-secret", error.message)
    }

    private fun validator(): FormPatchValidator = FormPatchValidator(
        definitions = listOf(
            FormFieldDefinition(
                fieldId = "name",
                type = FormFieldType.STRING,
                requiredPermissions = setOf("form.write"),
            ),
        ),
    )

    private fun typedValidator(
        rules: List<FormBusinessRule> = emptyList(),
    ): FormPatchValidator = FormPatchValidator(
        definitions = listOf(
            FormFieldDefinition("title", FormFieldType.STRING, setOf("form.write")),
            FormFieldDefinition("notes", FormFieldType.MULTILINE, setOf("form.write")),
            FormFieldDefinition("amount", FormFieldType.DECIMAL, setOf("form.write")),
            FormFieldDefinition("effectiveDate", FormFieldType.DATE, setOf("form.write")),
            FormFieldDefinition(
                fieldId = "status",
                type = FormFieldType.ENUM,
                requiredPermissions = setOf("form.write"),
                enumAllowedValues = setOf("draft", "active"),
            ),
        ),
        businessRules = rules,
    )

    private fun patch(
        pageId: String = "test-page",
        baseRevision: Long = 7,
        change: FieldChange = change(),
        changes: List<FieldChange> = listOf(change),
    ): FormPatch = FormPatch(
        pageId = pageId,
        baseRevision = baseRevision,
        changes = changes,
    )

    private fun change(
        fieldId: String = "name",
        previousValue: String = "before",
        newValue: String = "after",
        reason: String = "user supplied source",
        confidence: Double = 0.9,
        sourceReferences: List<SourceReference> = listOf(SourceReference(type = "user-input", id = "source-1")),
    ): FieldChange = changeElement(
        fieldId = fieldId,
        previousValue = previousValue,
        newValue = JsonPrimitive(newValue),
        reason = reason,
        confidence = confidence,
        sourceReferences = sourceReferences,
    )

    private fun changeElement(
        fieldId: String,
        previousValue: String,
        newValue: kotlinx.serialization.json.JsonElement?,
        reason: String = "user supplied source",
        confidence: Double = 0.9,
        sourceReferences: List<SourceReference> = listOf(SourceReference(type = "user-input", id = "source-1")),
    ): FieldChange = FieldChange(
        fieldId = fieldId,
        previousValue = JsonPrimitive(previousValue),
        newValue = newValue,
        reason = reason,
        confidence = confidence,
        sourceReferences = sourceReferences,
    )

    private fun snapshot(
        revision: Long = 7,
        editable: Boolean = true,
        value: String = "before",
    ): PageContextSnapshot = PageContextSnapshot(
        snapshotId = "snapshot-$revision",
        pageId = "test-page",
        pageTitle = "Test page",
        route = "/test",
        revision = revision,
        mode = PageMode.EDIT,
        fields = listOf(
            FieldContext(
                id = "name",
                label = "Name",
                type = "string",
                value = JsonPrimitive(value),
                editable = editable,
                required = true,
                sensitivity = FieldSensitivity.PUBLIC,
            ),
        ),
        validationSummary = ValidationSummary(valid = true),
    )

    private fun typedSnapshot(): PageContextSnapshot = PageContextSnapshot(
        snapshotId = "snapshot-7",
        pageId = "test-page",
        pageTitle = "Test page",
        route = "/test",
        revision = 7,
        mode = PageMode.EDIT,
        fields = listOf(
            field("title", "string", "old", required = true),
            field("notes", "multiline", "line 1", required = false),
            field("amount", "decimal", "1.00", required = true),
            field("effectiveDate", "date", "2026-07-13", required = true),
            field("status", "enum", "draft", required = true),
        ),
        validationSummary = ValidationSummary(valid = true),
    )

    private fun freezeSnapshot(
        objectValue: kotlinx.serialization.json.JsonElement,
        arrayValue: kotlinx.serialization.json.JsonElement,
    ): PageContextSnapshot = PageContextSnapshot(
        snapshotId = "snapshot-3",
        pageId = "freeze-page",
        pageTitle = "Freeze page",
        route = "/freeze",
        revision = 3,
        mode = PageMode.EDIT,
        fields = listOf(
            FieldContext(
                id = "payloadObject",
                label = "Payload object",
                type = "string",
                value = objectValue,
                editable = true,
                required = false,
                sensitivity = FieldSensitivity.INTERNAL,
            ),
            FieldContext(
                id = "payloadArray",
                label = "Payload array",
                type = "string",
                value = arrayValue,
                editable = true,
                required = false,
                sensitivity = FieldSensitivity.INTERNAL,
            ),
        ),
        validationSummary = ValidationSummary(valid = true),
    )

    private fun field(
        id: String,
        type: String,
        value: String,
        required: Boolean,
    ): FieldContext = FieldContext(
        id = id,
        label = id,
        type = type,
        value = JsonPrimitive(value),
        editable = true,
        required = required,
        sensitivity = FieldSensitivity.PUBLIC,
    )

    private fun assertRejected(
        result: FormPatchValidationResult,
        expectedCode: FormPatchErrorCode,
    ): FormPatchValidationResult.Rejected {
        assertFalse(result is FormPatchValidationResult.Applicable)
        val rejection = assertIs<FormPatchValidationResult.Rejected>(result)
        assertTrue(rejection.errors.any { it.code == expectedCode })
        return rejection
    }

    private fun assertMutationBlocked(block: () -> Unit) {
        try {
            block()
            throw AssertionError("mutation unexpectedly succeeded")
        } catch (_: UnsupportedOperationException) {
            // JVM unmodifiable collection wrapper.
        } catch (_: ClassCastException) {
            // JsonObject/JsonArray does not implement mutable collection interfaces.
        }
    }
}
