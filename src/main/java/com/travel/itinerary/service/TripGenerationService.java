package com.travel.itinerary.service;

import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.poisearch.vo.PoiVO;
import java.time.Duration;
import java.util.List;

/** Resolves Google places and plans their visit order; persistence belongs to TripService. */
public interface TripGenerationService {
    List<List<PoiVO>> generatePlan(GenerateTripRequest request, Duration maxDuration);
}
