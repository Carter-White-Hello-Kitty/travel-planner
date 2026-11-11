package com.travel.common;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CI must fail closed even when the launching shell contains provider credentials. */
class CiProviderIsolationTest {
    @Test void surefireDisablesPaidProvidersAndBackgroundWorkers() {
        for (String name : List.of("google.places.api-key", "google.maps.api-key",
                "travel.photos.bucket", "travel.photos.base-url")) {
            // Never include an unexpected property value in an assertion message.
            assertTrue("".equals(System.getProperty(name)), "Paid provider configuration must be disabled in Maven tests");
        }
        assertTrue("false".equals(System.getProperty("journal.search.worker-enabled")), "Journal worker must be disabled");
        assertTrue("false".equals(System.getProperty("auth.worker-enabled")), "Mail worker must be disabled");
        URI es = URI.create(System.getProperty("journal.integration.es-url"));
        assertTrue("http".equals(es.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]").contains(es.getHost()),
                "Integration Elasticsearch must be local");
    }
}
