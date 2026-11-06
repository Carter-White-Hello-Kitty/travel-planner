package com.travel.itinerary.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Keeps the existing database values ("Planned", "Passed", "Visited"). */
@Converter
public class TripPoiStatusConverter implements AttributeConverter<TripPoiStatus, String> {
    @Override
    public String convertToDatabaseColumn(TripPoiStatus status) {
        return status == null ? null : status.value();
    }

    @Override
    public TripPoiStatus convertToEntityAttribute(String value) {
        return value == null ? null : TripPoiStatus.from(value);
    }
}
