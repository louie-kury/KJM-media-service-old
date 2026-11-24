package com.louiekury.media.validation;

import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.ValidationFailure;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MEDIA_FFPROBE_PATH", matches = ".+")
class FfprobeIntegrationTest {
    private final MediaValidationService service = new MediaValidationService(new FileSignatureDetector());

    @Test void videoAndVideoWithAudioPassButAudioOnlyFails() throws Exception {
        for (String name : new String[]{"video.mp4", "video-audio.mp4"}) {
            try (var input = getClass().getResourceAsStream("/media/" + name)) {
                assertNotNull(input);
                assertNotNull(service.validate(MediaUsage.POST_ATTACHMENT, name, "video/mp4", input, OutputStream.nullOutputStream()));
            }
        }
        try (var input = getClass().getResourceAsStream("/media/audio.mp4")) {
            assertNotNull(input);
            var error = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                    "audio.mp4", "video/mp4", input, OutputStream.nullOutputStream()));
            assertEquals(ValidationFailure.UNKNOWN_CONTENT, error.getReason());
        }
    }
}
