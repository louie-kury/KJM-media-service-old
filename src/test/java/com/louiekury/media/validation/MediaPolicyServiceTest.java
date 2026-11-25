package com.louiekury.media.validation;

import com.louiekury.media.validation.application.service.MediaPolicyService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MediaPolicyServiceTest {
    private final MediaPolicyService service = new MediaPolicyService();

    @Test void returnsDocumentedPolicyForEveryUsage() {
        Map<String, Long> images = Map.of("image/jpeg", 10_000_000L,
                "image/png", 10_000_000L, "image/webp", 10_000_000L);
        Map<String, Long> post = Map.of("image/jpeg", 10_000_000L,
                "image/png", 10_000_000L, "image/webp", 10_000_000L,
                "video/mp4", 100_000_000L);
        Map<MediaUsage, Integer> counts = Map.of(
                MediaUsage.MEMBER_PROFILE, 1, MediaUsage.PET_PROFILE, 1,
                MediaUsage.POST_ATTACHMENT, 5, MediaUsage.HEALTH_ATTACHMENT, 3,
                MediaUsage.CONSULTATION_ATTACHMENT, 3);

        assertEquals(5, MediaUsage.values().length);
        for (MediaUsage usage : MediaUsage.values()) {
            var policy = service.getPolicy(usage);
            assertEquals(usage, policy.usage());
            assertEquals(counts.get(usage), policy.maxAttachments());
            assertEquals(usage == MediaUsage.POST_ATTACHMENT ? post : images,
                    policy.maxBytesByMimeType());
        }
    }

    @Test void agreesWithExistingValidationForEveryUsageAndType() {
        for (MediaUsage usage : MediaUsage.values()) {
            var limits = service.getPolicy(usage).maxBytesByMimeType();
            for (MediaType type : MediaType.values()) {
                assertEquals(usage.allows(type), limits.containsKey(type.getMimeType()),
                        usage + " / " + type);
                if (usage.allows(type)) {
                    assertEquals(type.getMaxBytes(), limits.get(type.getMimeType()));
                }
            }
        }
    }

    @Test void returnedLimitsCannotBeMutated() {
        var policy = service.getPolicy(MediaUsage.POST_ATTACHMENT);
        assertThrows(UnsupportedOperationException.class,
                () -> policy.maxBytesByMimeType().put("image/jpeg", 1L));
        assertEquals(10_000_000L, service.getPolicy(MediaUsage.POST_ATTACHMENT)
                .maxBytesByMimeType().get("image/jpeg"));
    }

    @Test void rejectsMissingUsage() {
        assertThrows(RuntimeException.class, () -> service.getPolicy(null));
    }
}
