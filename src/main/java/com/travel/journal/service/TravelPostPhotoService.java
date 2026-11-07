package com.travel.journal.service;

import org.springframework.web.multipart.MultipartFile;

public interface TravelPostPhotoService {
    String upload(MultipartFile file);
}