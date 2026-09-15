package untitled.untitled.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ModelRules<T> {
    private final Map<String, T> rules = new LinkedHashMap<>();

    void put(String itemName, T value) {
        rules.put(itemName, value);
    }

    T get(String itemName) {
        return rules.get(itemName);
    }

    boolean remove(String itemName) {
        return rules.remove(itemName) != null;
    }

    void clear() {
        rules.clear();
    }

    int size() {
        return rules.size();
    }

    Set<Map.Entry<String, T>> entries() {
        return rules.entrySet();
    }
}
