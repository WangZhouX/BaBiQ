package com.wzx.babiq.server.context.attachment;

import com.wzx.babiq.server.attachment.AttachmentTextSegment;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.context.compaction.ContextBudget;
import com.wzx.babiq.server.context.model.ContextExclusionReason;
import com.wzx.babiq.server.context.model.ContextPriority;
import com.wzx.babiq.server.context.model.ContextSnapshotItem;
import com.wzx.babiq.server.context.model.ContextSourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Allocates the current turn's transient document text within the context
 * window without putting attachment bodies into snapshots or envelopes.
 */
@Component
public class AttachmentContextBudgeter {

    private static final int ATTACHMENT_WINDOW_PERCENT = 35;
    private static final String INCLUDED_REASON = "ATTACHMENT_INCLUDED";
    private static final String TRUNCATED_REASON = "ATTACHMENT_TRUNCATED_TOKEN_BUDGET";

    private final ContextTokenEstimator tokenEstimator;
    private final AttachmentPromptRenderer renderer;

    public AttachmentContextBudgeter(ContextTokenEstimator tokenEstimator,
                                     AttachmentPromptRenderer renderer) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Allocate attachment text in user selection order.
     *
     * @param segments ordered, ephemeral extracted text
     * @param budget effective context budget for this model invocation
     * @param baseEstimatedTokens token estimate already consumed by normal context
     */
    public Result budget(List<AttachmentTextSegment> segments,
                         ContextBudget budget,
                         int baseEstimatedTokens) {
        List<AttachmentTextSegment> ordered =
                segments == null ? List.of() : List.copyOf(segments);
        Objects.requireNonNull(budget, "budget");
        int windowShare = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, ((long) budget.effectiveModelContextWindow() * ATTACHMENT_WINDOW_PERCENT) / 100L));
        int remainingInput = Math.max(0, budget.inputBudgetTokens() - Math.max(0, baseEstimatedTokens));
        int allowance = Math.min(windowShare, remainingInput);
        if (ordered.isEmpty()) {
            return Result.empty();
        }

        String renderedText = "";
        int renderedTokens = 0;
        boolean exhausted = allowance <= 0;
        List<ContextSnapshotItem> snapshotItems = new ArrayList<>(ordered.size());
        for (AttachmentTextSegment segment : ordered) {
            Objects.requireNonNull(segment, "attachmentTextSegment");
            String fullSection = renderer.render(segment, segment.text(), false);
            int originalTokens = tokenEstimator.estimate(fullSection);
            if (exhausted) {
                snapshotItems.add(snapshotItem(segment, false, ContextExclusionReason.TOKEN_BUDGET.name(),
                        originalTokens, 0));
                continue;
            }

            String fullCandidate = appendSection(renderedText, fullSection);
            int fullCandidateTokens = tokenEstimator.estimate(fullCandidate);
            if (fullCandidateTokens <= allowance) {
                int contribution = Math.max(0, fullCandidateTokens - renderedTokens);
                renderedText = fullCandidate;
                renderedTokens = fullCandidateTokens;
                snapshotItems.add(snapshotItem(segment, true, INCLUDED_REASON,
                        contribution, segment.originalCharacterCount()));
                exhausted = renderedTokens >= allowance;
                continue;
            }

            int includedCharacters = largestPrefixWithinBudget(segment, renderedText, allowance);
            if (includedCharacters > 0) {
                String truncatedSection =
                        renderer.render(segment, segment.text().substring(0, includedCharacters), true);
                String truncatedCandidate = appendSection(renderedText, truncatedSection);
                int truncatedCandidateTokens = tokenEstimator.estimate(truncatedCandidate);
                int contribution = Math.max(0, truncatedCandidateTokens - renderedTokens);
                renderedText = truncatedCandidate;
                renderedTokens = truncatedCandidateTokens;
                snapshotItems.add(snapshotItem(segment, true, TRUNCATED_REASON,
                        contribution, includedCharacters));
            } else {
                snapshotItems.add(snapshotItem(segment, false, ContextExclusionReason.TOKEN_BUDGET.name(),
                        originalTokens, 0));
            }
            // Selection order is authoritative. Once one item cannot fit in full,
            // later items cannot leapfrog it merely because they happen to be smaller.
            exhausted = true;
        }
        return new Result(renderedText, renderedTokens, snapshotItems);
    }

    private int largestPrefixWithinBudget(AttachmentTextSegment segment,
                                          String alreadyRendered,
                                          int allowance) {
        int low = 0;
        int high = segment.text().length();
        int best = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int safeMiddle = avoidSplitSurrogate(segment.text(), middle);
            String candidateSection =
                    renderer.render(segment, segment.text().substring(0, safeMiddle), true);
            int candidateTokens = tokenEstimator.estimate(appendSection(alreadyRendered, candidateSection));
            if (candidateTokens <= allowance) {
                best = Math.max(best, safeMiddle);
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static int avoidSplitSurrogate(String text, int index) {
        if (index > 0 && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index))) {
            return index - 1;
        }
        return index;
    }

    private static String appendSection(String current, String section) {
        return current.isEmpty() ? section : current + "\n\n" + section;
    }

    private static ContextSnapshotItem snapshotItem(AttachmentTextSegment segment,
                                                    boolean included,
                                                    String reason,
                                                    int tokenEstimate,
                                                    int includedCharacters) {
        int original = segment.originalCharacterCount();
        int accepted = Math.max(0, Math.min(original, includedCharacters));
        return new ContextSnapshotItem(
                segment.displayId(),
                ContextSourceType.ATTACHMENT,
                included ? ContextPriority.HIGH : ContextPriority.EXCLUDED,
                included,
                reason,
                Math.max(0, tokenEstimate),
                segment.name(),
                segment.mediaType(),
                original,
                accepted,
                original - accepted);
    }

    /**
     * Budget result. It deliberately contains rendered prompt text only in
     * memory; snapshot items carry metadata and counts, never the body.
     */
    public record Result(String renderedText,
                         int tokenEstimate,
                         List<ContextSnapshotItem> snapshotItems) {

        public Result {
            renderedText = renderedText == null ? "" : renderedText;
            tokenEstimate = Math.max(0, tokenEstimate);
            snapshotItems = snapshotItems == null ? List.of() : List.copyOf(snapshotItems);
        }

        public static Result empty() {
            return new Result("", 0, List.of());
        }
    }
}
