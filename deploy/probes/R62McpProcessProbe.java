import com.agentplatform.infrastructure.capability.McpDiscovery;
import com.agentplatform.infrastructure.capability.McpDiscoveryProcess;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/** Non-production launcher for the packaged parent and child discovery path. */
public class R62McpProcessProbe {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("fixture proxy and trust store required");
        var process = new McpDiscoveryProcess(InetSocketAddress.createUnresolved("127.0.0.1", Integer.parseInt(args[0])), Path.of(args[1]));
        var authentication = new McpDiscovery.Authentication(McpDiscovery.AuthKind.BEARER, null, "deterministic-fixture-only");
        var tools = process.discover("https://fixture.invalid:8443/mcp", authentication);
        if (tools.size() != 1 || !"fixture_echo".equals(tools.getFirst().name())) throw new IllegalStateException("FIXTURE_RESULT_MISMATCH");
        System.out.println("R62_PACKAGED_PARENT_CHILD_PASS");
    }
}
