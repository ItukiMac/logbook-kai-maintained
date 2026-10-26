package logbook.internal.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** Explicit editor saves only: optimistic byte check, retained original, and atomic replacement. */
public final class QuestDocumentStore {
    private static final int LIMIT = 2 * 1024 * 1024;
    private QuestDocumentStore() { }

    private static Path safe(Path input) throws IOException {
        if (input == null || input.toString().isBlank()) throw new IOException("保存先がありません。");
        for (Path part : input) if (part.toString().equals("..")) throw new IOException("親パスを含む保存先は使用できません。");
        Path path = input.toAbsolutePath().normalize();
        for (Path item = path; item != null; item = item.getParent())
            if (Files.isSymbolicLink(item)) throw new IOException("リンク先への保存は行いません: " + item);
        return path;
    }

    public static byte[] read(Path input) throws IOException {
        Path path = safe(input);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > LIMIT)
            throw new IOException("通常ファイル以外、または大きすぎる任務設定です。");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(LIMIT + 1);
            if (bytes.length > LIMIT) throw new IOException("読込中に設定ファイルが大きくなりました。");
            return bytes;
        }
    }

    /** Returns a retained pre-edit backup, or null for a new/no-op save. Never deletes a backup. */
    public static synchronized Path save(Path input, byte[] expected, byte[] replacement) throws IOException {
        if (replacement == null || replacement.length > LIMIT) throw new IOException("保存する設定が不正です。");
        Path path = safe(input);
        if (!Arrays.equals(expected, read(path))) throw new IOException("読込後にファイルが変更されています。再読込してください。");
        if (Arrays.equals(expected, replacement)) return null;
        if (!Files.exists(path.getParent(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path.getParent());
        safe(path);
        String suffix = UUID.randomUUID().toString();
        Path backup = null;
        if (expected != null) {
            backup = path.resolveSibling(path.getFileName() + ".before-" + suffix + ".bak");
            writeNew(backup, expected);
            if (!Arrays.equals(expected, read(backup))) throw new IOException("変更前のバックアップ検証に失敗しました。");
        }
        Path pending = path.resolveSibling(path.getFileName() + ".pending-" + suffix);
        writeNew(pending, replacement);
        // A concurrent change aborts activation. Both backup and pending bytes are retained.
        if (!Arrays.equals(expected, read(path))) throw new IOException("保存準備中にファイルが変更されました。元データは上書きしません。");
        safe(path);
        if (expected == null) {
            // CREATE_NEW prevents a competing newly-created file from being replaced.
            // Link activation is atomic and no-overwrite; retain the prepared file as evidence.
            Files.createLink(path, pending);
        } else {
            // No non-atomic fallback. On an unsupported filesystem the original stays intact.
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return backup;
    }

    /** Remove from the active filename only; keep both a verified copy and the original file. */
    public static synchronized Path remove(Path input, byte[] expected) throws IOException {
        Path path = safe(input);
        byte[] actual = read(path);
        if (!Arrays.equals(expected, actual)) throw new IOException("読込後に設定が変更されています。除去を中止しました。");
        if (actual == null) return null;
        String suffix = UUID.randomUUID().toString();
        Path backup = path.resolveSibling(path.getFileName() + ".before-remove-" + suffix + ".bak");
        writeNew(backup, actual);
        if (!Arrays.equals(actual, read(backup))) throw new IOException("除去前のバックアップ検証に失敗しました。");
        if (!Arrays.equals(expected, read(path))) throw new IOException("除去準備中に設定が変更されました。元設定はそのまま保持します。");
        safe(path);
        Path retained = path.resolveSibling(path.getFileName() + ".removed-" + suffix + ".bak");
        if (Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) throw new IOException("退避先が既に存在します。");
        // No deletion: the original is atomically renamed to its unique retained path.
        // On unsupported filesystems, fail without a non-atomic fallback.
        Files.move(path, retained, StandardCopyOption.ATOMIC_MOVE);
        return retained;
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
