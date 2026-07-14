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
}
