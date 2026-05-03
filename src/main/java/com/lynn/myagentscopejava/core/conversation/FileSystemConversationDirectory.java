package com.lynn.myagentscopejava.core.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 基于文件系统的 {@link ConversationDirectory} 实现。
 *
 * <p>每个用户的会话索引落盘为 {@code <baseDir>/_users/<userId>.json}，
 * 内容是按更新时间倒序的 {@link Conversation} 数组。
 *
 * <p>用一个 per-user 的 {@link ReentrantLock} 保护索引读写。
 */
public class FileSystemConversationDirectory implements ConversationDirectory {

    private static final String INDEX_DIR = "_users";

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final java.util.Map<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public FileSystemConversationDirectory(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir.resolve(INDEX_DIR));
        } catch (IOException e) {
            throw new RuntimeException("无法创建会话索引目录：" + baseDir, e);
        }
    }

    @Override
    public List<Conversation> list(String userId) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            return readIndex(userId).stream()
                    .sorted(Comparator.comparingLong(Conversation::updatedAt).reversed())
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Conversation create(String userId, String title, String provider) {
        validateUserId(userId);
        long now = System.currentTimeMillis();
        Conversation c = new Conversation("c-" + UUID.randomUUID().toString().substring(0, 8),
                (title == null || title.isBlank()) ? "新对话" : title,
                provider,  // null = 走默认 provider
                now, now);
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            List<Conversation> list = readIndex(userId);
            list.add(c);
            writeIndex(userId, list);
        } finally {
            lock.unlock();
        }
        return c;
    }

    @Override
    public boolean delete(String userId, String convId) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            List<Conversation> list = readIndex(userId);
            boolean removed = list.removeIf(c -> c.id().equals(convId));
            if (removed) writeIndex(userId, list);
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Conversation> get(String userId, String convId) {
        return list(userId).stream().filter(c -> c.id().equals(convId)).findFirst();
    }

    @Override
    public Optional<Conversation> rename(String userId, String convId, String title) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            List<Conversation> list = readIndex(userId);
            Optional<Conversation> updated = Optional.empty();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id().equals(convId)) {
                    Conversation c = list.get(i).withTitle(title);
                    list.set(i, c);
                    updated = Optional.of(c);
                    break;
                }
            }
            if (updated.isPresent()) writeIndex(userId, list);
            return updated;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void touch(String userId, String convId) {
        ReentrantLock lock = lockFor(userId);
        lock.lock();
        try {
            List<Conversation> list = readIndex(userId);
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id().equals(convId)) {
                    list.set(i, list.get(i).touch());
                    changed = true;
                    break;
                }
            }
            if (changed) writeIndex(userId, list);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(String userId) {
        return userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    private Path indexFile(String userId) {
        return baseDir.resolve(INDEX_DIR).resolve(userId + ".json");
    }

    private List<Conversation> readIndex(String userId) {
        Path file = indexFile(userId);
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            List<Conversation> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<Conversation>>() {});
            // 防御性拷贝，避免外部修改返回的 List 后误写回
            return new ArrayList<>(list);
        } catch (IOException e) {
            throw new RuntimeException("读取会话索引失败：" + file, e);
        }
    }

    private void writeIndex(String userId, List<Conversation> list) {
        Path file = indexFile(userId);
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), list);
        } catch (IOException e) {
            throw new RuntimeException("写入会话索引失败：" + file, e);
        }
    }

    private static void validateUserId(String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("userId 必须匹配 [A-Za-z0-9_-]+，实际：" + userId);
        }
    }
}
