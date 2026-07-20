# Business Agent Local Attachments Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Codex-style local attachments to the business desktop Agent composer: file selection, Ctrl+V screenshot paste, stable attachment chips, direct local-path handoff, safe backend loading, bounded document extraction, image multimodal input, attachment metadata persistence, same-thread attachment references, and actionable errors.

**Architecture:** The Compose client owns only draft selection and clipboard image materialization. It sends compact attachment descriptors (`id`, `displayId`, `name`, `localPath`) over the existing JSON-RPC WebSocket. The backend is authoritative: it validates and fingerprints files before creating a Turn, persists metadata in `userMessage`, resolves exact same-thread attachment references, extracts bounded document text locally, and constructs Spring AI `Media` only at model-call time. Extracted content and Base64 never enter WebSocket payloads, logs, or SQLite.

**Tech Stack:** Kotlin/JVM, Compose Desktop, kotlinx.serialization JSON, Java 21, Spring Boot 3.5, Spring AI 1.1.6, Spring AI Alibaba ReactAgent 1.1.2.3, Apache Tika 3.2.3, JUnit 5, Mockito, AssertJ, Compose UI tests.

---

## Chunk 1: Protocol, desktop draft creation, and composer UI

### Task 1: Define attachment protocol models and round-trip metadata

**Files:**

- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessAttachmentModels.kt`
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClient.kt`
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessThreadModels.kt`
- Modify: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClientTest.kt`
- Modify: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessThreadModelsTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessDesktopCoordinatorTest.kt`

- [ ] Write failing tests proving:
  - `turn/start` accepts blank text when at least one attachment exists;
  - the request contains only `id`, `displayId`, `name`, and `localPath`, never file bytes or Base64;
  - blank text and an empty attachment list are rejected locally;
  - decoded `userMessage.attachments` preserves stable metadata;
  - a decoded attachment-only user message accepts blank `text`, while historical messages missing `attachments` normalize to an empty list;
  - model `toString()` methods never expose `localPath`.
- [ ] Run the focused red test:

  ```powershell
  cd business-desktop
  .\gradlew.bat :agent-client-core:test --tests "*BusinessAgentClientTest" --tests "*BusinessThreadModelsTest"
  .\gradlew.bat :app:test --tests "*BusinessDesktopCoordinatorTest"
  ```

  Expected: compilation or assertions fail because attachment models and request fields do not exist.

- [ ] Add:
  - `BusinessAttachmentDraft(id, displayId, name, localPath, sizeBytes, displayType)` with validation and redacted `toString()`; the last two fields remain desktop-only and are not serialized in `turn/start`;
  - `BusinessMessageAttachment(id, displayId, name, mediaType, sizeBytes, sha256, source, localPath)` with redacted `toString()`;
  - `BusinessConversationGateway.startTurn(threadId, text, attachments, providerId)`;
  - JSON array serialization under `input.attachments`;
  - `BusinessThreadItem.UserMessage.attachments`.
- [ ] Preserve source compatibility with an overload/default empty list for existing call sites, but enforce `text.isNotBlank() || attachments.isNotEmpty()`.
- [ ] Update every fake gateway, including `BusinessDesktopCoordinatorTest`, for the attachment-aware signature.
- [ ] Run the focused green test with the same command.
- [ ] Commit only Task 1 files:

  ```powershell
  git add business-desktop/agent-client-core
  git add -p business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessDesktopCoordinatorTest.kt
  git commit -m "feat(attachments): add business attachment protocol"
  ```

### Task 2: Add secure clipboard image materialization and file selection

**Files:**

- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePaths.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/ClipboardImageAttachmentStore.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAttachmentIdFactory.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAttachmentPicker.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentLaunchRequest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePathsTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/ClipboardImageAttachmentStoreTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAttachmentIdFactoryTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAttachmentPickerTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncherTest.kt`

- [ ] Write failing tests proving:
  - runtime paths expose `agentAttachmentRoot` and `agentClipboardAttachmentRoot`, both below the isolated Agent root and with no link traversal;
  - clipboard image writes use `attachment-<uuid>.tmp`, then atomically publish a PNG;
  - the final filename is `截图-yyyyMMdd-HHmmss-<six-character-suffix>.png` without the `A-` prefix;
  - a non-image clipboard returns `null` and does not consume ordinary text paste;
  - generated IDs are UUIDs and display IDs match `A-[A-HJ-NP-Z2-9]{6}`;
  - collision retry considers both current draft IDs and current-thread message attachments;
  - clipboard PNG dimensions stay within 16,384 per side and 50 megapixels, encoded bytes stay within 20 MiB, and `existing controlled bytes + encoded bytes` never exceeds 1 GiB;
  - every clipboard rejection deletes its temporary file;
  - file selection normalizes selected regular paths but does not read or encode file contents.
- [ ] Run the focused red test:

  ```powershell
  cd business-desktop
  .\gradlew.bat :app:test --tests "*BusinessDesktopRuntimePathsTest" --tests "*ClipboardImageAttachmentStoreTest" --tests "*BusinessAttachmentIdFactoryTest" --tests "*BusinessAttachmentPickerTest" --tests "*BusinessDesktopCompositionRootTest" --tests "*BusinessAgentProcessLauncherTest"
  ```

  Expected: compilation fails because the runtime paths and services do not exist.

- [ ] Extend `BusinessDesktopRuntimePaths` with:
  - `agentAttachmentRoot = agentRoot.resolve("attachments")`;
  - `agentClipboardAttachmentRoot = agentAttachmentRoot.resolve("clipboard")`;
  - controlled-directory creation, link rejection, real-path containment, and best-effort owner-only permissions.
- [ ] Implement one deterministic `BusinessAttachmentIdFactory` used by both picker and clipboard capture. It retries UUID/display-ID collisions against caller-supplied current-thread IDs.
- [ ] Implement `ClipboardImageAttachmentStore` behind injectable `ClipboardImageSource`, `Clock`, and ID-factory seams. Use `ImageIO`, a temporary sibling file, `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` with a safe non-atomic fallback, and return a `BusinessAttachmentDraft`.
- [ ] After encoding the clipboard image to its temporary PNG, validate dimensions, the 20 MiB file limit, and `existing controlled bytes + temporary size <= 1 GiB` without following links. On any rejection, delete the temporary file and return a safe capacity/type error instead of deleting referenced files.
- [ ] Implement `BusinessAttachmentPicker` behind an injectable chooser seam. Production uses `JFileChooser.FILES_ONLY`, multi-select, `isAcceptAllFileFilterUsed=false`, and the supported extension filter. It reads only basic metadata, enforces friendly desktop checks (supported extension, 20 MiB/file, 50 MiB total, max eight), and never reads/encodes file bodies.
- [ ] Expose the already-created controlled root and attachment services through `ProductionUiComponents`/`BusinessDesktopCompositionRoot`; `Main.kt` must consume these owned services and must not create a second runtime-path graph.
- [ ] Pass `--babiq.business.attachment-clipboard-root=${paths.agentClipboardAttachmentRoot}` in `BusinessAgentLaunchRequest`.
- [ ] Run the focused green test.
- [ ] Commit only Task 2 files, preserving pre-existing runtime-path edits:

  ```powershell
  git add -p business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePaths.kt
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/ClipboardImageAttachmentStore.kt
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAttachmentIdFactory.kt
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAttachmentPicker.kt
  git add -p business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentLaunchRequest.kt
  git add -p business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePathsTest.kt
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/ClipboardImageAttachmentStoreTest.kt
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAttachmentIdFactoryTest.kt
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAttachmentPickerTest.kt
  git add -p business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt
  git add -p business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncherTest.kt
  git commit -m "feat(attachments): create local attachment drafts"
  ```

### Task 3: Render attachment chips and preserve failed-send drafts

**Files:**

- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/AgentComposer.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanel.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessComposerSendCoordinator.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanelTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationControllerTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessComposerSendCoordinatorTest.kt`

- [ ] Write failing tests proving:
  - the paperclip action calls file selection;
  - each draft displays `displayId`, filename, and a remove action, but not `localPath`;
  - Ctrl+V consumes the event only when an image was captured; ordinary text paste remains available;
  - attachment-only sends are enabled;
  - duplicate paths and more than eight attachments are rejected with a safe visible message;
  - merged existing plus selected/pasted drafts cannot exceed eight attachments or 50 MiB total;
  - draft and persisted chips show stable ID, filename, display/media type, and formatted size;
  - successful `startTurn` clears text and attachments;
  - failed `startTurn` retains both text and attachments.
  - edits made while `turn/start` is pending are not erased when the captured submission succeeds.
  - picker/clipboard/count/size failures become safe visible attachment error codes/messages and never expose paths.
- [ ] Run the focused red tests:

  ```powershell
  cd business-desktop
  .\gradlew.bat :app:test --tests "*BusinessAgentPanelTest" --tests "*BusinessDesktopShellTest" --tests "*BusinessConversationControllerTest" --tests "*BusinessComposerSendCoordinatorTest"
  ```

  Expected: tests fail because composer callbacks, attachment chips, and attachment-aware send do not exist.

- [ ] Refactor `AgentComposer` to a vertical layout:
  - attachment `FlowRow` above the text field;
  - paperclip button tagged `agent-composer-attach`;
  - removable chips tagged `agent-attachment-<displayId>`;
  - `Modifier.onPreviewKeyEvent` handling exactly one `KeyEventType.KeyDown` Ctrl+V through `onPasteImage(): Boolean`, and returning `false` for non-image clipboard content so normal text paste continues;
  - send enabled when connected and `(text.isNotBlank() || attachments.isNotEmpty())`.
- [ ] Thread `attachments`, `onChooseFiles`, `onPasteImage`, and `onRemoveAttachment` through `BusinessAgentPanel` and `BusinessDesktopShell`.
- [ ] Render draft chips from `displayType/sizeBytes` and persisted user-message chips from `mediaType/sizeBytes`, using a shared safe size formatter and never the path.
- [ ] Add a testable `BusinessComposerSendCoordinator` that returns a cleared draft only after `conversation.startTurn(text, attachments)` succeeds and returns the unchanged captured draft on failure. Clear with compare-and-swap against the captured text and attachment IDs so edits made while the request is pending survive. Use it from `Main.kt`, which owns draft state, validates merged count/size/deduplication, preserves stable ordering, and maps safe local attachment failures into visible state.
- [ ] Update `BusinessConversationController.startTurn` to forward attachments and retain existing safe error dispatch.
- [ ] Render persisted user-message attachment chips below the user text, again without exposing local paths.
- [ ] Run the focused green tests.
- [ ] Commit only Task 3 files:

  ```powershell
  git add -p business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessComposerSendCoordinator.kt
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationControllerTest.kt
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessComposerSendCoordinatorTest.kt
  git commit -m "feat(attachments): add composer attachment workflow"
  ```

## Chunk 2: Backend intake, validation, persistence, and references

### Task 4: Add backend attachment request validation before Turn creation

**Files:**

- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentErrorCode.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentException.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentRequest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentMetadata.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/PreparedAttachment.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/PreparedTurnInput.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentLimits.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentFileValidator.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentPreparationService.java`
- Modify: `backend/src/main/resources/application-business-desktop.yml`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/config/BusinessDesktopModeProperties.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/config/BusinessDesktopRuntimePaths.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentFileValidatorTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentPreparationServiceTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/config/BusinessDesktopProfilePropertiesTest.java`

- [ ] Write failing tests for:
  - allowed images, text/code, PDF, DOC/DOCX, XLS/XLSX, and PPT/PPTX;
  - absolute regular non-link paths only;
  - maximum 8 attachments, 20 MiB each, and 50 MiB total;
  - path length 4096, filename length 255, valid UUID, valid `A-[A-HJ-NP-Z2-9]{6}`, unique IDs/display IDs;
  - media type based on detected content plus an allowed extension, not caller claims;
  - image dimensions max 16,384 per side and 50 megapixels;
  - SHA-256 and file identity snapshot (`size`, `mtime`, `fileKey` where available);
  - error objects expose code and safe message but never local paths.
- [ ] Run the focused red test:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=AttachmentFileValidatorTest,AttachmentPreparationServiceTest,BusinessDesktopProfilePropertiesTest test
  ```

  Expected: compilation fails because the attachment package does not exist.

- [ ] Add Apache Tika `tika-core` and `tika-parsers-standard-package` version `3.2.3` to `backend/pom.xml` as part of the minimal implementation.
- [ ] Implement immutable records and `AttachmentFileValidator` using `NOFOLLOW_LINKS`, `BasicFileAttributes`, Tika detection, `ImageIO` header readers, streaming SHA-256, and post-hash attribute comparison.
- [ ] Keep persisted `AttachmentMetadata` separate from internal `PreparedAttachment`: metadata serializes safe display fields plus `localPath`, detected `mediaType`, `sizeBytes`, and `sha256`; `PreparedAttachment` wraps that metadata and additionally owns canonical `Path` plus the non-persisted file-identity snapshot used for asynchronous revalidation.
- [ ] Add `babiq.business.attachment-clipboard-root` with default `${babiq.business.runtime-dir}/attachments/clipboard`, require exact normalized containment below the Agent runtime, create it with owner-only permissions, and expose it from backend runtime paths.
- [ ] Retain a backward-compatible `BusinessDesktopModeProperties` constructor with the old parameter list so direct test constructors continue to compile; the overload derives the controlled clipboard root from `runtimeDir`.
- [ ] Implement `AttachmentPreparationService.prepareNew(...)` to validate the entire request before any Turn or item mutation, derive trusted serialized `source=CLIPBOARD_IMAGE` only when the canonical path is inside the controlled clipboard root (otherwise `SELECTED_FILE`), and return `PreparedTurnInput`.
- [ ] Define `PreparedTurnInput` from the outset with `newAttachments`, `referencedAttachments`, and a deduplicated ordered `allAttachments()` view; later `UserMessageItem` emission will use metadata from `newAttachments` only.
- [ ] Override/test path-free `toString()` behavior for every path-bearing request/prepared/persisted record and keep all exception messages path-free.
- [ ] Run the focused green test.
- [ ] Commit Task 4 files:

  ```powershell
  git add backend/pom.xml backend/src/main/java/com/wzx/babiq/server/attachment backend/src/test/java/com/wzx/babiq/server/attachment
  git add backend/src/main/resources/application-business-desktop.yml backend/src/main/java/com/wzx/babiq/server/application/config backend/src/test/java/com/wzx/babiq/server/application/config/BusinessDesktopProfilePropertiesTest.java
  git commit -m "feat(attachments): validate local attachment requests"
  ```

### Task 5: Persist attachment metadata and resolve exact same-thread references

**Files:**

- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/items/UserMessageItem.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentHistoryResolver.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/conversation/items/ThreadItemJsonTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessTurnScopePropagationTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentHistoryResolverTest.java`

- [ ] Write failing tests proving:
  - `UserMessageItem` round-trips attachment metadata in existing `payload_json`;
  - `turn/start` allows attachment-only input and rejects empty text plus empty attachments before creating a Turn;
  - invalid/missing files fail before `turn/started`, persistence, or executor submission;
  - executor receives `PreparedTurnInput`, not loose path strings;
  - `PreparedTurnInput` keeps newly selected and history-referenced attachments separate, exposes a stable ordered combined view for model input, and never re-emits a history reference as a newly uploaded message attachment;
  - exact standalone, case-insensitive `A-XXXXXX` references resolve only from prior user messages in the same thread/scope;
  - substring matches and cross-thread references do not resolve;
  - UUID and display ID collisions against any paginated current-thread history are rejected;
  - duplicate display IDs in corrupted history return structured `ATTACHMENT_REFERENCE_AMBIGUOUS`.
- [ ] Run the focused red test:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=ThreadItemJsonTest,TurnStartHandlerTest,AttachmentHistoryResolverTest,BusinessTurnScopePropagationTest test
  ```

  Expected: tests fail because user messages have no attachments and Turn execution still accepts plain text.

- [ ] Add `List<AttachmentMetadata> attachments` to `UserMessageItem`; normalize a missing/null JSON field to immutable `List.of()` in the compact canonical constructor and retain the existing three-argument constructor/factory.
- [ ] Implement `AttachmentHistoryResolver` with `ObjectMapper` decoding and full pagination through `ConversationRepository.listItems(threadId, limit, beforeItemId, scope)`, using the exact token regex `(?i)(?<![A-Z0-9])A-[A-HJ-NP-Z2-9]{6}(?![A-Z0-9])`.
- [ ] Inject `AttachmentPreparationService` and `AttachmentHistoryResolver` into `TurnStartHandler`; for business sessions, keep thread ownership lookup, all paginated history resolution, file validation, Turn creation, and start persistence inside the existing `withActiveConnectionScope(...)` critical section.
- [ ] Change `TurnExecutor.submit` and `AgentLoop.invoke` entry points to carry one immutable `PreparedTurnInput`, preserving compatibility overloads for existing callers until all call sites migrate.
- [ ] Persist only the raw text in the Turn input field and emit attachment metadata through `UserMessageItem`; do not persist bytes or extracted text.
- [ ] Run the focused green test.
- [ ] Commit only Task 5 files:

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/conversation/items/UserMessageItem.java
  git add backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java
  git add backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java
  git add backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java
  git add backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentHistoryResolver.java
  git add backend/src/test/java/com/wzx/babiq/server/conversation/items/ThreadItemJsonTest.java
  git add backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java
  git add backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessTurnScopePropagationTest.java
  git add backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentHistoryResolverTest.java
  git commit -m "feat(attachments): persist and resolve attachment metadata"
  ```

### Task 6: Redact attachment paths from protocol logs and failures

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentDiagnosticRedactor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcLogSupport.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcLogSupportTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentJsonRpcClient.kt`
- Create: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/client/AgentJsonRpcClientTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationControllerTest.kt`

- [ ] Write failing tests proving JSON-RPC previews recursively replace only attachment path-bearing fields (`localPath`, canonical/internal path fields) while retaining safe IDs/names, and every attachment failure maps to a stable code without a raw filesystem path.
- [ ] Write client tests proving only a whitelisted stable `attachmentCode` is retained from `error.data`; arbitrary remote messages/data remain discarded. Verify the controller maps that code to a safe actionable Chinese message.
- [ ] Run:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=JsonRpcLogSupportTest,TurnStartHandlerTest test
  cd ..\business-desktop
  .\gradlew.bat :agent-client-core:test --tests "*AgentJsonRpcClientTest"
  .\gradlew.bat :app:test --tests "*BusinessConversationControllerTest"
  ```

  Expected: path markers appear in previews/error messages and desktop tests fail because no safe attachment code is retained or mapped.

- [ ] Add a reusable structural `AttachmentDiagnosticRedactor` and use it in the common JSON-RPC params summary. Add one `TurnStartHandler` mapping from `AttachmentException` to `JsonRpcException(INVALID_PARAMS, safeMessage, Map.of("attachmentCode", code))`.
- [ ] Confirm by test and code search that application-action audit serialization never receives `turn/start` attachment params. Document this actual boundary in the design; any future attachment-bearing audit entry must call `AttachmentDiagnosticRedactor`.
- [ ] Extend `AgentJsonRpcException` with an optional whitelisted stable reason code only, never the raw remote message or arbitrary data, and map it to the desktop’s safe visible error state.
- [ ] Log only thread ID, count, total bytes, safe display IDs, error code, and exception type.
- [ ] Re-run the focused tests and commit:

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/api backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentDiagnosticRedactor.java backend/src/test/java/com/wzx/babiq/server/api
  git add business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentJsonRpcClient.kt business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/client/AgentJsonRpcClientTest.kt
  git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationControllerTest.kt
  git commit -m "fix(attachments): redact local paths from diagnostics"
  ```

## Chunk 3: Bounded extraction, context integration, and multimodal input

### Task 7: Extract supported documents with strict limits

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentTextSegment.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentContent.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/OoxmlArchiveGuard.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentDocumentExtractor.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentContentLoader.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/OoxmlArchiveGuardTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentDocumentExtractorTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentContentLoaderTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/LegacyOfficeTestFixtures.java`

- [ ] Write failing tests using generated in-memory fixtures for TXT/UTF-8, PDF, DOCX, XLSX, PPTX, XLS, and PPT. For legacy DOC, commit a minimal Apache-2.0-compatible fixture as an attributed Base64 constant in `LegacyOfficeTestFixtures` (decoded only in the test); POI HWPF cannot create a new DOC from scratch.
- [ ] Cover:
  - 100,000 extracted characters per file and 250,000 per turn;
  - OOXML central-directory max 1,000 entries, 100 MiB declared total, 50 MiB single entry, and 100:1 compression ratio;
  - embedded-object parsing disabled;
  - extraction above 100,000 characters fails with `ATTACHMENT_TEXT_LIMIT_EXCEEDED` rather than returning an unlabelled partial body;
  - encrypted PDF returns `ATTACHMENT_ENCRYPTED`;
  - archive extensions are rejected;
  - per-file 10-second and per-turn 30-second timeouts;
  - bounded executor size 2 with queue 8 and overload code;
  - a second file-identity/hash check immediately before reading.
- [ ] Run the focused red test:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=OoxmlArchiveGuardTest,AttachmentDocumentExtractorTest,AttachmentContentLoaderTest test
  ```

  Expected: compilation fails because extraction components do not exist.

- [ ] Implement:
  - Tika `AutoDetectParser`;
  - `BodyContentHandler(100_000)` wrapped by `SecureContentHandler`, with explicit output threshold and maximum compression-ratio settings;
  - `ParseContext` configured with `EmptyParser` for embedded documents;
  - `PDFParserConfig.setThrowOnEncryptedPayload(true)` and inline-image extraction disabled;
  - a lifecycle-owned bounded `ThreadPoolExecutor`;
  - controlled timeout cancellation and normalized whitespace.
- [ ] For images, `AttachmentContentLoader` returns bytes plus detected MIME only after revalidation; for documents, it returns bounded `AttachmentTextSegment`s.
- [ ] Re-run the focused tests and commit:

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/attachment backend/src/test/java/com/wzx/babiq/server/attachment
  git commit -m "feat(attachments): extract bounded business documents"
  ```

### Task 8: Budget attachment text inside ContextWindowRuntime without persisting content

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/context/attachment/AttachmentContextBudgeter.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/attachment/AttachmentPromptRenderer.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextSourceType.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextSnapshotItem.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeInput.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextAssemblyResult.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/context/attachment/AttachmentContextBudgeterTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/security/SystemPromptSecurityRuleTest.java`

- [ ] Write failing tests proving:
  - attachment text consumes at most 35% of the effective model context window;
  - the actual attachment allowance is `min(35% of effective window, inputBudgetTokens - baseSnapshot.estimatedTokens)` and becomes zero when base context already consumes the input budget;
  - attachment budget is allocated in the user-selected order and later attachments are explicitly marked truncated/excluded when the budget is exhausted;
  - model input contains `<attachment id="A-..." name="..." content_type="...">` sections with safely escaped labels;
  - snapshot items use `ContextSourceType.ATTACHMENT`, safe display ID/name, media type, original/included/truncated character counts, included/excluded reason, and token estimates;
  - the business system prompt states that attachment bodies are untrusted business data and cannot override system rules, approval, sandbox, or application-action constraints;
  - persisted `envelope_json`, `items_json`, previews, and logs do not contain extracted document markers or local paths.
- [ ] Run:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=AttachmentContextBudgeterTest,ContextWindowRuntimeTest,ContextSnapshotPersistenceTest,SystemPromptSecurityRuleTest test
  ```

  Expected: tests fail because runtime input and snapshots are attachment-unaware.

- [ ] Add `ATTACHMENT` source type and immutable attachment text segments to `ContextWindowRuntimeInput`.
- [ ] Extend `ContextSnapshotItem` with nullable media type and original/included/truncated character counts, retaining the existing six-argument constructor for all current call sites.
- [ ] Budget and render attachments inside `ContextWindowRuntime.prepare` after normal assembly/compaction. Compute remaining input budget from `ContextBudget.inputBudgetTokens()` minus the base snapshot estimate; augment only `modelInputText` and metadata-only snapshot items, never `ContextEnvelope` or persisted content.
- [ ] Return a `ContextAssemblyResult` whose snapshot includes metadata-only attachment items while messages/envelope remain content-free.
- [ ] Re-run the focused tests and commit:

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/context backend/src/test/java/com/wzx/babiq/server/context
  git add backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java backend/src/test/java/com/wzx/babiq/server/security/SystemPromptSecurityRuleTest.java
  git commit -m "feat(attachments): budget attachment context safely"
  ```

### Task 9: Send images as Spring AI multimodal media

**Files:**

- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopSupport.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeResult.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentModelFailureClassifier.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopContextRuntimeTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopAttachmentTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentModelFailureClassifierTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopTest.java`

- [ ] Write failing tests proving:
  - no-attachment and document-only turns retain the existing string stream path;
  - image turns call `ReactAgent.stream(UserMessage, RunnableConfig)`;
  - `UserMessage.getMedia()` contains detected MIME and exact bytes only at model-call time;
  - Spring AI `Media` has no source name/ID metadata, and `UserMessage.text` contains neither local path nor SHA-256;
  - emitted `userMessage` includes new-attachment metadata but not re-referenced history metadata as a new upload;
  - missing/changed files fail before model invocation;
  - known provider image/media rejection maps to `ATTACHMENT_MODEL_UNSUPPORTED`;
  - provider response bodies and low-level exception text remain internal diagnostics and never enter `turn/failed`, while unrelated failures retain a stable generic user-facing classification.
- [ ] Run:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=AgentLoopContextRuntimeTest,AgentLoopAttachmentTest,AgentLoopTest,AttachmentModelFailureClassifierTest test
  ```

  Expected: tests fail because `AgentLoop` always streams a String.

- [ ] In `AgentLoop`, emit `UserMessageItem` from `PreparedTurnInput`, load attachment content, prepare text context, then:
  - stream the existing String when there is no media;
  - otherwise build `UserMessage.builder().text(modelInputText).media(media).build()` and stream that message.
- [ ] Keep all media byte arrays scoped to the current invocation and release references after stream consumption.
- [ ] Refactor `AgentLoopSupport` to separate internal diagnostic logging from safe user-facing failure messages; map attachment failures to stable safe turn reasons and never echo remote HTTP response bodies, raw paths, or parser exception text.
- [ ] Re-run the focused tests and commit:

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java
  git add backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopSupport.java
  git add backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeResult.java
  git add backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentModelFailureClassifier.java
  git add backend/src/test/java/com/wzx/babiq/server/agent
  git add backend/src/test/java/com/wzx/babiq/server/attachment/AttachmentModelFailureClassifierTest.java
  git commit -m "feat(attachments): send images as multimodal input"
  ```

## Chunk 4: Retention, integration, and verification

### Task 10: Clean controlled clipboard files without touching user-selected files

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/ClipboardAttachmentRetentionService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentReferenceRecord.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentReferenceRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/attachment/SQLiteAttachmentReferenceRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ItemMapper.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/ClipboardAttachmentRetentionServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/attachment/SQLiteAttachmentReferenceRepositoryTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/recovery/RecoveryStartupRunnerTest.java`

- [ ] Write failing tests proving:
  - cleanup deletes only controlled clipboard files that are unreferenced and older than 24 hours;
  - referenced active-thread files remain;
  - archived-thread references expire after 30 days;
  - cleanup first removes expired orphan files and archive-retention-expired files, but never deletes active references merely to get below 1 GiB;
  - when eligible cleanup is insufficient, new screenshot creation remains blocked by the desktop-side 1 GiB capacity check;
  - only app-generated names matching `截图-\d{8}-\d{6}-[A-HJ-NP-Z2-9]{6}\.png` are eligible; unrelated ordinary files inside the controlled directory are never deleted;
  - symlinks/reparse-like entries and all paths outside the controlled root are ignored/rejected;
  - the real SQLite reference query covers scoped and unscoped user messages, excludes non-user/removed items, and returns the joined thread archive timestamp;
  - startup recovery invokes one cleanup and the scheduled six-hour cleanup reuses the same idempotent method.
- [ ] Run:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=ClipboardAttachmentRetentionServiceTest,SQLiteAttachmentReferenceRepositoryTest,RecoveryStartupRunnerTest test
  ```

  Expected: retention tests fail because the repository and cleanup service do not exist.

- [ ] Implement `AttachmentReferenceRepository` as a narrow MyBatis-backed query over `bq_items` joined to `bq_threads`, returning only user-message payload JSON plus archive state needed by retention. Implement retention from those records and never delete arbitrary selected user files.
- [ ] Make repository/retention beans conditional on `babiq.business.enabled=true`. Inject retention into `RecoveryStartupRunner` through `ObjectProvider`, invoke one cleanup after database recovery but before `markRecoveryComplete()`, and schedule the same idempotent method with `@Scheduled(fixedDelayString = "${babiq.business.attachment-cleanup-interval-millis:21600000}")`.
- [ ] Re-run focused tests and commit only related hunks:

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/attachment/ClipboardAttachmentRetentionService.java backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentReferenceRecord.java backend/src/main/java/com/wzx/babiq/server/attachment/AttachmentReferenceRepository.java backend/src/main/java/com/wzx/babiq/server/attachment/SQLiteAttachmentReferenceRepository.java
  git add backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ItemMapper.java
  git add backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java
  git add backend/src/test/java/com/wzx/babiq/server/attachment/ClipboardAttachmentRetentionServiceTest.java
  git add backend/src/test/java/com/wzx/babiq/server/attachment/SQLiteAttachmentReferenceRepositoryTest.java
  git add backend/src/test/java/com/wzx/babiq/server/recovery/RecoveryStartupRunnerTest.java
  git commit -m "feat(attachments): retain controlled clipboard files"
  ```

### Task 11: Add end-to-end protocol and UI regression coverage

**Files:**

- Create: `backend/src/test/java/com/wzx/babiq/server/application/BusinessAttachmentEndToEndIT.java`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAgentAttachmentWorkflowIT.kt`
- Modify: `docs/superpowers/specs/2026-07-20-business-agent-local-attachments-design.md`
- Create: `docs/superpowers/plans/2026-07-20-business-agent-local-attachments-codex-handoff.md`

- [ ] Add an authenticated business-profile integration test that:
  - creates the one-shot token file, connects with trusted desktop headers, and calls `application/identity/bind` before post-bind methods;
  - creates a thread;
  - starts a text-document attachment turn;
  - observes `userMessage.attachments`;
  - verifies the model stub received extracted text;
  - starts a second turn referencing the stable display ID;
  - proves the same-thread reference works;
  - proves no Base64 or extracted body appears in WebSocket frames; local paths appear only in explicit local attachment request/metadata fields and never in logs, errors, context snapshots, or Provider model input.
- [ ] Keep the real authenticated dispatcher, `TurnExecutor`, and `AgentLoop` while replacing only `ChatClientFactory`/model behavior with a deterministic capture stub.
- [ ] Create a dedicated business Agent attachment workflow integration test that wires `BusinessAgentPanel`/shell, `BusinessConversationController`, a fake gateway, and injected picker/clipboard seams; test attach, remove, attachment-only send, screenshot paste, safe chips, and draft retention without native dialogs.
- [ ] Run the integration red/green cycle:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=BusinessAttachmentEndToEndIT test
  cd ..\business-desktop
  .\gradlew.bat :app:test --tests "*BusinessAgentAttachmentWorkflowIT"
  ```

- [ ] Update the design document only for implementation-confirmed deviations and write the handoff with file map, error codes, test evidence, and manual smoke instructions.
- [ ] Commit:

  ```powershell
  git add backend/src/test/java/com/wzx/babiq/server/application/BusinessAttachmentEndToEndIT.java
  git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAgentAttachmentWorkflowIT.kt
  git add docs/superpowers/specs/2026-07-20-business-agent-local-attachments-design.md
  git add docs/superpowers/plans/2026-07-20-business-agent-local-attachments-codex-handoff.md
  git commit -m "test(attachments): cover business attachment workflow"
  ```

### Task 12: Perform fresh full verification

**Files:**

- Verify only; update handoff evidence if commands pass.

- [ ] Run backend focused attachment suite:

  ```powershell
  cd backend
  .\mvnw.cmd -Dtest=AttachmentFileValidatorTest,AttachmentPreparationServiceTest,AttachmentHistoryResolverTest,OoxmlArchiveGuardTest,AttachmentDocumentExtractorTest,AttachmentContentLoaderTest,AttachmentContextBudgeterTest,AttachmentModelFailureClassifierTest,AgentLoopAttachmentTest,TurnStartHandlerTest,ThreadItemJsonTest,JsonRpcLogSupportTest,ContextWindowRuntimeTest,ContextSnapshotPersistenceTest,ClipboardAttachmentRetentionServiceTest,SQLiteAttachmentReferenceRepositoryTest,RecoveryStartupRunnerTest,BusinessAttachmentEndToEndIT test
  ```

- [ ] Run backend full verification:

  ```powershell
  cd backend
  .\mvnw.cmd clean verify
  ```

- [ ] Run business desktop focused tests:

  ```powershell
  cd business-desktop
  .\gradlew.bat :agent-client-core:test --tests "*BusinessAgentClientTest" --tests "*BusinessThreadModelsTest"
  .\gradlew.bat :app:test --tests "*BusinessAgentPanelTest" --tests "*BusinessDesktopShellTest" --tests "*ClipboardImageAttachmentStoreTest" --tests "*BusinessAttachmentIdFactoryTest" --tests "*BusinessAttachmentPickerTest" --tests "*BusinessConversationControllerTest" --tests "*BusinessComposerSendCoordinatorTest" --tests "*BusinessDesktopRuntimePathsTest" --tests "*BusinessDesktopCompositionRootTest" --tests "*BusinessAgentProcessLauncherTest" --tests "*BusinessAgentAttachmentWorkflowIT"
  ```

- [ ] Run business desktop full verification:

  ```powershell
  cd business-desktop
  .\gradlew.bat test
  ```

- [ ] Start the standalone backend and frontend separately, then manually smoke test:
  - console 1: `cd business-desktop; .\gradlew.bat :app:runBusinessBackendDevelopment`;
  - console 2: `cd business-desktop; .\gradlew.bat :app:runBusinessFrontendDevelopment`;
  - select one image and one document;
  - paste a screenshot with Ctrl+V;
  - inspect stable chips and remove one;
  - send attachments with and without text;
  - confirm the image-capable Provider receives media and the document content is understood;
  - reference one prior `A-XXXXXX`;
  - rename/delete a referenced file and confirm a safe actionable error;
  - verify backend logs contain no raw paths or Base64.
- [ ] Record fresh command output and smoke results in the handoff.
- [ ] Review `git diff --check`, `git status --short`, and the final diff. Stage only attachment-task files; do not include unrelated dirty-worktree changes.
- [ ] Commit any handoff-only evidence update:

  ```powershell
  git add docs/superpowers/plans/2026-07-20-business-agent-local-attachments-codex-handoff.md
  git commit -m "docs(attachments): record verification evidence"
  ```
