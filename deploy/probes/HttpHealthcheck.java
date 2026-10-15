import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container-local health check using the pinned JRE, without an extra package feed. */
public final class HttpHealthcheck {
    public static void main(String[] args) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var request = HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(2)).GET().build();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status < 200 || status >= 300) {
            System.exit(1);
        }
    }
}
