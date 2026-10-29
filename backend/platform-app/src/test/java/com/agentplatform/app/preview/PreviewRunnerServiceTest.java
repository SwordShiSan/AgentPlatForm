package com.agentplatform.app.preview;

import com.agentplatform.api.workspace.RunnerControlApi.SignedResult;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.preview.PreviewData.*;
import com.agentplatform.domain.preview.PreviewRepository;
import com.agentplatform.domain.preview.PreviewRuntime;
import com.agentplatform.domain.workspace.RunnerInstance;
import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.agentplatform.infrastructure.workspace.RunnerSignatures;
import com.agentplatform.infrastructure.workspace.SignedPreviewRunnerClient;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.json.JsonMapper;

/** Real JOSE/application lifecycle; physical stop and object storage are deterministic ports, not Linux evidence. */
class PreviewRunnerServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] SOURCE = "immutable input".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OUTPUT = "derived preview".getBytes(StandardCharsets.UTF_8);
    private static RunnerSignatures control, runner;
    @TempDir Path temporary;
    private final PreviewRepository repository = mock(PreviewRepository.class);
    private final PreviewRuntime runtime = mock(PreviewRuntime.class);
    private final AssetStore assets = mock(AssetStore.class);
    private final RunnerInstance instance = new RunnerInstance(UUID.randomUUID(), "c".repeat(64), "sha256:" + "d".repeat(64), "2026-10-29T00:00:00Z");
    private final AtomicReference<Receipt> durable = new AtomicReference<>();
    private final CountDownLatch stored = new CountDownLatch(1);
    private PreviewRunnerService service;
    private Command command;
    private Path root;
    private ByteArrayInputStream source;

    @BeforeAll static void signatures() throws Exception {
        var a = new RSAKeyGenerator(3072).keyID("preview-control").generate();
        var b = new RSAKeyGenerator(3072).keyID("preview-runner").generate();
        control = new RunnerSignatures(a, b.toPublicJWK(), "control", "runner", Clock.systemUTC());
        runner = new RunnerSignatures(b, a.toPublicJWK(), "runner", "control", Clock.systemUTC());
    }

    @BeforeEach void fixture() throws Exception {
        root = temporary.toRealPath().resolve("preview-staging");
        command = new Command(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "sample.docx", "DOCX", "AUTO", "private-source", SOURCE.length, RunnerSignatures.sha256(SOURCE), "sha256:" + "e".repeat(64),
                "preview-test-bundle", "f".repeat(64), 1, UUID.randomUUID(), 7, Instant.now().plusSeconds(120), "private-derived-output");
        source = spy(new ByteArrayInputStream(SOURCE));
        when(runtime.runnerInstance()).thenReturn(instance);
        when(runtime.policyHash()).thenReturn(command.policyHash());
        when(repository.runnerLookup(command.previewId())).thenReturn(command);
        when(repository.runnerClaim(command, instance.id())).thenReturn(true, false);
        when(repository.runnerObserveNeverAccepted(command, instance.id())).thenReturn(true);
        when(repository.receipt(command)).thenAnswer(call -> durable.get());
        when(assets.open(command.sourceObjectKey(), null, null)).thenReturn(new AssetStore.Content(source, SOURCE.length, SOURCE.length));
        doAnswer(call -> {
            durable.set(call.getArgument(2, Receipt.class));
            stored.countDown();
            return null;
        }).when(repository).runnerReceipt(eq(command), eq(instance.id()), any());
        service = new PreviewRunnerService(repository, runtime, assets, runner, root);
    }

    @AfterEach void close() {
        if (service != null) service.close();
    }

    private Stop stop(boolean confirmed) {
        return new Stop("a".repeat(64), "b".repeat(64), command.imageId(), Instant.parse("2026-10-29T00:00:00Z"),
                confirmed, confirmed, confirmed, confirmed ? Instant.parse("2026-10-29T00:00:01Z") : null);
    }

    private PreviewRuntime.Result referenced(Stop stop) {
        return new PreviewRuntime.Result("READY", "PDF", "application/pdf", SOURCE.length, command.sourceSha256(), 1, null, true, null, false, stop);
    }

    private SignedResult post() {
        byte[] body = JSON.writeValueAsBytes(command);
        return service.operate(control.sign(SignedPreviewRunnerClient.binding(command, "POST", SignedPreviewRunnerClient.PATH), body), body);
    }

    private Receipt decode(SignedResult signed, String path) {
        control.verify(signed.signature(), SignedPreviewRunnerClient.binding(command, "RESPONSE", path), signed.body());
        return JSON.readValue(signed.body(), Receipt.class);
    }

    private Receipt query() {
        String path = SignedPreviewRunnerClient.PATH + "/" + command.previewId();
        String token = control.sign(SignedPreviewRunnerClient.binding(command, "GET", path), new byte[0]);
        return decode(service.query(token, command.previewId().toString()), path);
    }

    private void until(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.call(), "Original asynchronous handoff did not reach the expected boundary");
    }

    @Test void postReturnsWhilePhysicalWorkIsBlockedAndRepeatedPostOrQueryNeverStartsAnotherConversion() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(runtime.render(eq(command), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return referenced(stop(true));
        });
        try (var callers = Executors.newSingleThreadExecutor()) {
            var request = callers.submit(this::post);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertNull(decode(request.get(3, TimeUnit.SECONDS), SignedPreviewRunnerClient.PATH));
                assertNull(decode(post(), SignedPreviewRunnerClient.PATH));
                assertNull(query());
                verify(runtime, never()).reconcile(any(), any(), any());
                verify(repository, never()).runnerReceipt(any(), any(), any());
                verify(runtime, times(1)).render(eq(command), any(), any(), any());
            } finally {
                release.countDown();
            }
        }
        assertTrue(stored.await(5, TimeUnit.SECONDS));
        assertEquals("READY", query().state());
        verify(runtime, times(1)).render(eq(command), any(), any(), any());
    }

    @Test void sourceActuallyClosesAndDurableTransferReleasePrecedesOutputUploadAndReceiptPrecedesCleanup() throws Exception {
        var released = new AtomicBoolean();
        doAnswer(call -> { verify(source).close(); released.set(true); return null; })
                .when(repository).recordSourceTransferClosed(command, instance.id());
        when(runtime.render(eq(command), any(), any(), any())).thenAnswer(call -> {
            assertTrue(released.get());
            assertArrayEquals(SOURCE, Files.readAllBytes(call.getArgument(1, Path.class)));
            Path output = call.getArgument(2, Path.class);
            Files.createDirectory(output);
            Path file = output.resolve("preview.pdf");
            Files.write(file, OUTPUT);
            return new PreviewRuntime.Result("READY", "PDF", "application/pdf", OUTPUT.length, RunnerSignatures.sha256(OUTPUT), 1, file, false, null, false, stop(true));
        });
        when(assets.put(eq(command.outputObjectKey()), eq((long) OUTPUT.length), eq(RunnerSignatures.sha256(OUTPUT)), any())).thenAnswer(call -> {
            assertTrue(released.get());
            assertArrayEquals(OUTPUT, call.getArgument(3, InputStream.class).readAllBytes());
            return new AssetStore.Identity(command.outputObjectKey(), OUTPUT.length, RunnerSignatures.sha256(OUTPUT));
        });
        post();
        assertTrue(stored.await(5, TimeUnit.SECONDS));
        verify(runtime, timeout(3000)).retire(eq(command), any());
        var order = inOrder(source, repository, runtime, assets);
        order.verify(source).close();
        order.verify(repository).recordSourceTransferClosed(command, instance.id());
        order.verify(runtime).render(eq(command), any(), any(), any());
        order.verify(assets).put(eq(command.outputObjectKey()), eq((long) OUTPUT.length), eq(RunnerSignatures.sha256(OUTPUT)), any());
        order.verify(repository).runnerReceipt(eq(command), eq(instance.id()), any());
        order.verify(runtime).retire(eq(command), any());
        assertEquals(command.outputObjectKey(), query().objectKey());
    }

    @Test void failedSourceCloseDoesNotProduceAReleaseFactOrStartOrPublishAnotherOperation() throws Exception {
        var failedClose = new CountDownLatch(1);
        var input = new ByteArrayInputStream(SOURCE) {
            @Override public void close() throws IOException { failedClose.countDown(); throw new IOException("Close unconfirmed"); }
        };
        when(assets.open(command.sourceObjectKey(), null, null)).thenReturn(new AssetStore.Content(input, SOURCE.length, SOURCE.length));
        post();
        assertTrue(failedClose.await(5, TimeUnit.SECONDS));
        assertNull(query());
        verify(repository, never()).recordSourceTransferClosed(any(), any());
        verify(repository, never()).runnerReceipt(any(), any(), any());
        verify(runtime, never()).render(any(), any(), any(), any());
        verify(runtime, never()).reconcile(any(), any(), any());
        verify(assets, never()).put(any(), anyLong(), any(), any());
    }

    @Test void aClosedSourceWithUnavailableDurableReleaseNeverAdvancesToRenderOrOutputUpload() throws Exception {
        var releaseAttempted = new CountDownLatch(1);
        doAnswer(call -> { releaseAttempted.countDown(); throw new FileFailure(503, "STORAGE_UNAVAILABLE"); })
                .when(repository).recordSourceTransferClosed(command, instance.id());
        post();
        assertTrue(releaseAttempted.await(5, TimeUnit.SECONDS));
        assertNull(query());
        verify(source).close();
        verify(runtime, never()).render(any(), any(), any(), any());
        verify(assets, never()).put(any(), anyLong(), any(), any());
        verify(repository, never()).runnerReceipt(any(), any(), any());
    }

    @Test void anUnconfirmedResultIsNotPublishedAndLaterQueryCanConfirmOnlyTheOriginalStoppedInstance() throws Exception {
        var mayConfirm = new AtomicBoolean();
        var reconciled = new AtomicInteger();
        when(runtime.render(eq(command), any(), any(), any())).thenReturn(referenced(stop(false)));
        when(runtime.reconcile(eq(command), any(), any())).thenAnswer(call -> {
            reconciled.incrementAndGet();
            return referenced(stop(mayConfirm.get()));
        });
        post();
        until(() -> { assertNull(query()); return reconciled.get() > 0; });
        verify(repository, never()).runnerReceipt(any(), any(), any());
        verify(assets, never()).put(any(), anyLong(), any(), any());
        mayConfirm.set(true);
        until(() -> query() != null);
        assertEquals("READY", durable.get().state());
        assertTrue(durable.get().stop().confirmed());
        verify(runtime, times(1)).render(eq(command), any(), any(), any());
    }

    @Test void revokedAuthorizationAfterKnownStopRecordsFailedReceiptWithoutPublishingOutputOrLoopingReady() throws Exception {
        var revoked = new AtomicBoolean();
        doAnswer(call -> { if (revoked.get()) throw new FileFailure(403, "REVOKED"); return null; })
                .when(repository).runnerGuard(command, instance.id());
        when(runtime.render(eq(command), any(), any(), any())).thenAnswer(call -> { revoked.set(true); return referenced(stop(true)); });
        post();
        assertTrue(stored.await(5, TimeUnit.SECONDS));
        var result = query();
        assertEquals("FAILED", result.state());
        assertEquals("PREVIEW_AUTHORIZATION_REVOKED", result.errorCode());
        assertFalse(result.retryable());
        assertNull(result.objectKey());
        assertTrue(result.stop().confirmed());
        assertEquals("FAILED", decode(post(), SignedPreviewRunnerClient.PATH).state());
        verify(runtime, times(1)).render(eq(command), any(), any(), any());
        verify(runtime, never()).reconcile(any(), any(), any());
        verify(assets, never()).put(any(), anyLong(), any(), any());
        verify(repository, times(1)).runnerReceipt(eq(command), eq(instance.id()), any());
    }

    @Test void tamperedOrValidlySignedDifferentCommandsAreRejectedBeforePhysicalAdmission() {
        byte[] original = JSON.writeValueAsBytes(command);
        byte[] changed = new String(original, StandardCharsets.UTF_8).replace("\"requestedFormat\":\"AUTO\"", "\"requestedFormat\":\"PDF\"").getBytes(StandardCharsets.UTF_8);
        assertFalse(Arrays.equals(original, changed));
        String token = control.sign(SignedPreviewRunnerClient.binding(command, "POST", SignedPreviewRunnerClient.PATH), original);
        assertEquals("RUNNER_COMMAND_BINDING_REJECTED", assertThrows(WorkspaceFailure.class, () -> service.operate(token, changed)).code());
        String changedToken = control.sign(SignedPreviewRunnerClient.binding(command, "POST", SignedPreviewRunnerClient.PATH), changed);
        assertEquals("PREVIEW_COMMAND_CONFLICT", assertThrows(FileFailure.class, () -> service.operate(changedToken, changed)).getMessage());
        verify(repository, never()).runnerClaim(any(), any());
        verify(runtime, never()).render(any(), any(), any(), any());
        verifyNoInteractions(assets);
    }
}
