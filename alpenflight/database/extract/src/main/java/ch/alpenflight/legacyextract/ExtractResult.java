package ch.alpenflight.legacyextract;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record ExtractResult(Path outDir, List<Path> emittedFiles, Duration duration) {}
