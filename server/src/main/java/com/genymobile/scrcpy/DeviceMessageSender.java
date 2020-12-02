package com.genymobile.scrcpy;

import java.io.IOException;

/**
 * 设备消息发送器
 * 目前只是发送粘贴板内容
 */
public final class DeviceMessageSender {

    private final DesktopConnection connection;

    private String clipboardText;

    public DeviceMessageSender(DesktopConnection connection) {
        this.connection = connection;
    }

    /**
     * 推送粘贴板中的内容到PC
     * @param text
     */
    public synchronized void pushClipboardText(String text) {
        clipboardText = text;
        notify();
    }

    public void loop() throws IOException, InterruptedException {
        while (true) {
            String text;
            synchronized (this) {
                while (clipboardText == null) {
                    wait();
                }
                text = clipboardText;
                clipboardText = null;
            }
            DeviceMessage event = DeviceMessage.createClipboard(text);
            connection.sendDeviceMessage(event);
        }
    }
}
