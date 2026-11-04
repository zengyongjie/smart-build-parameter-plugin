package io.github.xvvx.smartbuildparameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.AbstractDescribableImpl;
import java.io.Serializable;
import java.util.List;

/**
 * Option source for the smartSelect parameter (FR-3 source=shell, FR-4 source=http —
 * see docs/REQUIREMENTS.md). Both share the same dropdown component and the
 * {@code selectMode/filterable/delimiter} semantics.
 *
 * <p>Implementations return one candidate per list entry (already blank-free) and report
 * failures as {@link SmartSourceException} so the build page renders an inline error
 * instead of a 500 (FR-3 行为规范 4: never white-screen).</p>
 *
 * <p>The base class owns the thread-safe TTL cache (FR-3 行为规范 5 / FR-4 行为规范):
 * successful fetches are cached for {@link #cacheTtlSeconds()} seconds, one execution at a
 * time per configured source, failures are never cached (so "retry" always re-runs).</p>
 */
public abstract class SmartSelectSource extends AbstractDescribableImpl<SmartSelectSource> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Structured, user-presentable source failure (non-zero exit, timeout, over-limit, …). */
    public static class SmartSourceException extends Exception {

        private static final long serialVersionUID = 1L;

        public SmartSourceException(String message) {
            super(message);
        }
    }

    /** TTL cache of the last successful fetch; successes only, so "retry" re-executes after failures. */
    private transient volatile CachedOptions cache;

    private static final class CachedOptions {
        private final long expiresAtMs;
        private final List<String> options;

        CachedOptions(long expiresAtMs, List<String> options) {
            this.expiresAtMs = expiresAtMs;
            this.options = options;
        }
    }

    /**
     * Fetch the option list (cache-transparent).
     *
     * @param forceRefresh bypass the TTL cache (the build page "retry" entry).
     */
    @NonNull
    public final List<String> fetch(boolean forceRefresh) throws SmartSourceException {
        CachedOptions c = cache;
        if (!forceRefresh && c != null && c.expiresAtMs > System.currentTimeMillis()) {
            return c.options;
        }
        synchronized (this) { // one execution at a time per configured source
            c = cache;
            if (!forceRefresh && c != null && c.expiresAtMs > System.currentTimeMillis()) {
                return c.options; // another thread just refreshed it
            }
            List<String> options = doFetch();
            int ttl = cacheTtlSeconds();
            cache = ttl > 0 ? new CachedOptions(System.currentTimeMillis() + ttl * 1000L, options) : null;
            return options;
        }
    }

    /** Actually contact the source; no caching concerns here. */
    @NonNull
    protected abstract List<String> doFetch() throws SmartSourceException;

    /** Seconds to serve the last successful result; 0 = re-evaluate on every form render. */
    protected abstract int cacheTtlSeconds();

    /**
     * Shared option cleanup: trim, drop blanks, keep order, deduplicate — applied by every
     * source so the dropdown and the delimiter-joined value stay unambiguous (增补 A).
     */
    @NonNull
    protected static List<String> cleanOptions(List<String> raw) {
        List<String> out = new java.util.ArrayList<>();
        for (String value : raw) {
            String trimmed = value == null ? "" : value.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
