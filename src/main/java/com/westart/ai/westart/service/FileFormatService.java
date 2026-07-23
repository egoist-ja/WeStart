package com.westart.ai.westart.service;

import com.github.wechat.ilink.sdk.core.model.MessageItem;

import java.io.IOException;

public interface FileFormatService {

    byte[] toWav(byte[] srcData, String srcMime) throws IOException;

    byte[] toPdf(byte[] srcData, String srcMime) throws IOException;

    byte[] toDocx(byte[] srcData, String srcMime) throws IOException;


}
