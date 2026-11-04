package io.github.xvvx.smartbuildparameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * FR-3 (docs/REQUIREMENTS.md): options produced by a shell command executed on the Jenkins
 * controller with {@code /bin/sh -c} (decision D5 / NFR-2 — configure accordingly). The
 * command prints one option per output line; blank lines and trailing whitespace are removed.
 *
 * <p>Defensive bounds (FR-3 行为规范 4/5): wall-clock {@code timeoutSeconds}, output capped at
 * {@code maxOutputBytes} (over-limit is an error state, not silent truncation), and the
 * thread-safe TTL cache inherited from {@link SmartSelectSource}.</p>
 */
public class ShellSelectSource extends SmartSelectSource {

    private static final long serialVersionUID = 1L;

    static final int DEFAULT_TIMEOUT_SECONDS = 5;
    static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    /** How much of the command's stderr to keep for the inline error message. */
    private static final int STDERR_KEEP_BYTES = 2048;

    private final String command;
    private final int timeoutSeconds;
    private final int cacheTtlSeconds;
    private final int maxOutputBytes;

    @DataBoundConstructor
    public ShellSelectSource(String command, Integer timeoutSeconds, Integer cacheTtlSeconds, Integer maxOutputBytes) {
        this.command = Util.fixNull(command);
        this.timeoutSeconds = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        this.cacheTtlSeconds = cacheTtlSeconds == null || cacheTtlSeconds < 0 ? 0 : cacheTtlSeconds;
        this.maxOutputBytes = maxOutputBytes == null || maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
    }

    public String getCommand() {
        return command;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    @Override
    protected int cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    @Override
    @NonNull
    protected List<String> doFetch() throws SmartSourceException {
        if (command.trim().isEmpty()) {
            throw new SmartSourceException(Messages.ShellSelectSource_Command_empty());
        }
        return cleanOptions(runCommand());
    }

    private List<String> runCommand() throws SmartSourceException {
        Process proc;
        try {
            proc = new ProcessBuilder("/bin/sh", "-c", command).start();
        } catch (IOException e) {
            throw new SmartSourceException(Messages.ShellSelectSource_Failed_to_start(e.getMessage()));
        }
        BoundedReader stdout = new BoundedReader(proc.getInputStream(), maxOutputBytes);
        BoundedReader stderr = new BoundedReader(proc.getErrorStream(), STDERR_KEEP_BYTES);
        stdout.start();
        stderr.start();

        boolean finished;
        try {
            finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SmartSourceException(Messages.ShellSelectSource_Interrupted());
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new SmartSourceException(stdout.overLimit
                    ? Messages.ShellSelectSource_Output_too_large(maxOutputBytes)
                    : Messages.ShellSelectSource_Timeout(timeoutSeconds));
        }
        if (stdout.overLimit) {
            proc.destroyForcibly();
            throw new SmartSourceException(Messages.ShellSelectSource_Output_too_large(maxOutputBytes));
        }
        joinQuietly(stdout);
        joinQuietly(stderr);
        int exit = proc.exitValue();
        if (exit != 0) {
            String detail = firstLine(stderr.asString());
            throw new SmartSourceException(
                    Messages.ShellSelectSource_Exit_code(exit, detail.isEmpty() ? "" : ": " + detail));
        }
        return parseLines(stdout.asString());
    }

    /** One option per line: trailing whitespace removed, blank lines dropped, order preserved, deduplicated. */
    private static List<String> parseLines(String output) {
        List<String> out = new ArrayList<>();
        for (String line : output.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String firstLine(String s) {
        String trimmed = s.trim();
        int nl = trimmed.indexOf('\n');
        return (nl >= 0 ? trimmed.substring(0, nl) : trimmed);
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Drains a process stream in the background, keeping at most {@code limit} bytes, so the
     * process never blocks on a full pipe while the main thread waits for its exit. Setting
     * {@code overLimit} means MORE than {@code limit} bytes were seen (FR-3 超限 error state).
     */
    private static final class BoundedReader extends Thread {

        private final InputStream in;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int limit;
        volatile boolean overLimit;

        BoundedReader(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() < limit) {
                        int take = Math.min(n, limit - out.size());
                        out.write(buf, 0, take);
                        if (take < n) {
                            overLimit = true;
                        }
                    } else {
                        overLimit = true; // keep draining, remember the overflow
                    }
                }
            } catch (IOException ignored) {
                // pipe closed under us — normal when the process is killed on timeout/over-limit
            }
        }

        String asString() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Extension
    @Symbol("shell")
    public static class DescriptorImpl extends hudson.model.Descriptor<SmartSelectSource> {

        @Override
        public String getDisplayName() {
            return Messages.ShellSelectSource_DisplayName();
        }

        /** FR-3: the command is the one required source field. */
        public hudson.util.FormValidation doCheckCommand(@org.kohsuke.stapler.QueryParameter String command) {
            if (Util.fixNull(command).trim().isEmpty()) {
                return hudson.util.FormValidation.error(Messages.ShellSelectSource_Command_empty());
            }
            return hudson.util.FormValidation.ok();
        }
    }
}
