package com.westart.ai.westart.service;

import org.springframework.web.bind.annotation.RequestParam;

public interface WeatherService {

    String queryWeatherInfo(String province,String cityName);

}
