package rrnmask.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code --key value}, repeatable keys, boolean {@code --flag}s, and positionals. */
final class Args {
    final Map<String, List<String>> options = new HashMap<>();
    final List<String> positional = new ArrayList<>();

    Args(String[] argv, Set<String> flags) {
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) {
                positional.add(a);
                continue;
            }
            String key = a.substring(2);
            if (flags.contains(key)) {
                options.computeIfAbsent(key, k -> new ArrayList<>()).add("true");
            } else if (i + 1 < argv.length) {
                options.computeIfAbsent(key, k -> new ArrayList<>()).add(argv[++i]);
            } else {
                throw new IllegalArgumentException("--" + key + " needs a value");
            }
        }
    }

    String get(String key, String fallback) {
        List<String> v = options.get(key);
        return v == null ? fallback : v.getLast();
    }

    String require(String key) {
        String v = get(key, null);
        if (v == null) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return v;
    }

    int getInt(String key, int fallback) {
        return Integer.parseInt(get(key, Integer.toString(fallback)));
    }

    boolean has(String key) {
        return options.containsKey(key);
    }

    List<String> all(String key) {
        return options.getOrDefault(key, List.of());
    }
}
