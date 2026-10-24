package com.agentplatform.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.LegacyMd5Plugin;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Explicit third-party S3 compatibility using official SDK extensions, never global SDK settings. */
public final class S3Clients {
    private S3Clients() {}
    public static S3Client create(URI fixedEndpoint, AwsCredentialsProvider credentials) {
        return builder(fixedEndpoint, credentials).build();
    }
    public static S3ClientBuilder builder(URI fixedEndpoint, AwsCredentialsProvider credentials) {
        return S3Client.builder().endpointOverride(fixedEndpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(b -> b.apiCallTimeout(Duration.ofSeconds(120)).apiCallAttemptTimeout(Duration.ofSeconds(30)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .addPlugin(LegacyMd5Plugin.create())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    }
}
