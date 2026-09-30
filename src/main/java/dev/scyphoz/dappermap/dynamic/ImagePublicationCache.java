package dev.scyphoz.dappermap.dynamic;

import java.util.LinkedHashMap;
import java.util.Map;

/** World-thread bookkeeping for the bounded image paths already sent to this client. */
final class ImagePublicationCache {
    private String activePath;
    private static final java.util.regex.Pattern SLOT = java.util.regex.Pattern.compile("(.*?)([0-9]+)(\\.png)$");
    private final Map<String, String> hashesByPath = new LinkedHashMap<>();

    String pathFor(String hash) {
        for (var entry : hashesByPath.entrySet())
            if (entry.getValue().equals(hash)) {
                activePath = entry.getKey();
                return activePath;
            }
        return null;
    }

    void remember(String path, String hash) {
        activePath = path;
        hashesByPath.remove(path); // Replacing a slot invalidates its previous content.
        hashesByPath.put(path, hash);
        if (hashesByPath.size() > 16) hashesByPath.remove(hashesByPath.keySet().iterator().next());
    }

    String nonActivePath(String candidate) {
        if (!candidate.equals(activePath)) return candidate;
        var match = SLOT.matcher(candidate);
        if (!match.matches()) throw new IllegalArgumentException("Image path lacks a rotating slot: " + candidate);
        return match.group(1) + ((Integer.parseInt(match.group(2)) + 1) & 15) + match.group(3);
    }

    void clear() { hashesByPath.clear(); activePath = null; }
}
