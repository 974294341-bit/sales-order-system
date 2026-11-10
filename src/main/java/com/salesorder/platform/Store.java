package com.salesorder.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 仓储层底座：JSON 文件持久化 + 内存索引 + 读写锁 + 原子替换写入 + 可回滚迁移 + 事务。
 * 零第三方依赖，因此不用 JDBC/连接池；换成 Spring Boot 时这一层可直接替换为 JPA Repository。
 */
public final class Store {
    public static final int CURRENT_SCHEMA = 3;

    public static final String USERS = "users";
    public static final String CUSTOMERS = "customers";
    public static final String PRODUCTS = "products";
    public static final String ORDERS = "orders";
    public static final String APPROVALS = "approvals";
    public static final String CHANGES = "changes";
    public static final String DELIVERY_NOTES = "delivery_notes";
    public static final String INVENTORY_LOGS = "inventory_logs";
    public static final String LIAISONS = "liaisons";
    public static final String FORECASTS = "forecasts";
    public static final String DELIVERY_REPORTS = "delivery_reports";
    public static final String CYCLE_TABLES = "cycle_tables";

    public static final List<String> TABLES = List.of(
            USERS, CUSTOMERS, PRODUCTS, ORDERS, APPROVALS, CHANGES, DELIVERY_NOTES,
            INVENTORY_LOGS, LIAISONS, FORECASTS, DELIVERY_REPORTS, CYCLE_TABLES);

    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ThreadLocal<Boolean> batch = ThreadLocal.withInitial(() -> false);
    private Map<String, Map<String, Map<String, Object>>> tables = new LinkedHashMap<>();
    private int schemaVersion = 0;

    public Store(Config config) {
        this.file = config.dataDir.resolve("store.json");
        load();
        migrateTo(CURRENT_SCHEMA);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(file)) {
            for (String t : TABLES) tables.put(t, new LinkedHashMap<>());
            persist();
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> root = Json.asMap(Json.parse(text));
            schemaVersion = Json.integer(root.get("schemaVersion"), 0);
            Map<String, Object> raw = Json.asMap(root.get("tables"));
            for (String t : TABLES) {
                Map<String, Object> table = Json.asMap(raw.get(t));
                Map<String, Map<String, Object>> typed = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : table.entrySet()) {
                    typed.put(e.getKey(), Json.asMap(e.getValue()));
                }
                tables.put(t, typed);
            }
        } catch (Exception e) {
            throw new IllegalStateException("数据文件损坏，请检查 " + file + " : " + e.getMessage(), e);
        }
    }

    /** 可回滚的迁移：按版本号顺序 up，回退时逆序 down。 */
    private void migrateTo(int target) {
        if (target < 1 || target > CURRENT_SCHEMA) {
            throw new IllegalStateException("不支持的 schema 版本: " + target);
        }
        lock.writeLock().lock();
        try {
            if (schemaVersion > target) {
                for (int v = schemaVersion; v > target; v--) migration(v).down(tables);
            } else {
                for (int v = schemaVersion + 1; v <= target; v++) migration(v).up(tables);
            }
            schemaVersion = target;
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Migration migration(int version) {
        return switch (version) {
            case 1 -> new Migration() {
                public void up(Map<String, Map<String, Map<String, Object>>> t) {
                    for (String name : TABLES) t.computeIfAbsent(name, k -> new LinkedHashMap<>());
                }
                public void down(Map<String, Map<String, Map<String, Object>>> t) { t.clear(); }
            };
            case 2 -> new Migration() {
                public void up(Map<String, Map<String, Map<String, Object>>> t) {
                    for (Map<String, Object> o : t.getOrDefault(ORDERS, new LinkedHashMap<>()).values()) {
                        o.putIfAbsent("source", "DOMESTIC");
                        o.putIfAbsent("rush", false);
                    }
                }
                public void down(Map<String, Map<String, Map<String, Object>>> t) {
                    for (Map<String, Object> o : t.getOrDefault(ORDERS, new LinkedHashMap<>()).values()) {
                        o.remove("source");
                        o.remove("rush");
                    }
                }
            };
            // v3：业务框架优化 —— 版本历史、财务前置、急插单前置、库存短路、SLA 升级标记
            case 3 -> new Migration() {
                public void up(Map<String, Map<String, Map<String, Object>>> t) {
                    for (Map<String, Object> o : t.getOrDefault(ORDERS, new LinkedHashMap<>()).values()) {
                        o.putIfAbsent("version", 1);
                        o.putIfAbsent("changeHistory", new ArrayList<>());
                        o.putIfAbsent("financeChecked", false);
                        o.putIfAbsent("rushApproved", false);
                        o.putIfAbsent("stockShortcut", false);
                        o.putIfAbsent("priority", Json.bool(o.get("rush"), false) ? "RUSH" : "NORMAL");
                        o.putIfAbsent("salesType", "NORMAL");
                    }
                    for (Map<String, Object> a : t.getOrDefault(APPROVALS, new LinkedHashMap<>()).values()) {
                        a.putIfAbsent("escalated", false);
                    }
                }
                public void down(Map<String, Map<String, Map<String, Object>>> t) {
                    for (Map<String, Object> o : t.getOrDefault(ORDERS, new LinkedHashMap<>()).values()) {
                        for (String k : List.of("version", "changeHistory", "financeChecked",
                                "rushApproved", "stockShortcut", "priority")) o.remove(k);
                    }
                    for (Map<String, Object> a : t.getOrDefault(APPROVALS, new LinkedHashMap<>()).values()) {
                        a.remove("escalated");
                    }
                }
            };
            default -> throw new IllegalStateException("未知迁移版本 " + version);
        };
    }

    private interface Migration {
        void up(Map<String, Map<String, Map<String, Object>>> tables);
        void down(Map<String, Map<String, Map<String, Object>>> tables);
    }

    private synchronized void persist() {
        if (Boolean.TRUE.equals(batch.get())) return;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", schemaVersion);
        root.put("updatedAt", Ids.now());
        root.put("tables", tables);
        Path tmp = file.resolveSibling("store.json.tmp");
        try {
            Files.writeString(tmp, Json.stringify(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("持久化失败: " + file, e);
        }
    }

    /** 多步写入放进事务；异常时回滚内存快照，不留下半成品数据。 */
    @SuppressWarnings("unchecked")
    public <T> T tx(Supplier<T> work) {
        lock.writeLock().lock();
        Object backup = null;
        try {
            backup = Json.copy(tables);
            batch.set(true);
            T result = work.get();
            batch.set(false);
            persist();
            return result;
        } catch (RuntimeException e) {
            if (backup != null) tables = (Map<String, Map<String, Map<String, Object>>>) backup;
            batch.set(false);
            persist();
            throw e;
        } finally {
            batch.set(false);
            lock.writeLock().unlock();
        }
    }

    private Map<String, Map<String, Object>> table(String name) {
        return tables.computeIfAbsent(name, k -> new LinkedHashMap<>());
    }

    public List<Map<String, Object>> list(String name) {
        lock.readLock().lock();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : table(name).values()) out.add(copy(row));
            return out;
        } finally { lock.readLock().unlock(); }
    }

    public List<Map<String, Object>> find(String name, Predicate<Map<String, Object>> predicate) {
        lock.readLock().lock();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : table(name).values()) {
                Map<String, Object> copy = copy(row);
                if (predicate.test(copy)) out.add(copy);
            }
            return out;
        } finally { lock.readLock().unlock(); }
    }

    public Map<String, Object> get(String name, String id) {
        lock.readLock().lock();
        try {
            Map<String, Object> row = table(name).get(id);
            return row == null ? null : copy(row);
        } finally { lock.readLock().unlock(); }
    }

    public Map<String, Object> require(String name, String id, String label) {
        Map<String, Object> row = get(name, id);
        if (row == null) throw Errors.notFound(label + "不存在: " + id);
        return row;
    }

    public Map<String, Object> insert(String name, Map<String, Object> row) {
        lock.writeLock().lock();
        try {
            Map<String, Object> data = copy(row);
            String id = Json.str(data.get("id"), "");
            if (id.isBlank()) id = Ids.uuid();
            data.put("id", id);
            data.putIfAbsent("createdAt", Ids.now());
            data.put("updatedAt", Ids.now());
            table(name).put(id, data);
            persist();
            return copy(data);
        } finally { lock.writeLock().unlock(); }
    }

    public Map<String, Object> update(String name, String id, Map<String, Object> patch) {
        lock.writeLock().lock();
        try {
            Map<String, Map<String, Object>> t = table(name);
            Map<String, Object> current = t.get(id);
            if (current == null) throw Errors.notFound("记录不存在: " + id);
            current.putAll(copy(patch));
            current.put("id", id);
            current.put("updatedAt", Ids.now());
            persist();
            return copy(current);
        } finally { lock.writeLock().unlock(); }
    }

    public void delete(String name, String id) {
        lock.writeLock().lock();
        try {
            if (table(name).remove(id) == null) throw Errors.notFound("记录不存在: " + id);
            persist();
        } finally { lock.writeLock().unlock(); }
    }

    public long count(String name) {
        lock.readLock().lock();
        try { return table(name).size(); }
        finally { lock.readLock().unlock(); }
    }

    public int schemaVersion() { return schemaVersion; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> row) {
        return (Map<String, Object>) Json.copy(row);
    }
}
