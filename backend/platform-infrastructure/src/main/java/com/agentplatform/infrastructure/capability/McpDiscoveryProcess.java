package com.agentplatform.infrastructure.capability;

import com.agentplatform.domain.files.FileFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import tools.jackson.databind.json.JsonMapper;

/** Starts one bounded trusted child; transport errors never become a replayable success. */
public final class McpDiscoveryProcess {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_INPUT = 131072;
    private static final int MAX_OUTPUT = 16 * 1024 * 1024;
    private final InetSocketAddress proxy;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean poisoned = new AtomicBoolean();
    private final Duration timeout;
    private final int outputLimit;
    private final List<String> command;

    public McpDiscoveryProcess(InetSocketAddress proxy) {
        this(proxy, null);
    }
    /** Optional operator-provided public trust store; never supplied by a connector user. */
    public McpDiscoveryProcess(InetSocketAddress proxy, Path trustStore) {
        this(proxy, Duration.ofSeconds(110), MAX_OUTPUT, childCommand(trustStore));
    }
    McpDiscoveryProcess(InetSocketAddress proxy, Duration timeout, int outputLimit, List<String> command) {
        this.proxy = Objects.requireNonNull(proxy); this.timeout = Objects.requireNonNull(timeout);
        this.command = List.copyOf(command); this.outputLimit = outputLimit;
        if (proxy.getPort() < 1 || proxy.getPort() > 65535 || timeout.isNegative() || timeout.isZero()
                || outputLimit < 1 || outputLimit > MAX_OUTPUT || command.isEmpty()) throw new IllegalArgumentException("MCP_PROCESS_CONFIG_INVALID");
    }
    public List<McpDiscovery.Tool> discover(String endpoint, McpDiscovery.Authentication authentication) {
        Objects.requireNonNull(authentication);
        if (poisoned.get() || !busy.compareAndSet(false, true)) throw unavailable();
        Process process = null; CompletableFuture<byte[]> read = null; CompletableFuture<Void> write = null;
        boolean stopped = false, streamsClosed = false;
        try {
            var input = new McpDiscoveryChild.Input(endpoint, authentication.kind().name(), authentication.header(),
                    authentication.secret(), proxy.getHostString(), proxy.getPort());
            byte[] payload = JSON.writeValueAsBytes(input);
            if (payload.length > MAX_INPUT) { Arrays.fill(payload, (byte) 0); throw new FileFailure(422, "MCP_REQUEST_LIMIT"); }
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Do not inherit credentials, proxy overrides or arbitrary application configuration.
            var environment = builder.environment();
            String windowsRoot = environment.get("SystemRoot"), temp = environment.get("TEMP"), tmp = environment.get("TMP");
            environment.clear();
            if (windowsRoot != null) environment.put("SystemRoot", windowsRoot);
            if (temp != null) environment.put("TEMP", temp);
            if (tmp != null) environment.put("TMP", tmp);
            environment.put("LANG", "C.UTF-8");
            try { process = builder.start(); }
            catch (IOException failure) { Arrays.fill(payload, (byte) 0); throw unavailable(); }
            Process child = process;
            write = CompletableFuture.runAsync(() -> {
                try (var stream = child.getOutputStream()) { stream.write(payload); stream.flush(); }
                catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
                finally { Arrays.fill(payload, (byte) 0); }
            });
            read = CompletableFuture.supplyAsync(() -> {
                try (var stream = child.getInputStream()) {
                    byte[] result = stream.readNBytes(outputLimit + 1);
                    if (result.length > outputLimit) child.destroyForcibly();
                    return result;
                } catch (IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw unavailable();
            stopped = true;
            byte[] output = read.get(5, TimeUnit.SECONDS); write.get(5, TimeUnit.SECONDS); streamsClosed = true;
            if (child.exitValue() != 0 || output.length > outputLimit) throw unavailable();
            var result = JSON.readValue(output, McpDiscoveryChild.Result.class);
            if (result == null || result.tools() == null) throw unavailable();
            if (result.status() != 200) {
                String code = result.code();
                if (code == null || !code.matches("[A-Z0-9_]{1,64}")) code = "MCP_DISCOVERY_UNAVAILABLE";
                throw new FileFailure(result.status() == 422 ? 422 : 503, code);
            }
            return List.copyOf(result.tools());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException failure) {
            if (failure instanceof FileFailure typed) throw typed;
            throw unavailable();
        } finally {
            if (process != null && !stopped) {
                process.destroyForcibly();
                try { stopped = process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (stopped && !streamsClosed) {
                try { awaitClosed(read); awaitClosed(write); streamsClosed = true; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (java.util.concurrent.TimeoutException unconfirmed) { /* Keep the slot poisoned. */ }
            }
            if (process == null || (stopped && streamsClosed)) busy.set(false);
            else { poisoned.set(true); /* Never reuse a slot without confirmed child and pipe termination. */ }
        }
    }
    private static void awaitClosed(CompletableFuture<?> task) throws InterruptedException, java.util.concurrent.TimeoutException {
        if (task == null) return;
        try { task.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException closedWithError) { /* Completion still proves the pipe task exited. */ }
    }
    private static List<String> childCommand(Path trustStore) {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var result = new ArrayList<>(List.of(executable, "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=96m",
                "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=1", "-XX:+ExitOnOutOfMemoryError", "-Dfile.encoding=UTF-8"));
        if (trustStore != null) {
            try {
                Path fixed = trustStore.toAbsolutePath().normalize();
                if (!Files.isRegularFile(fixed, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !fixed.toRealPath().equals(fixed)
                        || Files.size(fixed) > 1024 * 1024) throw new IllegalArgumentException("MCP_TRUST_STORE_INVALID");
                result.add("-Djavax.net.ssl.trustStore=" + fixed);
            } catch (IOException invalid) { throw new IllegalArgumentException("MCP_TRUST_STORE_INVALID", invalid); }
        }
        boolean boot = false;
        try {
            Path path = Path.of(cp);
            if (Files.isRegularFile(path)) try (var jar = new JarFile(path.toFile())) {
                boot = jar.getEntry("BOOT-INF/classes/") != null && jar.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null;
            }
        } catch (IOException | RuntimeException ignored) { /* A normal multi-entry class path is not a Boot archive. */ }
        if (boot) result.add("-Dloader.main=" + McpDiscoveryChild.class.getName());
        result.addAll(List.of("-cp", cp, boot ? "org.springframework.boot.loader.launch.PropertiesLauncher" : McpDiscoveryChild.class.getName()));
        return List.copyOf(result);
    }
    private static FileFailure unavailable() { return new FileFailure(503, "MCP_DISCOVERY_UNAVAILABLE"); }
}
