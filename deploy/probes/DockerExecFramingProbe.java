import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Local Engine transport experiment only. This does not bypass sandbox runtime policy. */
public final class DockerExecFramingProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Fixture ID required");
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .connectionTimeout(Duration.ofSeconds(2)).responseTimeout(Duration.ofSeconds(10)).maxConnections(4).build();
        try (var docker = DockerClientImpl.getInstance(config, transport)) {
            var fixture = docker.inspectContainerCmd(args[0]).exec();
            if (!"true".equals(fixture.getConfig().getLabels().get("r32.framing")) || !fixture.getState().getRunning()) throw new IllegalArgumentException("Wrong fixture");
            String waiting = docker.execCreateCmd(args[0]).withAttachStdout(true).withAttachStderr(true).withAttachStdin(true)
                    .withCmd("python3", "-c", "import sys;print(len(sys.stdin.buffer.read(65537)),flush=True)").exec().getId();
            String framed = docker.execCreateCmd(args[0]).withAttachStdout(true).withAttachStderr(true).withAttachStdin(true)
                    .withCmd("python3", "-c", "import sys;print(len(sys.stdin.buffer.readline(65537)),flush=True)").exec().getId();
            var payload = "{\"action\":\"READ\"}\n".getBytes(StandardCharsets.UTF_8);
            class Capture extends ResultCallback.Adapter<Frame> {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                @Override public void onNext(Frame frame) { bytes.writeBytes(frame.getPayload()); }
            }
            try (var raw = new Capture(); var line = new Capture()) {
                docker.execStartCmd(waiting).withStdIn(new ByteArrayInputStream(payload)).exec(raw);
                boolean rawDone = raw.awaitCompletion(3, TimeUnit.SECONDS);
                docker.execStartCmd(framed).withStdIn(new ByteArrayInputStream(payload)).exec(line);
                boolean lineDone = line.awaitCompletion(5, TimeUnit.SECONDS);
                if (!lineDone || !line.bytes.toString(StandardCharsets.UTF_8).strip().equals(Integer.toString(payload.length))) throw new AssertionError("Framed read did not complete");
                boolean rawStillRunning = Boolean.TRUE.equals(docker.inspectExecCmd(waiting).exec().isRunning());
                docker.stopContainerCmd(args[0]).withTimeout(1).exec();
                if (docker.inspectContainerCmd(args[0]).exec().getState().getRunning()) throw new AssertionError("Fixture stop not confirmed");
                System.out.println("{\"status\":\"PASS\",\"rawReadCompleted\":" + rawDone + ",\"rawReadStillRunning\":" + rawStillRunning
                        + ",\"newlineReadCompleted\":" + lineDone + ",\"scope\":\"Local Engine stdin transport only; not gVisor acceptance\"}");
            }
        }
    }
}
