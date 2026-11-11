package com.travel.journal.service;

import com.travel.common.ApiError;
import com.travel.journal.service.impl.TravelPostPhotoServiceImpl;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class TravelPostPhotoServiceTest {
    final S3Client s3 = mock(S3Client.class);
    final TravelPostPhotoServiceImpl photos = new TravelPostPhotoServiceImpl(s3, "unit-test-bucket", "https://example.com/photos/");
    MockMultipartFile png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "photo.png", "image/png", bytes.toByteArray());
    }
    @Test void validImageUsesExistingS3UploadContract() throws Exception {
        String url = photos.upload(png());
        assertTrue(url.startsWith("https://example.com/photos/travel-photos/"));
        assertTrue(url.endsWith(".png"));
        verify(s3).putObject(org.mockito.ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
    }
    @Test void invalidFilesNeverReachS3() {
        for (byte[] data : new byte[][] {new byte[0], new byte[1_048_577], new byte[] {1,2,3}}) {
            var error = assertThrows(ApiError.class, () -> photos.upload(new MockMultipartFile("file", data)));
            assertEquals(400, error.status.value());
            assertEquals("INVALID_PHOTO", error.code);
        }
        verifyNoInteractions(s3);
    }
    @Test void missingConfigurationDoesNotAttemptExternalUpload() throws Exception {
        var file = png();
        var error = assertThrows(ApiError.class, () -> new TravelPostPhotoServiceImpl(s3, "", "").upload(file));
        assertEquals("PHOTO_UPLOAD_FAILED", error.code);
        verifyNoInteractions(s3);
    }
    @Test void sdkFailureKeepsExistingSanitizedErrorContract() throws Exception {
        doThrow(SdkClientException.create("Synthetic provider detail"))
                .when(s3).putObject(org.mockito.ArgumentMatchers.<Consumer<PutObjectRequest.Builder>>any(), any(RequestBody.class));
        var file = png();
        var error = assertThrows(ApiError.class, () -> photos.upload(file));
        assertEquals(500, error.status.value());
        assertEquals("PHOTO_UPLOAD_FAILED", error.code);
        assertFalse(error.getMessage().contains("Synthetic provider detail"));
    }
}
