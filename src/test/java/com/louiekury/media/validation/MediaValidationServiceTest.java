package com.louiekury.media.validation;

import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.ValidationFailure;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MediaValidationServiceTest {
    private final MediaValidationService service = new MediaValidationService(new FileSignatureDetector() {
        @Override public boolean hasVideoTrack(java.nio.file.Path file) { return true; }
    });
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 8, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    private static final byte[] MP4 = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0, 'i', 's', 'o', 'm'};

    private void valid(MediaUsage usage, String name, String mime, byte[] bytes) {
        assertNotNull(service.validate(usage, name, mime, new ByteArrayInputStream(bytes), OutputStream.nullOutputStream()));
    }

    private void invalid(ValidationFailure expected, MediaUsage usage, String name, String mime, InputStream input) {
        var ex = assertThrows(MediaValidationException.class,
                () -> service.validate(usage, name, mime, input, OutputStream.nullOutputStream()));
        assertEquals(expected, ex.getReason());
    }

    @Test void acceptsAllowedImageTypesAndPostVideo() {
        for (MediaUsage usage : MediaUsage.values()) {
            valid(usage, "file.jpg", "image/jpeg", JPEG);
            valid(usage, "file.png", "image/png", PNG);
            valid(usage, "file.webp", "image/webp", WEBP);
        }
        valid(MediaUsage.POST_ATTACHMENT, "file.mp4", "video/mp4", MP4);
    }

    @Test void acceptsCompatibleMp4BrandWhenMajorBrandIsUnknown() {
        byte[] compatible = {0, 0, 0, 20, 'f', 't', 'y', 'p', 'z', 'z', 'z', 'z',
                0, 0, 0, 0, 'm', 'p', '4', '2'};
        valid(MediaUsage.POST_ATTACHMENT, "clip.mp4", "video/mp4", compatible);
    }

    @Test void acceptsMimeParametersWithoutChangingFileValidation() {
        valid(MediaUsage.POST_ATTACHMENT, "file.mp4", "video/mp4; codecs=\"avc1.42E01E\"", MP4);
        valid(MediaUsage.POST_ATTACHMENT, "file.jpg", " IMAGE/JPEG ; charset=utf-8", JPEG);
        invalid(ValidationFailure.TYPE_MISMATCH, MediaUsage.POST_ATTACHMENT, "file.jpg",
                "image/png; charset=utf-8", new ByteArrayInputStream(JPEG));
    }

    @Test void rejectsMalformedMimeParameters() {
        for (String mime : new String[]{"video/mp4;", "video/mp4; codecs", "video/mp4; =value",
                "video/mp4; codecs=", "video/mp4; codecs=\"unterminated", "video/mp4; x=y;"}) {
            invalid(ValidationFailure.UNSUPPORTED_TYPE, MediaUsage.POST_ATTACHMENT, "file.mp4", mime,
                    new ByteArrayInputStream(MP4));
        }
    }

    @Test void acceptsLongQuotedMimeParameterWithoutStackOverflow() {
        assertEquals(MediaType.MP4, MediaType.fromContentType("video/mp4; codecs=\"" + "a".repeat(32_000) + "\""));
        assertEquals(MediaType.MP4, MediaType.fromContentType("video/mp4; codecs=\"" + "\\\"".repeat(16_000) + "\""));
        assertEquals(MediaType.MP4, MediaType.fromContentType("video/mp4; x=" + "a".repeat(32_000)));
        assertEquals(MediaType.MP4, MediaType.fromContentType("video/mp4" + ";x=a".repeat(8_000)));
        assertNull(MediaType.fromContentType("video/mp4; codecs=\"" + "a".repeat(32_000)));
    }

    @Test void rejectsTruncatedOrInvalidMp4FileTypeBox() {
        byte[] truncated = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        byte[] tooSmall = {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        for (byte[] data : new byte[][]{truncated, tooSmall}) {
            invalid(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT,
                    "clip.mp4", "video/mp4", new ByteArrayInputStream(data));
        }
    }

    @Test void rejectsHeicBrandsEvenWhenCompatibleWithIsom() {
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c',
                0, 0, 0, 0, 'm', 'i', 'f', '1', 'i', 's', 'o', 'm'};
        invalid(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT,
                "clip.mp4", "video/mp4", new ByteArrayInputStream(heic));
    }

    @Test void failsClosedWhenFfprobeCannotStart() {
        var unavailable = new MediaValidationService(new FileSignatureDetector("missing-ffprobe-executable-xyz"));
        var ex = assertThrows(MediaValidationException.class, () -> unavailable.validate(MediaUsage.POST_ATTACHMENT,
                "file.mp4", "video/mp4", new ByteArrayInputStream(MP4), OutputStream.nullOutputStream()));
        assertEquals(ValidationFailure.READ_ERROR, ex.getReason());
    }

    @Test void videoIsRejectedOutsidePosts() {
        for (MediaUsage usage : MediaUsage.values()) {
            if (usage != MediaUsage.POST_ATTACHMENT) {
                invalid(ValidationFailure.UNSUPPORTED_TYPE, usage, "file.mp4", "video/mp4", new ByteArrayInputStream(MP4));
            }
        }
    }

    @Test void rejectsInconsistentAndUnknownInput() {
        invalid(ValidationFailure.UNSUPPORTED_USAGE, null, "file.jpg", "image/jpeg", new ByteArrayInputStream(JPEG));
        invalid(ValidationFailure.TYPE_MISMATCH, MediaUsage.POST_ATTACHMENT, "file.jpg", "image/png", new ByteArrayInputStream(JPEG));
        invalid(ValidationFailure.TYPE_MISMATCH, MediaUsage.POST_ATTACHMENT, "file.jpg", "image/jpeg", new ByteArrayInputStream(PNG));
        invalid(ValidationFailure.UNKNOWN_CONTENT, MediaUsage.POST_ATTACHMENT, "file.jpg", "image/jpeg", new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        invalid(ValidationFailure.EMPTY_FILE, MediaUsage.POST_ATTACHMENT, "file.jpg", "image/jpeg", new ByteArrayInputStream(new byte[0]));
    }

    @Test void reportsReadError() {
        InputStream broken = new InputStream() { @Override public int read() throws IOException { throw new IOException("test failure"); } };
        invalid(ValidationFailure.READ_ERROR, MediaUsage.POST_ATTACHMENT, "file.jpg", "image/jpeg", broken);
    }

    @Test void preservesValidatedBytesInStagedOutput() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var result = service.validate(MediaUsage.POST_ATTACHMENT, "file.png", "image/png", new ByteArrayInputStream(PNG), output);
        assertArrayEquals(PNG, output.toByteArray());
        assertEquals(PNG.length, result.getSize());
    }

    @Test void reportsStagingWriteError() {
        OutputStream broken = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("write failure"); } };
        var ex = assertThrows(MediaValidationException.class, () -> service.validate(MediaUsage.POST_ATTACHMENT,
                "file.jpg", "image/jpeg", new ByteArrayInputStream(JPEG), broken));
        assertEquals(ValidationFailure.WRITE_ERROR, ex.getReason());
    }

    @Test void imageAndVideoExactLimitAndOneByteOver() {
        assertNotNull(service.validate(MediaUsage.POST_ATTACHMENT, "file.jpg", "image/jpeg", new SizedInput(JPEG, 10_000_000L), OutputStream.nullOutputStream()));
        invalid(ValidationFailure.FILE_TOO_LARGE, MediaUsage.POST_ATTACHMENT, "file.jpg", "image/jpeg", new SizedInput(JPEG, 10_000_001L));
        assertNotNull(service.validate(MediaUsage.POST_ATTACHMENT, "file.mp4", "video/mp4", new SizedInput(MP4, 100_000_000L), OutputStream.nullOutputStream()));
        invalid(ValidationFailure.FILE_TOO_LARGE, MediaUsage.POST_ATTACHMENT, "file.mp4", "video/mp4", new SizedInput(MP4, 100_000_001L));
    }

    private static class SizedInput extends InputStream {
        private final byte[] prefix;
        private final long size;
        private long position;
        SizedInput(byte[] prefix, long size) { this.prefix = prefix; this.size = size; }
        @Override public int read() {
            if (position >= size) return -1;
            return position < prefix.length ? prefix[(int) position++] & 0xff : advance();
        }
        private int advance() { position++; return 0; }
        @Override public int read(byte[] bytes, int offset, int len) {
            if (len == 0) return 0;
            if (position >= size) return -1;
            int n = (int) Math.min(size - position, len);
            for (int i = 0; i < n; i++) bytes[offset + i] = position + i < prefix.length ? prefix[(int) (position + i)] : 0;
            position += n;
            return n;
        }
    }
}
