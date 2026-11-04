package com.travel.poisearch.service;

import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import java.util.List;

public interface PoiSearchService {
  List<PoiVO> search(PoiSearchRequest request);
}
