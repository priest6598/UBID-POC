package com.karnataka.ubid.model;

import java.time.Instant;

/**
 * An immutable audit record produced by the reviewer UI when a human
 * adjudicates a pair from the review queue. These records are the labelled
 * training signal that feeds the active-learning loop ({@link
 * com.karnataka.ubid.learning.ActiveLearningTrainer}).
 */
public record ReviewerDecision(
        String leftRecordId,
        String rightRecordId,
        Verdict verdict,
        String reviewerId,
        Instant timestamp,
        String note
) {
    public enum Verdict {
        CONFIRMED_MATCH,
        CONFIRMED_NON_MATCH,
        ESCALATED        // bumped to senior review — neither labels nor trains
    }

    public static ReviewerDecision match(String left, String right, String reviewerId, String note) {
        return new ReviewerDecision(left, right, Verdict.CONFIRMED_MATCH, reviewerId, Instant.now(), note);
    }

    public static ReviewerDecision nonMatch(String left, String right, String reviewerId, String note) {
        return new ReviewerDecision(left, right, Verdict.CONFIRMED_NON_MATCH, reviewerId, Instant.now(), note);
    }
}
