package com.travel.itinerary.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Keeps the existing database values ("Planning", "Ongoing", ...). */
@Converter
public class TripStatusConverter implements AttributeConverter<TripStatus, String> {
    @Override
    public String convertToDatabaseColumn(TripStatus status) {
        return status == null ? null : status.value();
    }

    @Override
    public TripStatus convertToEntityAttribute(String value) {
        return value == null ? null : TripStatus.from(value);
    }
}
