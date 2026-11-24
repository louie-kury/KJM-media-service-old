package com.louiekury.media.validation.adapter.out;

import com.louiekury.media.validation.application.port.out.MediaContentDetector;
import com.louiekury.media.validation.domain.MediaType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 파일의 식별 정보와 영상 스트림을 검사합니다. 파일 전체의 안전성은 보장하지 않습니다. */
public class FileSignatureDetector implements MediaContentDetector {
    private static final Set<String> IMAGE_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis");
    private static final Set<String> MP4_BRANDS = Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso8", "iso9", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash");
    private final String ffprobe;

    public FileSignatureDetector() {
        this(System.getenv().getOrDefault("MEDIA_FFPROBE_PATH", "ffprobe"));
    }

    public FileSignatureDetector(String ffprobe) { this.ffprobe = ffprobe; }

    @Override
    public MediaType detect(byte[] prefix, int length) {
        if (length >= 3 && (prefix[0] & 255) == 0xff && (prefix[1] & 255) == 0xd8 && (prefix[2] & 255) == 0xff) return MediaType.JPEG;
        if (length >= 8 && (prefix[0] & 255) == 0x89 && matches(prefix, 1, "PNG\r\n\u001a\n")) return MediaType.PNG;
        if (length >= 12 && matches(prefix, 0, "RIFF") && matches(prefix, 8, "WEBP")) return MediaType.WEBP;
        if (length >= 16 && matches(prefix, 4, "ftyp")) {
            long size = ((prefix[0] & 255L) << 24) | ((prefix[1] & 255L) << 16) | ((prefix[2] & 255L) << 8) | (prefix[3] & 255L);
            if (size < 16 || size > length || (size - 16) % 4 != 0) return null;
            if (isImageBrand(prefix, 8) || matches(prefix, 8, "qt  ")) return null;
            boolean mp4 = isMp4Brand(prefix, 8);
            for (int offset = 16; offset < size; offset += 4) {
                if (isImageBrand(prefix, offset)) return null;
                mp4 |= isMp4Brand(prefix, offset);
            }
            return mp4 ? MediaType.MP4 : null;
        }
        return null;
    }

    @Override
    public boolean hasVideoTrack(Path path) throws IOException {
        Process process = new ProcessBuilder(ffprobe, "-v", "error", "-protocol_whitelist", "file", "-select_streams", "V",
                "-show_entries", "stream=index", "-of", "csv=p=0", "-i", path.toAbsolutePath().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        final boolean[] found = {false};
        final boolean[] readFailed = {false};
        Thread reader = new Thread(() -> {
            try (var input = process.getInputStream()) {
                int c;
                while ((c = input.read()) != -1) if (c >= '0' && c <= '9') found[0] = true;
            } catch (IOException error) { readFailed[0] = true; }
        }, "media-ffprobe-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                terminate(process);
                throw new IOException("ffprobe timeout");
            }
            reader.join(1000);
            if (reader.isAlive()) throw new IOException("ffprobe output timeout");
            if (readFailed[0]) throw new IOException("ffprobe output read failed");
            return process.exitValue() == 0 && found[0];
        } catch (InterruptedException error) {
            try { terminate(process); } finally { Thread.currentThread().interrupt(); }
            throw new IOException("ffprobe interrupted", error);
        }
    }

    private static void terminate(Process process) throws IOException {
        process.destroyForcibly();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("ffprobe did not terminate");
                try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean isImageBrand(byte[] bytes, int offset) {
        return IMAGE_BRANDS.contains(new String(bytes, offset, 4, StandardCharsets.US_ASCII));
    }

    private boolean isMp4Brand(byte[] bytes, int offset) {
        return MP4_BRANDS.contains(new String(bytes, offset, 4, StandardCharsets.US_ASCII));
    }

    private boolean matches(byte[] bytes, int offset, String expected) {
        byte[] value = expected.getBytes(StandardCharsets.US_ASCII);
        if (offset + value.length > bytes.length) return false;
        for (int i = 0; i < value.length; i++) if (bytes[offset + i] != value[i]) return false;
        return true;
    }
}
