package com.travel.poisearch.controller;

import com.travel.common.ApiSupport;
import com.travel.common.Result;
import com.travel.poisearch.service.PoiSearchService;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pois")
public class PoiSearchController {
  private final PoiSearchService service;
  private final ApiSupport api;

  public PoiSearchController(PoiSearchService service, ApiSupport api) {
    this.service = service;
    this.api = api;
  }

  @PostMapping("/search")
  public Result<Map<String, List<PoiVO>>> search(@RequestBody PoiSearchRequest request) {
    return api.ok("POIs retrieved successfully", Map.of("pois", service.search(request)));
  }
}
