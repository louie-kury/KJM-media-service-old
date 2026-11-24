package com.louiekury.media.validation;

import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.ValidationFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private final FileSignatureDetector detector = new FileSignatureDetector();
    private final MediaValidationService service = new MediaValidationService(detector);

    @Test void mismatchStopsReadingAtPrefixAndWritesNothingEvenWithShortReads() {
        byte[] payload = new byte[100_000];
        System.arraycopy(PNG, 0, payload, 0, PNG.length);
        var input = new CountingShortInput(payload, 13);
        var output = new ByteArrayOutputStream();
        var error = assertThrows(MediaValidationException.class, () -> service.validate(
                MediaUsage.POST_ATTACHMENT, "file.mp4", "video/mp4", input, output));
        assertEquals(ValidationFailure.TYPE_MISMATCH, error.getReason());
        assertTrue(input.bytesRead <= 4096, "mismatched payload beyond prefix must not be read");
        assertEquals(0, output.size(), "mismatched prefix must not be written");
    }

    @Test void validPngShortReadsPreserveAllBytesAndShortFileAtEof() {
        for (int size : new int[]{PNG.length, 4095, 4096, 4097, 8199}) {
            byte[] payload = Arrays.copyOf(PNG, size);
            var input = new CountingShortInput(payload, 7);
            var output = new ByteArrayOutputStream();
            var result = service.validate(MediaUsage.POST_ATTACHMENT, "file.png", "image/png", input, output);
            assertEquals(size, result.getSize());
            assertArrayEquals(payload, output.toByteArray());
        }
    }

    @Test void readFailureDuringPrefixDoesNotWriteAnyBytes() {
        InputStream input = new InputStream() {
            boolean first = true;
            @Override public int read() throws IOException { throw new IOException("read failed"); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (!first) throw new IOException("read failed");
                first = false;
                System.arraycopy(PNG, 0, bytes, offset, PNG.length);
                return PNG.length;
            }
        };
        var output = new ByteArrayOutputStream();
        var error = assertThrows(MediaValidationException.class, () -> service.validate(
                MediaUsage.POST_ATTACHMENT, "file.png", "image/png", input, output));
        assertEquals(ValidationFailure.READ_ERROR, error.getReason());
        assertEquals(0, output.size());
    }

    @Test void extendedFtypAcceptsCompatibleBrandAndRejectsInvalidSizes() {
        byte[] box = extendedFtyp(28);
        assertEquals(MediaType.MP4, detector.detect(box, box.length));
        byte[] limitBox = Arrays.copyOf(box, 4096);
        ByteBuffer.wrap(limitBox).putLong(8, 4096);
        assertEquals(MediaType.MP4, detector.detect(limitBox, limitBox.length));
        assertNull(detector.detect(box, 15));
        assertNull(detector.detect(box, 23));
        assertNull(detector.detect(extendedFtyp(23), box.length));
        assertNull(detector.detect(extendedFtyp(27), box.length));
        assertNull(detector.detect(extendedFtyp(4097), box.length));
        assertNull(detector.detect(extendedFtyp(-1), box.length));
    }

    private static byte[] extendedFtyp(long size) {
        return ByteBuffer.allocate(28).putInt(1).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .putLong(size).put("zzzz".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put("mp42".getBytes(StandardCharsets.US_ASCII)).array();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MEDIA_FFPROBE_PATH", matches = ".+")
    void ffprobeFailureIsDifferentFromSuccessfulAudioOnly(@TempDir Path temp) throws Exception {
        Path audio = temp.resolve("audio.mp4");
        try (InputStream source = getClass().getResourceAsStream("/media/audio.mp4")) {
            assertNotNull(source);
            Files.copy(source, audio);
        }
        assertFalse(detector.hasVideoTrack(audio));
        Path invalid = temp.resolve("invalid.mp4");
        Files.write(invalid, new byte[]{0, 1, 2, 3});
        IOException error = assertThrows(IOException.class, () -> detector.hasVideoTrack(invalid));
        assertTrue(error.getMessage().matches("(?s).*\\b[1-9][0-9]*\\b.*"), "diagnostic should retain nonzero exit status");
    }

    @Test void abnormalProbeExitOnValidVideoKeepsExitStatus(@TempDir Path temp) throws Exception {
        Path video = temp.resolve("valid.mp4");
        try (InputStream source = getClass().getResourceAsStream("/media/video.mp4")) {
            assertNotNull(source);
            Files.copy(source, video);
        }
        // The installed JVM executable rejects ffprobe-specific arguments and exits nonzero on every platform.
        String executable = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        IOException error = assertThrows(IOException.class, () -> new FileSignatureDetector(java).hasVideoTrack(video));
        assertTrue(error.getMessage().matches("(?s).*\\b[1-9][0-9]*\\b.*"), "nonzero exit status must be retained");
    }

    private static final class CountingShortInput extends InputStream {
        private final ByteArrayInputStream input;
        private final int chunkSize;
        int bytesRead;

        CountingShortInput(byte[] bytes, int chunkSize) {
            input = new ByteArrayInputStream(bytes);
            this.chunkSize = chunkSize;
        }

        @Override public int read() {
            int value = input.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) {
            int count = input.read(bytes, offset, Math.min(chunkSize, length));
            if (count > 0) bytesRead += count;
            return count;
        }
    }
}
