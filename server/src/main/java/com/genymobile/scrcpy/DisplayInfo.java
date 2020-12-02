package com.genymobile.scrcpy;


/**
 * 显示信息
 */
public final class DisplayInfo {
    /**
     * 显示屏id，0默认
     */
    private final int displayId;
    /**
     * 显示屏大小，宽高
     */
    private final Size size;
    /**
     * 方向
     */
    private final int rotation;
    private final int layerStack;
    private final int flags;

    public static final int FLAG_SUPPORTS_PROTECTED_BUFFERS = 0x00000001;

    public DisplayInfo(int displayId, Size size, int rotation, int layerStack, int flags) {
        this.displayId = displayId;
        this.size = size;
        this.rotation = rotation;
        this.layerStack = layerStack;
        this.flags = flags;
    }

    public int getDisplayId() {
        return displayId;
    }

    public Size getSize() {
        return size;
    }

    public int getRotation() {
        return rotation;
    }

    public int getLayerStack() {
        return layerStack;
    }

    public int getFlags() {
        return flags;
    }
}

