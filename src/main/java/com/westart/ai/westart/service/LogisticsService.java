package com.westart.ai.westart.service;

public interface LogisticsService {

    /**
     * 查询快递物流信息。
     *
     * @param trackingNumber 快递单号（必填）
     * @param carrierCode 快递公司编码（选填，不填则自动识别）
     * @param phone 收件人手机尾号后4位（选填）
     * @return 物流轨迹信息 JSON
     */
    String queryLogistics(String trackingNumber, String carrierCode, String phone);

}
