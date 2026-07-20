package com.wzx.babiq.server.attachment;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Atomically protects attachment identities until their user-message metadata is visible in history.
 */
@Component
public final class AttachmentReservationRegistry {

    private static final Pattern DISPLAY_ID_PATTERN = Pattern.compile("A-[A-HJ-NP-Z2-9]{6}");

    private final Clock clock;
    private final Map<ScopeThreadKey, Map<String, String>> ownersByIdentity = new HashMap<>();
    private final Map<String, ReservationState> statesByToken = new HashMap<>();
    private final Map<String, String> tokensByTurn = new HashMap<>();
    private final Map<Path, Integer> activePathReferences = new HashMap<>();

    public AttachmentReservationRegistry() {
        this(Clock.systemUTC());
    }

    AttachmentReservationRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Serializes synchronous attachment validation/history resolution/reservation with cleanup.
     *
     * <p>The supplied operation must not wait for asynchronous persistence or worker completion.</p>
     */
    public synchronized <T> T withinPublicationGuard(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }

    /**
     * Serializes the complete retention query/scan/delete operation with attachment publication.
     */
    public synchronized <T> T withinCleanupGuard(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }

    /**
     * Atomically reserves every UUID and display ID for one thread/scope boundary.
     */
    public synchronized Reservation reserve(
            String threadId,
            BusinessIdentityScope scope,
            List<PreparedAttachment> attachments
    ) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(attachments, "attachments");
        if (attachments.isEmpty()) {
            return Reservation.inactive(this);
        }

        ScopeThreadKey key = new ScopeThreadKey(
                threadId,
                scope == null ? BusinessIdentityScope.UNSCOPED : scope);
        Set<String> identities = new LinkedHashSet<>();
        Set<Path> protectedPaths = new LinkedHashSet<>();
        for (PreparedAttachment attachment : attachments) {
            AttachmentMetadata metadata = Objects.requireNonNull(attachment, "attachment").metadata();
            identities.add("id:" + canonicalUuid(metadata.id()));
            identities.add("display:" + canonicalDisplayId(metadata.displayId()));
            protectedPaths.add(attachment.canonicalPath().toAbsolutePath().normalize());
        }

        Map<String, String> owners = ownersByIdentity.computeIfAbsent(key, ignored -> new HashMap<>());
        rejectOwnedIdentities(owners, identities);

        String token = UUID.randomUUID().toString();
        identities.forEach(identity -> owners.put(identity, token));
        protectedPaths.forEach(path ->
                activePathReferences.merge(path, 1, Math::addExact));
        statesByToken.put(token, new ReservationState(
                key,
                Set.copyOf(identities),
                Set.copyOf(protectedPaths),
                clock.instant()));
        return new Reservation(this, token);
    }

    /**
     * Rejects already-pending new identities before the more expensive history scan.
     *
     * <p>Callers that depend on this check must keep the surrounding publication guard until
     * {@link #reserve(String, BusinessIdentityScope, List)} completes.</p>
     */
    public synchronized void assertAvailable(
            String threadId,
            BusinessIdentityScope scope,
            List<PreparedAttachment> attachments
    ) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(attachments, "attachments");
        if (attachments.isEmpty()) {
            return;
        }
        ScopeThreadKey key = new ScopeThreadKey(
                threadId,
                scope == null ? BusinessIdentityScope.UNSCOPED : scope);
        Map<String, String> owners = ownersByIdentity.get(key);
        if (owners == null) {
            return;
        }
        Set<String> identities = new LinkedHashSet<>();
        for (PreparedAttachment attachment : attachments) {
            AttachmentMetadata metadata = Objects.requireNonNull(attachment, "attachment").metadata();
            identities.add("id:" + canonicalUuid(metadata.id()));
            identities.add("display:" + canonicalDisplayId(metadata.displayId()));
        }
        rejectOwnedIdentities(owners, identities);
    }

    private static void rejectOwnedIdentities(
            Map<String, String> owners,
            Set<String> identities
    ) {
        for (String identity : identities) {
            if (owners.containsKey(identity)) {
                throw ambiguous();
            }
        }
    }

    synchronized boolean isPathProtected(Path path) {
        if (path == null) {
            return false;
        }
        return activePathReferences.containsKey(path.toAbsolutePath().normalize());
    }

    /**
     * Associates a successful reservation with its turn so async lifecycle owners can release it.
     */
    private synchronized void bind(String token, String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        ReservationState state = statesByToken.get(token);
        if (state == null) {
            throw new IllegalStateException("attachment reservation is no longer active");
        }
        if (state.turnId() != null && !state.turnId().equals(turnId)) {
            throw new IllegalStateException("attachment reservation is already bound");
        }
        String existing = tokensByTurn.putIfAbsent(turnId, token);
        if (existing != null && !existing.equals(token)) {
            throw new IllegalStateException("turn already owns an attachment reservation");
        }
        statesByToken.put(token, state.withTurnId(turnId));
    }

    /** Releases a reservation after persistence, cancellation, or worker termination. */
    public synchronized void releaseTurn(String turnId) {
        if (turnId == null) {
            return;
        }
        String token = tokensByTurn.remove(turnId);
        if (token != null) {
            releaseToken(token);
        }
    }

    private synchronized boolean isActive(String token) {
        return token != null && statesByToken.containsKey(token);
    }

    private synchronized void releaseToken(String token) {
        if (token == null) {
            return;
        }
        ReservationState state = statesByToken.remove(token);
        if (state == null) {
            return;
        }
        if (state.turnId() != null) {
            tokensByTurn.remove(state.turnId(), token);
        }
        for (Path path : state.protectedPaths()) {
            activePathReferences.computeIfPresent(path, (ignored, references) ->
                    references <= 1 ? null : references - 1);
        }
        Map<String, String> owners = ownersByIdentity.get(state.key());
        if (owners == null) {
            return;
        }
        for (String identity : state.identities()) {
            owners.remove(identity, token);
        }
        if (owners.isEmpty()) {
            ownersByIdentity.remove(state.key());
        }
    }

    private static String canonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return uuid.toString();
        } catch (RuntimeException exception) {
            throw ambiguous();
        }
    }

    private static String canonicalDisplayId(String value) {
        if (value == null) {
            throw ambiguous();
        }
        String canonical = value.toUpperCase(Locale.ROOT);
        if (!DISPLAY_ID_PATTERN.matcher(canonical).matches()) {
            throw ambiguous();
        }
        return canonical;
    }

    private static AttachmentException ambiguous() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS,
                "Attachment identity is already pending in this thread");
    }

    public static final class Reservation implements AutoCloseable {

        private final AttachmentReservationRegistry owner;
        private final String token;

        private Reservation(AttachmentReservationRegistry owner, String token) {
            this.owner = owner;
            this.token = token;
        }

        private static Reservation inactive(AttachmentReservationRegistry owner) {
            return new Reservation(owner, null);
        }

        public void bindToTurn(String turnId) {
            if (token != null) {
                owner.bind(token, turnId);
            }
        }

        public boolean active() {
            return owner.isActive(token);
        }

        @Override
        public void close() {
            owner.releaseToken(token);
        }
    }

    private record ScopeThreadKey(String threadId, BusinessIdentityScope scope) {
    }

    private record ReservationState(
            ScopeThreadKey key,
            Set<String> identities,
            Set<Path> protectedPaths,
            Instant createdAt,
            String turnId
    ) {

        private ReservationState(
                ScopeThreadKey key,
                Set<String> identities,
                Set<Path> protectedPaths,
                Instant createdAt
        ) {
            this(key, identities, protectedPaths, createdAt, null);
        }

        private ReservationState withTurnId(String value) {
            return new ReservationState(key, identities, protectedPaths, createdAt, value);
        }
    }
}
