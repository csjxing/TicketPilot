package cn.local.tickethelper.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiRequestReviewTest {
    private AiSettings settings(String endpoint, String model, int daily) {
        return new AiSettings(true, endpoint, model, 8192, daily, false, 32768, 120);
    }

    @Test public void reviewedTextAndProviderAreFrozenUntilExplicitSend() {
        AiSettings original = settings("https://example.test/v1", "model-a", 20);
        AiRequestReview review = new AiRequestReview(original, "system", "public context at T1", 7, false);
        assertTrue(review.matches(settings("https://example.test/v1", "model-a", 20), 7, false));
        assertEquals("public context at T1", review.userPrompt);
        assertFalse(review.matches(settings("https://other.test/v1", "model-a", 20), 7, false));
        assertFalse(review.matches(settings("https://example.test/v1", "model-b", 20), 7, false));
        assertFalse(review.matches(settings("https://example.test/v1", "model-a", 0), 7, false));
    }

    @Test public void editingOrLeavingInvalidatesEvenAnIdenticalLookingRequest() {
        AiSettings settings = settings("https://example.test/v1", "model-a", 0);
        AiRequestReview review = new AiRequestReview(settings, "system", "question", 9, false);
        assertFalse(review.matches(settings, 10, false));
        assertFalse(review.matches(settings, 8, false));
        assertFalse(review.matches(null, 9, false));
    }

    @Test public void testPreviewCannotAuthorizeAContextRequest() {
        AiSettings settings = settings("https://example.test/v1", "model-a", 0);
        AiRequestReview test = new AiRequestReview(settings, "test system", "fixed test text", 4, true);
        assertTrue(test.matches(settings, 4, true));
        assertFalse(test.matches(settings, 4, false));
        assertEquals("fixed test text", test.userPrompt);
    }
}
