package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.ContentProvider;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.os.BatteryManager;
import android.os.Build;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class Server {


    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException {
        Ln.i("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        // 创建设备对象，初始化设备信息，初始化监听
        final Device device = new Device(options);
        List<CodecOption> codecOptions = CodecOption.parse(options.getCodecOptions());

        // 设置是否需要在停止后关闭触摸点显示
        // 如果本来是没有开的，这次命令开启了，那么在进程结束后就关掉
        boolean mustDisableShowTouchesOnCleanUp = false;
        int restoreStayOn = -1;
        if (options.getShowTouches() || options.getStayAwake()) {
            try (ContentProvider settings = device.createSettingsProvider()) {
                if (options.getShowTouches()) {
                    String oldValue = settings.getAndPutValue(ContentProvider.TABLE_SYSTEM, "show_touches", "1");
                    // If "show touches" was disabled, it must be disabled back on clean up
                    mustDisableShowTouchesOnCleanUp = !"1".equals(oldValue);
                }

                if (options.getStayAwake()) {
                    int stayOn = BatteryManager.BATTERY_PLUGGED_AC | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    String oldValue = settings.getAndPutValue(ContentProvider.TABLE_GLOBAL, "stay_on_while_plugged_in", String.valueOf(stayOn));
                    try {
                        restoreStayOn = Integer.parseInt(oldValue);
                        if (restoreStayOn == stayOn) {
                            // No need to restore
                            restoreStayOn = -1;
                        }
                    } catch (NumberFormatException e) {
                        restoreStayOn = 0;
                    }
                }
            }
        }

        CleanUp.configure(mustDisableShowTouchesOnCleanUp, restoreStayOn, true);

        boolean tunnelForward = options.isTunnelForward();

        try (DesktopConnection connection = DesktopConnection.open(device, tunnelForward)) {
            ScreenEncoder screenEncoder = new ScreenEncoder(options.getSendFrameMeta(), options.getBitRate(), options.getMaxFps(), codecOptions);

            if (options.getControl()) {
                final Controller controller = new Controller(device, connection);

                // asynchronous
                startController(controller);
                startDeviceMessageSender(controller.getSender());

                device.setClipboardListener(new Device.ClipboardListener() {
                    @Override
                    public void onClipboardTextChanged(String text) {
                        controller.getSender().pushClipboardText(text);
                    }
                });
            }

            try {
                // synchronous
                screenEncoder.streamScreen(device, connection.getVideoFd());
            } catch (IOException e) {
                // this is expected on close
                Ln.d("Screen streaming stopped");
            }
        }
    }

    private static void startController(final Controller controller) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controller.control();
                } catch (IOException e) {
                    // this is expected on close
                    Ln.d("Controller stopped");
                }
            }
        }).start();
    }

    private static void startDeviceMessageSender(final DeviceMessageSender sender) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sender.loop();
                } catch (IOException | InterruptedException e) {
                    // this is expected on close
                    Ln.d("Device message sender stopped");
                }
            }
        }).start();
    }

    /**
     * 解析参数， 参数的数量、顺序都是固定的
     * 版本号
     * 日志级别
     * maxSize
     * 比特率
     * 最大帧数
     * 视频方向锁定
     * 是否作为服务端
     * crop指定区域
     * 是否发送帧元数据
     * 是否开启控制
     * id
     * 是否显示触摸点
     * 是否保持唤醒
     * codec参数
     */
    private static Options createOptions(String... args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Missing client version");
        }

        // 首先判断参数里的版本号是否匹配，这是为了确保服务端和客户端配套
        // 因为不配套的可能会出现奇奇怪怪的bug
        String clientVersion = args[0];
        if (!clientVersion.equals(BuildConfig.VERSION_NAME)) {
            throw new IllegalArgumentException(
                    "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")");
        }

        // 指定参数个数，确保需要的配置都有指定
        final int expectedParameters = 14;
        if (args.length != expectedParameters) {
            throw new IllegalArgumentException("Expecting " + expectedParameters + " parameters");
        }

        Options options = new Options();

        Ln.Level level = Ln.Level.valueOf(args[1].toUpperCase(Locale.ENGLISH));
        options.setLogLevel(level);

        int maxSize = Integer.parseInt(args[2]) & ~7; // multiple of 8
        options.setMaxSize(maxSize);

        int bitRate = Integer.parseInt(args[3]);
        options.setBitRate(bitRate);

        int maxFps = Integer.parseInt(args[4]);
        options.setMaxFps(maxFps);

        int lockedVideoOrientation = Integer.parseInt(args[5]);
        options.setLockedVideoOrientation(lockedVideoOrientation);

        // use "adb forward" instead of "adb tunnel"? (so the server must listen)
        boolean tunnelForward = Boolean.parseBoolean(args[6]);
        options.setTunnelForward(tunnelForward);

        Rect crop = parseCrop(args[7]);
        options.setCrop(crop);

        boolean sendFrameMeta = Boolean.parseBoolean(args[8]);
        options.setSendFrameMeta(sendFrameMeta);

        boolean control = Boolean.parseBoolean(args[9]);
        options.setControl(control);

        int displayId = Integer.parseInt(args[10]);
        options.setDisplayId(displayId);

        boolean showTouches = Boolean.parseBoolean(args[11]);
        options.setShowTouches(showTouches);

        boolean stayAwake = Boolean.parseBoolean(args[12]);
        options.setStayAwake(stayAwake);

        String codecOptions = args[13];
        options.setCodecOptions(codecOptions);

        return options;
    }

    private static Rect parseCrop(String crop) {
        if ("-".equals(crop)) {
            return null;
        }
        // input format: "width:height:x:y"
        String[] tokens = crop.split(":");
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + crop + "\"");
        }
        int width = Integer.parseInt(tokens[0]);
        int height = Integer.parseInt(tokens[1]);
        int x = Integer.parseInt(tokens[2]);
        int y = Integer.parseInt(tokens[3]);
        return new Rect(x, y, x + width, y + height);
    }

    private static void suggestFix(Throwable e) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (e instanceof MediaCodec.CodecException) {
                MediaCodec.CodecException mce = (MediaCodec.CodecException) e;
                if (mce.getErrorCode() == 0xfffffc0e) {
                    Ln.e("The hardware encoder is not able to encode at the given definition.");
                    Ln.e("Try with a lower definition:");
                    Ln.e("    scrcpy -m 1024");
                }
            }
        }
        if (e instanceof InvalidDisplayIdException) {
            InvalidDisplayIdException idie = (InvalidDisplayIdException) e;
            int[] displayIds = idie.getAvailableDisplayIds();
            if (displayIds != null && displayIds.length > 0) {
                Ln.e("Try to use one of the available display ids:");
                for (int id : displayIds) {
                    Ln.e("    scrcpy --display " + id);
                }
            }
        }
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Ln.e("Exception on thread " + t, e);
                suggestFix(e);
            }
        });

        // 解析参数
        Options options = createOptions(args);

        // 根据参数指定日志等级
        Ln.initLogLevel(options.getLogLevel());

        // 启动scrcpy
        scrcpy(options);
    }
}
