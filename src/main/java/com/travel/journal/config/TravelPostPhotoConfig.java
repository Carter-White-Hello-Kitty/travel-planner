package com.travel.journal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class TravelPostPhotoConfig {
    @Bean
    public S3Client s3Client(
            @Value("${AWS_REGION:}") String region,
            @Value("${AWS_S3_TRAVEL_JOURNAL_PHOTO_ACCESS_KEY_ID:}") String accessKeyId,
            @Value("${AWS_S3_TRAVEL_JOURNAL_PHOTO_ACCESS_SECRET:}") String secretAccessKey) {
        // The S3 client is created in every application context, including CI where photo uploads are not configured.
        var builder = S3Client.builder().region(region.isBlank() ? Region.US_EAST_1 : Region.of(region));
        if (accessKeyId.isBlank() != secretAccessKey.isBlank()) {
            throw new IllegalStateException("Both S3 photo access key settings must be provided together");
        }
        if (!accessKeyId.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return builder.build();
    }
}
