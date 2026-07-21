package com.westart.ai.westart.controller;

import com.westart.ai.westart.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping("/current")
    @ResponseBody
    public String queryCurrentWeather(@RequestParam("province") String province,@RequestParam("cityName") String cityName){
        return weatherService.queryWeatherInfo(province,cityName);
    }

}
