package com.travel.journal.service.impl;

import com.travel.common.ApiError;
import com.travel.journal.service.TravelPostPhotoService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

@Service
public class TravelPostPhotoServiceImpl implements TravelPostPhotoService {
    private static final long MAX_FILE_SIZE = 1024 * 1024;

    private final S3Client s3Client;
    private final String bucket;
    private final String imageBaseUrl;

    public TravelPostPhotoServiceImpl(
            S3Client s3Client,
            @Value("${travel.photos.bucket:}") String bucket,
            @Value("${travel.photos.base-url:}") String imageBaseUrl) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.imageBaseUrl = imageBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public String upload(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_FILE_SIZE) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                    "Upload a non-empty image no larger than 1 MB.");
        }

        try {
            byte[] bytes = file.getBytes();
            String format = imageFormat(bytes);
            String key = "travel-photos/" + UUID.randomUUID() + "." + format;

            if (bucket.isBlank() || !imageBaseUrl.startsWith("https://")) {
                throw uploadFailed();
            }

            s3Client.putObject(
                    request -> request.bucket(bucket).key(key).contentType("image/" + format),
                    RequestBody.fromBytes(bytes));

            return imageBaseUrl + "/" + key;
        } catch (IOException | SdkException e) {
            throw uploadFailed();
        }
    }

    private String imageFormat(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (format.equals("jpeg") || format.equals("png")) {
                        return format;
                    }
                } finally {
                    reader.dispose();
                }
            }
        }

        throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_PHOTO",
                "Only JPEG and PNG images are supported.");
    }

    private ApiError uploadFailed() {
        return new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "PHOTO_UPLOAD_FAILED",
                "Could not save the photo.");
    }
}