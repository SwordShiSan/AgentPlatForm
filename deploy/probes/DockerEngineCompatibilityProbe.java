import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.time.Duration;
import java.util.Map;

/** Trusted read-only Engine experiment, separate from API/worker and all user execution. */
public final class DockerEngineCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        String container=System.getenv("R31_PROBE_CONTAINER"),image=System.getenv("R31_PROBE_IMAGE");
        if(container==null||!container.matches("[a-f0-9]{64}")||image==null||!image.matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Pinned independent fixture identities required");
        var configuration=DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost("unix:///var/run/docker.sock").withApiVersion("1.45").build();
        var transport=new ApacheDockerHttpClient.Builder().dockerHost(configuration.getDockerHost()).connectionTimeout(Duration.ofSeconds(2))
                .responseTimeout(Duration.ofSeconds(5)).maxConnections(2).build();
        try(var docker=DockerClientImpl.getInstance(configuration,transport)) {
            var version=docker.versionCmd().exec();var found=docker.inspectContainerCmd(container).exec();var helper=docker.inspectImageCmd(image).exec();
            if(!"agentplatform-r31-local".equals(found.getConfig().getLabels().get("com.docker.compose.project"))||!image.equals(helper.getId()))
                throw new IllegalStateException("Wrong fixture identity");
            var jackson2=new com.fasterxml.jackson.databind.ObjectMapper();var jackson3=tools.jackson.databind.json.JsonMapper.builder().build();
            if(!"ok".equals(jackson2.readTree("{\"probe\":\"ok\"}").path("probe").asText())
                    ||!"ok".equals(jackson3.readTree("{\"probe\":\"ok\"}").path("probe").asText()))throw new IllegalStateException("Jackson boundary failed");
            System.out.println(jackson3.writeValueAsString(Map.of("result","PASS","engineVersion",version.getVersion(),"engineApi",version.getApiVersion(),
                    "requestedApi","1.45","dockerJava","3.7.1","transport","HttpClient5/Unix socket","fixtureContainer",found.getId(),"helperImage",helper.getId(),"jackson2And3","PASS")));
        }
    }
}
