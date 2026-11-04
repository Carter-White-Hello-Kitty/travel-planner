package com.travel.poisearch.vo;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record PoiSearchRequest(
        String keyword,
        Double latitude,
        Double longitude,
        @JsonDeserialize(using = PoiSearchRequest.RadiusDeserializer.class) Integer radius
) {
    /** Reject fractions and strings instead of silently truncating/coercing the radius. */
    public static class RadiusDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Integer.class, "radius must be an integer");
            return parser.getIntValue();
        }
    }
}
