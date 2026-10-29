package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.WorkspaceFailure;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** Standard JOSE handles cryptography; the platform binds a token to one exact command. */
public final class RunnerSignatures {
    public record Binding(String method, String path, UUID commandId, UUID operationId,
            UUID spaceId, UUID workspaceId, long generation, long fence) {
        public Binding {
            if (!List.of("GET", "POST", "RESPONSE").contains(method)
                    || !(path.equals("/internal/operations") || path.equals("/internal/operations/" + operationId)
                        || path.equals("/internal/operations/v2/environments") || path.equals("/internal/operations/v2/environments/" + operationId)
                        || path.equals("/internal/operations/v3/environments") || path.equals("/internal/operations/v3/environments/" + operationId)
                        || path.equals("/internal/workspace-uses/v1") || path.equals("/internal/workspace-uses/v1/" + operationId)
                        || path.equals("/internal/workspace-shell/v1") || path.equals("/internal/workspace-shell/v1/" + operationId)
                        || path.equals("/internal/file-operations/v1") || path.equals("/internal/file-operations/v1/"+operationId)
                        || path.equals("/internal/file-captures/v1") || path.equals("/internal/file-captures/v1/"+operationId)
                        || path.equals("/internal/previews/v1") || path.equals("/internal/previews/v1/"+operationId)) || path.contains("?")
                    || path.contains("#") || path.contains("..") || generation < 1 || fence < 1) {
                throw new IllegalArgumentException("Invalid runner binding");
            }
            Objects.requireNonNull(commandId); Objects.requireNonNull(operationId);
            Objects.requireNonNull(spaceId); Objects.requireNonNull(workspaceId);
        }
    }
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final String issuer;
    private final String audience;
    private final String localKid;
    private final String peerKid;
    private final Clock clock;

    public RunnerSignatures(RSAKey local, RSAKey peer, String issuer, String audience, Clock clock) {
        try {
            if (!local.isPrivate() || peer.isPrivate() || local.getModulus().equals(peer.getModulus()) || local.getKeyID() == null || peer.getKeyID() == null
                    || local.getModulus().decodeToBigInteger().bitLength() < 3072
                    || peer.getModulus().decodeToBigInteger().bitLength() < 3072
                    || issuer.equals(audience)) throw new IllegalArgumentException("Separate pinned RSA3072 identities required");
            this.issuer = issuer; this.audience = audience; this.clock = clock;
            localKid = local.getKeyID(); peerKid = peer.getKeyID();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(local)));
            decoder = NimbusJwtDecoder.withPublicKey(peer.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
            var time = new JwtTimestampValidator(Duration.ofSeconds(5));
            time.setClock(clock);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(time,
                    jwt -> audience.equals(jwt.getClaimAsString("iss"))
                            && jwt.getAudience().equals(List.of(issuer))
                            ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        } catch (com.nimbusds.jose.JOSEException invalid) {
            throw new IllegalArgumentException("Invalid runner RSA key", invalid);
        }
    }
    public String sign(Binding binding, byte[] body) {
        bounded(binding,body);
        var now = clock.instant();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(60)).id(binding.commandId().toString())
                .claim("method", binding.method()).claim("path", binding.path())
                .claim("operation", binding.operationId().toString()).claim("space", binding.spaceId().toString())
                .claim("workspace", binding.workspaceId().toString()).claim("generation", binding.generation())
                .claim("fence", binding.fence()).claim("body_sha256", sha256(body)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(localKid).type("JWT").build(), claims)).getTokenValue();
    }
    public Jwt authenticate(String token) {
        try {
            if (token == null || token.length() > 8192) throw new IllegalArgumentException();
            Jwt jwt = decoder.decode(token);
            if (!peerKid.equals(jwt.getHeaders().get("kid")) || !"JWT".equals(jwt.getHeaders().get("typ"))
                    || jwt.getIssuedAt() == null || jwt.getNotBefore() == null || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) || jwt.getNotBefore().isAfter(jwt.getExpiresAt())
                    || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(5))
                    || jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plusSeconds(60))
                    || jwt.getNotBefore().isBefore(jwt.getIssuedAt())
                    || jwt.getId() == null) throw new IllegalArgumentException();
            return jwt;
        } catch (RuntimeException invalid) { throw new WorkspaceFailure(401, "RUNNER_AUTHENTICATION_REJECTED"); }
    }
    public JwtDecoder resourceServerDecoder() {
        return token->{
            try{return authenticate(token);}
            catch(WorkspaceFailure rejected){throw new BadJwtException("Runner token rejected");}
        };
    }
    public void verify(String token, Binding binding, byte[] body) {
        bounded(binding,body);
        Jwt jwt = authenticate(token);
        try {
            boolean equal = binding.commandId().toString().equals(jwt.getId())
                    && binding.method().equals(jwt.getClaimAsString("method"))
                    && binding.path().equals(jwt.getClaimAsString("path"))
                    && binding.operationId().toString().equals(jwt.getClaimAsString("operation"))
                    && binding.spaceId().toString().equals(jwt.getClaimAsString("space"))
                    && binding.workspaceId().toString().equals(jwt.getClaimAsString("workspace"))
                    && binding.generation() == integerClaim(jwt, "generation")
                    && binding.fence() == integerClaim(jwt, "fence")
                    && MessageDigest.isEqual(sha256(body).getBytes(StandardCharsets.US_ASCII),
                            jwt.getClaimAsString("body_sha256").getBytes(StandardCharsets.US_ASCII));
            if (!equal) throw new IllegalArgumentException();
        } catch (RuntimeException mismatch) { throw new WorkspaceFailure(401, "RUNNER_COMMAND_BINDING_REJECTED"); }
    }
    /** Verify a body-free query from authenticated claims before any database lookup. */
    public Binding verifiedQuery(String token,String path) {
        Jwt jwt=authenticate(token);
        try {
            var binding=new Binding("GET",path,UUID.fromString(jwt.getId()),UUID.fromString(jwt.getClaimAsString("operation")),
                    UUID.fromString(jwt.getClaimAsString("space")),UUID.fromString(jwt.getClaimAsString("workspace")),
                    integerClaim(jwt,"generation"),integerClaim(jwt,"fence"));
            verify(token,binding,new byte[0]);return binding;
        } catch(RuntimeException invalid) {throw new WorkspaceFailure(401,"RUNNER_COMMAND_BINDING_REJECTED");}
    }
    public static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long integerClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        if (value instanceof Long number) return number;
        if (value instanceof Integer number) return number.longValue();
        throw new IllegalArgumentException("Integer claim required");
    }
    public static int bodyLimit(String method,String path) {
        return "POST".equals(method)&&"/internal/workspace-shell/v1".equals(path)?131072:65536;
    }
    private static void bounded(Binding binding,byte[] body) {
        if (body == null || body.length > bodyLimit(binding.method(),binding.path())) throw new WorkspaceFailure(413, "RUNNER_BODY_TOO_LARGE");
    }
}
