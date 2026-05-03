package com.lynn.myagentscopejava.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 落盘版 {@link Session} 实现：每个槽位写为
 * {@code <baseDir>/<sessionKey>/<slot>.json}。可在 JVM 重启后恢复状态。
 */
public class FileSystemSession implements Session {

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * @param baseDir 持久化根目录；不存在时会自动创建
     */
    public FileSystemSession(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 session 目录：" + baseDir, e);
        }
    }

    @Override
    public void save(SessionKey key, String slot, Object value) {
        try {
            Path dir = baseDir.resolve(key.value());
            Files.createDirectories(dir);
            Path file = dir.resolve(slot + ".json");
            mapper.writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new RuntimeException("保存失败：" + key + "/" + slot, e);
        }
    }

    @Override
    public <T> Optional<T> get(SessionKey key, String slot, Class<T> type) {
        Path file = baseDir.resolve(key.value()).resolve(slot + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), type));
        } catch (IOException e) {
            throw new RuntimeException("加载失败：" + key + "/" + slot, e);
        }
    }

    @Override
    public void delete(SessionKey key) {
        Path dir = baseDir.resolve(key.value());
        if (!Files.exists(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            // 倒序删除，先删文件再删空目录
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            throw new RuntimeException("删除失败：" + key, e);
        }
    }

    @Override
    public boolean exists(SessionKey key) {
        Path dir = baseDir.resolve(key.value());
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }
}
