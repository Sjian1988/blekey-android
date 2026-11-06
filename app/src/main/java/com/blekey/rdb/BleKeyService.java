package com.blekey.rdb;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 常驻前台服务: 用 autoConnect 白名单方式与车机保持 GATT 连接。
 *
 * 关键点: connectGatt(ctx, true, ...) 把连接请求交给蓝牙控制器的自动连接白名单,
 * 设备广播一出现就由控制器建连, 不需要 App 主动扫描。这是把靠近响应从 20~40 秒
 * 压到 1~3 秒的根本原因。
 */
public class BleKeyService extends Service {

    private static final String TAG = "BleKey";

    public static final String ACTION_START = "com.blekey.rdb.action.START";
    public static final String ACTION_STOP = "com.blekey.rdb.action.STOP";
    public static final String ACTION_UNLOCK = "com.blekey.rdb.action.UNLOCK";
    public static final String ACTION_LOCK = "com.blekey.rdb.action.LOCK";
    public static final String ACTION_TRUNK = "com.blekey.rdb.action.TRUNK";
    /** 主动断开并把车机让给原 App, YIELD_MS 内不再自动重连。 */
    public static final String ACTION_YIELD = "com.blekey.rdb.action.YIELD";
    /** 重发一次「系统信息同步」, 借车机的回复把电量刷新一遍。 */
    public static final String ACTION_REFRESH = "com.blekey.rdb.action.REFRESH";
    /** 写入舒适进入参数(开关 + 落锁/开锁阈值)。 */
    public static final String ACTION_CE_SET = "com.blekey.rdb.action.CE_SET";
    /** 原 App 刷固件(DFU)可能持续十几分钟, 被抢连接会中断升级, 所以让位给足 30 分钟。 */
    private static final long YIELD_MS = 30 * 60 * 1000L;

    public static final String EXTRA_ADDR = "addr";
    public static final String EXTRA_SERIAL = "serial";
    public static final String EXTRA_CE_OPEN = "ce_open";
    public static final String EXTRA_CE_LOCK = "ce_lock";
    public static final String EXTRA_CE_UNLOCK = "ce_unlock";

    /** 状态变化广播, 界面用来刷新。 */
    public static final String BCAST_STATE = "com.blekey.rdb.STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DETAIL = "detail";
    /** 车机电量百分比, -1 表示还没读到。 */
    public static final String EXTRA_POWER = "power";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_DISCOVERING = "discovering";
    public static final String STATE_AUTHING = "authing";
    public static final String STATE_READY = "ready";
    public static final String STATE_NEED_UNBIND = "need_unbind";
    public static final String STATE_NEED_CONFIRM = "need_confirm";
    public static final String STATE_FAIL = "fail";

    public static final UUID SVC = UUID.fromString("8849a9d0-d75f-0c8c-2348-de2d580f2f24");
    public static final UUID CH_IO = UUID.fromString("8849a9d1-d75f-0c8c-2348-de2d580f2f24");
    public static final UUID CH_LOG = UUID.fromString("8849a9d2-d75f-0c8c-2348-de2d580f2f24");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int MANUFACTURER_ID = 0xF715;   // 63253

    private static final int NOTIF_ID = 8081;
    private static final String CHANNEL = "blekey";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> txq = new ArrayDeque<byte[]>();
    private boolean txBusy;

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chIo;
    private BluetoothGattCharacteristic chLog;

    private int seqId = 1;
    private boolean ready;
    private int verifyFails;        // 本轮连接里密钥校验连续失败次数, 成功后归零
    /** 让位给原 App 的截止时间戳。 */
    private long yieldUntil;

    /* 握手必须严格串行: GATT 同一时刻只接受一个操作, 并发会直接返回 busy */
    private boolean connected;
    private boolean cccdIoDone;
    private boolean cccdLogDone;
    private boolean mtuDone;
    private boolean authStarted;
    private int wxType = -1;     // 当前生效的写类型, -1 表示还没定
    private int wxFails;         // 连续写失败次数
    private int authTries;
    private int pendingCtrl = -1;
    private long pendingCtrlTs;
    private long connectTs;
    /** 落锁后车机会及时断电, 这段时间内不主动重连。 */
    private long quietUntil;
    private static final long LOCK_QUIET_MS = 15000;
    private String state = STATE_IDLE;
    private String detail = "";
    private int lastRssi = 0;
    /** 已显示过的 RSSI, 用来做 5dB 抖动节流。 */
    private int shownRssi = Integer.MIN_VALUE;
    /** 车机上次推 RSSI 的时间, 有推送就不再自己 readRemoteRssi。 */
    private long lastCarRssiTs;
    /** 上次向车机要「系统信息」(顺带拿电量)的时间, 用来做防抖。 */
    private long lastInfoReqTs;

    private BluetoothLeScanner scanner;
    private boolean scanning;

    /** 服务存活标记, 静态的, 供 JobScheduler / 闹钟判断需不需要拉起。 */
    private static volatile boolean running;

    /** 当前状态的静态副本, 界面拿不到 Service 实例时用它判断是否已连接。 */
    private static volatile String lastState = STATE_IDLE;

    /** 与 lastState 配套的说明文字, 界面重建时用它回填状态卡, 不用干等下一次广播。 */
    private static volatile String lastDetail;

    public static String lastState() {
        return lastState;
    }

    public static String lastDetail() {
        return lastDetail;
    }

    /** 界面侧判断能不能下发指令, 不持有 Service 实例。 */
    public static boolean isConnected() {
        return STATE_READY.equals(lastState);
    }

    /* ------------------------------------------------------------------ */

    public static void start(Context c) {
        Intent i = new Intent(c, BleKeyService.class);
        i.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            // Android 12+ 后台起前台服务会被拦, 交给 JobScheduler 稍后再试
            Log.w(TAG, "start fail", t);
            KeepAlive.scheduleAlarm(c, 60 * 1000L);
        }
    }

    /** 服务是否还活着。给 Job/闹钟兜底判断用。 */
    public static boolean isRunning() {
        return running;
    }

    public static void send(Context c, String action) {
        Intent i = new Intent(c, BleKeyService.class);
        i.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "send fail", e);
        }
    }

    /** 写舒适进入参数, 连接状态由服务侧判断。 */
    public static void sendCe(Context c, boolean open, int lock, int unlock) {
        Intent i = new Intent(c, BleKeyService.class);
        i.setAction(ACTION_CE_SET);
        i.putExtra(EXTRA_CE_OPEN, open);
        i.putExtra(EXTRA_CE_LOCK, lock);
        i.putExtra(EXTRA_CE_UNLOCK, unlock);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "send fail", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        createChannel();
        // 把周期任务和闹钟都挂上: 进程万一被清掉, 由它们把服务拉回来
        KeepAlive.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundCompat(buildNotification());
        } catch (Throwable t) {
            // 极少见: 后台起前台服务被 ROM 拦。不 return, 让连接逻辑照跑
            Log.w(TAG, "startForeground fail", t);
        }
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) {
            action = ACTION_START;
        }
        switch (action) {
            case ACTION_STOP:
                shutdown();
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_UNLOCK:
                cancelYield();
                sendCtrl(Proto.K_QUICK_UNLOCK);
                break;
            case ACTION_LOCK:
                cancelYield();
                sendCtrl(Proto.K_QUICK_LOCK);
                break;
            case ACTION_TRUNK:
                cancelYield();
                sendCtrl(Proto.K_QUICK_TRUNK);
                break;
            case ACTION_YIELD:
                yieldToOriginalApp();
                break;
            case ACTION_REFRESH:
                refreshInfo();
                break;
            case ACTION_CE_SET:
                setComfortEntry(intent);
                break;
            default:
                // ACTION_START: 有配置就直接连, 没有就只挂着通知等用户配对
                break;
        }
        if (!ready && gatt == null
                && Prefs.autoConnect(this) && Prefs.addr(this).length() > 0) {
            connect();
        }
        return START_STICKY;
    }

    private void shutdown() {
        ready = false;
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        setState(STATE_IDLE, "已停止");
        // 用户主动停止就不要再被兜底拉起来
        KeepAlive.uninstall(this);
    }

    /* ------------------------------ 连接 ------------------------------ */

    @SuppressLint("MissingPermission")
    private void connect() {
        connect(false);
    }

    /**
     * @param direct true=直接连接(配对时用, 快), false=autoConnect 白名单(日常用, 靠近即连)
     */
    @SuppressLint("MissingPermission")
    private void connect(boolean direct) {
        if (yielding() && !direct) {
            Log.i(TAG, "connect skipped, yielding to original app");
            return;
        }
        String addr = Prefs.addr(this);
        if (addr == null || addr.length() == 0) {
            setState(STATE_IDLE, "还没有配对车机, 请先搜索并配对");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            setState(STATE_FAIL, "蓝牙未开启");
            return;
        }
        if (gatt != null) {
            // 已在连接中或握手阶段就不要再 connectGatt, 否则会同时冒出多个
            // GATT 客户端, 互相把写通道挤成 busy
            boolean busy = connected
                    || ((STATE_CONNECTING.equals(state)
                    || STATE_DISCOVERING.equals(state)
                    || STATE_AUTHING.equals(state))
                    && System.currentTimeMillis() - connectTs < 20000);
            if (busy) {
                Log.i(TAG, "connect skipped, already " + state);
                return;
            }
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(addr);
        } catch (Exception e) {
            setState(STATE_FAIL, "地址无效: " + addr);
            return;
        }
        setState(STATE_CONNECTING, addr + (direct ? " (直接连接)" : " (等待靠近)"));
        connectTs = System.currentTimeMillis();
        try {
            Log.i(TAG, "connect " + addr + " name=" + dev.getName()
                    + " bond=" + dev.getBondState() + " type=" + dev.getType()
                    + " direct=" + direct);
        } catch (Exception ignored) {
        }
        gatt = dev.connectGatt(this, !direct, callback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "conn state " + status + " -> " + newState
                    + (g == gatt ? "" : " (stale)"));
            if (g != gatt) {
                // 旧连接的迟到回调, 必须忽略, 否则会把新连接的状态搞乱
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                resetHandshake();
                h.post(new Runnable() {
                    public void run() {
                        setState(STATE_DISCOVERING, "已连接, 发现服务");
                    }
                });
                try {
                    g.discoverServices();
                } catch (Exception e) {
                    Log.w(TAG, "discover fail", e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                ready = false;
                resetHandshake();
                h.post(new Runnable() {
                    public void run() {
                        setState(STATE_CONNECTING, "断开, 等待重连");
                        scheduleReconnect();
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) {
                return;
            }
            Log.i(TAG, "services discovered status=" + status
                    + " chars=" + (g.getServices() == null ? 0 : g.getServices().size()));
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) {
                setState(STATE_FAIL, "没找到车机服务, 可能不是这台设备");
                return;
            }
            chIo = svc.getCharacteristic(CH_IO);
            chLog = svc.getCharacteristic(CH_LOG);
            if (chIo == null) {
                setState(STATE_FAIL, "没找到收发特征");
                return;
            }
            Log.i(TAG, "chIo props=0x" + Integer.toHexString(chIo.getProperties())
                    + " chLog=" + (chLog == null ? "none"
                    : "0x" + Integer.toHexString(chLog.getProperties())));
            // 串行: 先开通知(写 CCCD), 回调回来再请求 MTU, MTU 回来才鉴权
            cccdLogDone = (chLog == null);
            h.postDelayed(authWatchdog, 2000);
            if (!enableNotify(g, chIo)) {
                cccdIoDone = true;
                onHandshakeStep();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (g == gatt) {
                Log.i(TAG, "descriptor write status=" + status);
                if (!cccdIoDone) {
                    cccdIoDone = true;
                    if (!cccdLogDone && !enableNotify(g, chLog)) {
                        cccdLogDone = true;
                    }
                } else if (!cccdLogDone) {
                    cccdLogDone = true;
                }
                onHandshakeStep();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status != 0) {
                Log.w(TAG, "char write status=" + status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (g != gatt) {
                return;
            }
            Log.i(TAG, "mtu=" + mtu + " status=" + status);
            mtuDone = true;
            onHandshakeStep();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            handleFrame(ch.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            handleFrame(value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            if (status == 0 && ch.getValue() != null) {
                handleFrame(ch.getValue());
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value, int status) {
            if (status == 0 && value != null) {
                handleFrame(value);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            if (status == 0) {
                lastRssi = rssi;
                onRssi(rssi);
            }
        }
    };

    /**
     * 打开通知。返回 true 表示发起了一次 CCCD 写(异步), 需要等 onDescriptorWrite;
     * 返回 false 表示没有 CCCD 或写请求被拒, 调用方可以直接进入下一步。
     */
    @SuppressLint("MissingPermission")
    private boolean enableNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        if (ch == null) {
            return false;
        }
        try {
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
            if (d == null) {
                return false;
            }
            int st;
            if (Build.VERSION.SDK_INT >= 33) {
                st = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                st = g.writeDescriptor(d) ? 0 : -1;
            }
            if (st != 0) {
                Log.w(TAG, "cccd write rejected " + st);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "notify fail", e);
            return false;
        }
    }

    /** 清空握手过程中的分步标记。 */
    private void resetHandshake() {
        h.removeCallbacks(authWatchdog);
        cccdIoDone = false;
        cccdLogDone = false;
        mtuDone = false;
        authStarted = false;
        wxFails = 0;
        wxType = -1;      // 每条新连接重新按特征属性挑写类型
        verifyFails = 0;
        // 每条新连接都要重新读一次电量, 所以防抖计时清零
        lastInfoReqTs = 0;
        h.removeCallbacks(requery);
    }

    /** 分步推进: CCCD -> MTU -> 鉴权, 任一步完成才会触发下一步。 */
    private void onHandshakeStep() {
        if (!connected || gatt == null) {
            return;
        }
        if (!cccdIoDone || !cccdLogDone) {
            return;
        }
        if (!mtuDone) {
            boolean asked;
            try {
                asked = gatt.requestMtu(247);
            } catch (Exception e) {
                asked = false;
            }
            if (!asked) {
                mtuDone = true;
                onHandshakeStep();
            }
            return;
        }
        if (!authStarted) {
            authStarted = true;
            h.removeCallbacks(authWatchdog);
            startAuth();
        }
    }

    /** 兜底: 有些车机不回 CCCD / MTU 回调, 2 秒后强行往下走。 */
    private final Runnable authWatchdog = new Runnable() {
        public void run() {
            if (!authStarted && connected) {
                Log.w(TAG, "handshake timeout, force auth");
                cccdIoDone = true;
                cccdLogDone = true;
                mtuDone = true;
                onHandshakeStep();
            }
        }
    };

    private void scheduleReconnect() {
        h.removeCallbacks(reconnectTask);
        h.postDelayed(reconnectTask, 2000);
    }

    private final Runnable reconnectTask = new Runnable() {
        public void run() {
            if (!Prefs.autoConnect(BleKeyService.this)) {
                return;
            }
            if (yielding()) {
                return;   // 让位期间不抢连接
            }
            long quiet = quietUntil - System.currentTimeMillis();
            if (quiet > 0) {
                // 刚落锁: 车机正在断电, 这时候连只会白费
                Log.i(TAG, "reconnect postponed " + quiet + "ms (after lock)");
                h.postDelayed(this, quiet + 500);
                return;
            }
            if (gatt != null) {
                try {
                    if (gatt.connect()) {
                        return;
                    }
                } catch (Exception ignored) {
                }
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }
                gatt = null;
            }
            connect();
        }
    };

    /* ------------------------------ 让位给原 App ------------------------------ */

    /** 让位期间不抢连接: BLE 连接是独占的, 我们连着时原 App 连不上。 */
    private boolean yielding() {
        return System.currentTimeMillis() < yieldUntil;
    }

    private void cancelYield() {
        if (yieldUntil > 0) {
            yieldUntil = 0;
            h.removeCallbacks(yieldResume);
            Log.i(TAG, "yield cancelled by user action");
        }
    }

    private void yieldToOriginalApp() {
        yieldUntil = System.currentTimeMillis() + YIELD_MS;
        ready = false;
        pendingCtrl = -1;
        h.removeCallbacks(reconnectTask);
        h.removeCallbacks(unlockSecond);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        connected = false;
        chIo = null;
        chLog = null;
        Log.i(TAG, "yield to original app for " + (YIELD_MS / 1000) + "s");
        setState(STATE_IDLE, "已让位给原 App, 30 分钟内不抢连接");
        h.removeCallbacks(yieldResume);
        h.postDelayed(yieldResume, YIELD_MS);
    }

    private final Runnable yieldResume = new Runnable() {
        public void run() {
            yieldUntil = 0;
            if (Prefs.addr(BleKeyService.this).length() == 0) {
                return;
            }
            Log.i(TAG, "yield ended, reconnect");
            setState(STATE_CONNECTING, "让位结束, 重新接管连接");
            connect(false);
        }
    };

    /* ------------------------------ 鉴权 ------------------------------ */

    private void startAuth() {
        setState(STATE_AUTHING, "校验密钥");
        sendFrame(Proto.CMD_AUTH, Proto.AUTH_QUERY, null);
        authTries = 0;
        h.removeCallbacks(authRetry);
        h.postDelayed(authRetry, 3000);
    }

    /** 鉴权发出去没回应就重发, 最多 4 次。写通道刚建立时偶尔会吞掉第一帧。 */
    private final Runnable authRetry = new Runnable() {
        public void run() {
            if (ready || !connected) {
                return;
            }
            if (authTries >= 4) {
                Log.w(TAG, "auth no response, give up");
                setState(STATE_FAIL, "车机没有回应鉴权, 请点\"搜索车机\"重新配对");
                return;
            }
            authTries++;
            Log.w(TAG, "auth retry #" + authTries);
            sendFrame(Proto.CMD_AUTH, Proto.AUTH_QUERY, null);
            h.postDelayed(this, 2500);
        }
    };

    private void onAuthResponse(Proto.Rsp r) {
        Proto.AuthRsp a = Proto.parseAuth(r);
        if (a == null) {
            return;
        }
        Log.i(TAG, "auth rsp key=" + r.key + " type=" + a.type + " confirm=" + a.keyConfirm
                + " mac=" + a.macId + " rnd=" + a.random + " err=" + a.errorCode
                + " passLen=" + Prefs.keypass(this).length());
        String serial = Prefs.serial(this);
        if (serial.length() == 0 && a.macId != null && a.macId.length() >= 12) {
            serial = a.macId;
            Prefs.put(this, Prefs.K_SERIAL, serial);
        }

        if (r.key == Proto.AUTH_QUERY) {
            if (a.type == Proto.BOND_BOUND) {
                String pass = Prefs.keypass(this);
                if (pass.length() >= 32 && a.hasRandom()) {
                    byte[] enc = Proto.aes(Proto.unhex(pass), Proto.unhex(a.random));
                    Log.i(TAG, "verify: aes(" + pass + ", " + a.random + ") = " + Proto.hex(enc));
                    sendFrame(Proto.CMD_AUTH, Proto.AUTH_VERIFY, enc);
                    return;
                }
                setState(STATE_NEED_UNBIND, "本机没有车钥匙。请先用原 App 配对, 再导入密钥");
                return;
            }
            if (a.type == Proto.BOND_FREE) {
                // 绑定只在原 App 里做: 本应用不生成、也不向车机下发密钥。
                setState(STATE_NEED_UNBIND, "车机还没有绑定车钥匙。请先用原 App 完成配对");
                return;
            }
            setState(STATE_FAIL, "未知的绑定状态: " + a.type);
            return;
        }

        if (r.key == Proto.AUTH_VERIFY) {
            if (a.errorCode == 0) {
                verifyFails = 0;
                onReady();
            } else {
                // 校验失败不能清密钥: 车机偶尔会在忙/重连时回个错误码,
                // 一旦清掉, 手里的钥匙就永久没了, 只能重新注入。改为有限次重试。
                verifyFails++;
                Log.w(TAG, "verify failed code=" + a.errorCode
                        + " fail#" + verifyFails + " (keypass kept)");
                if (verifyFails <= 3) {
                    setState(STATE_AUTHING, "密钥校验失败, 正在重试(" + verifyFails + "/3)");
                    h.removeCallbacks(requery);
                    h.postDelayed(requery, 1200);
                } else {
                    setState(STATE_NEED_UNBIND, "密钥校验失败(code=" + a.errorCode
                            + "), 密钥已保留, 请确认车机是否仍是这台");
                }
            }
            return;
        }
    }

    /** 校验失败后重新问一次车机的绑定状态。 */
    private final Runnable requery = new Runnable() {
        public void run() {
            if (!connected || ready) {
                return;
            }
            sendFrame(Proto.CMD_AUTH, Proto.AUTH_QUERY, null);
            authTries = 0;
            h.removeCallbacks(authRetry);
            h.postDelayed(authRetry, 4000);
        }
    };

    private void onReady() {
        ready = true;
        authTries = 0;
        shownRssi = Integer.MIN_VALUE;
        h.removeCallbacks(authRetry);
        h.removeCallbacks(requery);
        setState(STATE_READY, String.format("已连接 (%d dBm)", Integer.valueOf(lastRssi)));
        // 同步一次时间/时区, 车机时钟才不会飘; 车机的回复里带电量, 一并读回来
        refreshInfo();
        // 读回车机里的舒适进入参数(开锁/落锁距离), 只显示用, 不修改
        sendFrame(Proto.CMD_CFG, 32, null);
        h.removeCallbacks(rssiTask);
        h.postDelayed(rssiTask, 5000);
        // 用户在没连上时点过解锁/落锁, 这里补发(8 秒内有效)
        if (pendingCtrl >= 0) {
            final int k = pendingCtrl;
            pendingCtrl = -1;
            if (System.currentTimeMillis() - pendingCtrlTs < 8000) {
                h.postDelayed(new Runnable() {
                    public void run() {
                        sendCtrl(k);
                    }
                }, 300);
            } else {
                Log.w(TAG, "pending ctrl expired");
            }
        }
    }

    /* ------------------------------ 收发 ------------------------------ */

    private void sendFrame(int cmd, int key, byte[] payload) {
        byte[] f = Proto.frame(seqId++, cmd, key, payload);
        Log.i(TAG, "tx cmd=" + cmd + " key=" + key + " len=" + (payload == null ? 0 : payload.length)
                + " " + Proto.hex(f));
        enqueue(f);
    }

    private void sendCtrl(int key) {
        if (key == Proto.K_QUICK_UNLOCK) {
            doUnlock();
            return;
        }
        if (key == Proto.K_QUICK_LOCK) {
            doLock();
            return;
        }
        if (!ready) {
            queuePending(key);
            return;
        }
        pendingCtrl = -1;
        sendFrame(Proto.CMD_CTRL, key, null);
    }

    /**
     * 开锁。车机侧配置是「开锁按 2 次、间隔 1 秒」, 只发一帧车门不会开,
     * 所以这里按同样节奏补第二帧。设置里可以关掉。
     */
    private void doUnlock() {
        if (!ready) {
            queuePending(Proto.K_QUICK_UNLOCK);
            return;
        }
        pendingCtrl = -1;
        h.removeCallbacks(unlockSecond);
        sendFrame(Proto.CMD_CTRL, Proto.K_QUICK_UNLOCK, null);
        if (Prefs.unlockTwice(this)) {
            Log.i(TAG, "unlock 1/2, second in " + Prefs.UNLOCK_GAP_MS + "ms");
            setState(STATE_READY, "开锁 1/2, 1 秒后补第二下");
            h.postDelayed(unlockSecond, Prefs.UNLOCK_GAP_MS);
        } else {
            Log.i(TAG, "unlock 1/1");
            setState(STATE_READY, "已发送开锁");
        }
    }

    private final Runnable unlockSecond = new Runnable() {
        public void run() {
            if (!ready) {
                Log.w(TAG, "unlock 2/2 skipped, not ready");
                return;
            }
            Log.i(TAG, "unlock 2/2");
            sendFrame(Proto.CMD_CTRL, Proto.K_QUICK_UNLOCK, null);
            setState(STATE_READY, "已发送开锁 (2 次)");
        }
    };

    /** 落锁。单发一次, 之后暂停重连给车机断电。 */
    private void doLock() {
        if (!ready) {
            queuePending(Proto.K_QUICK_LOCK);
            return;
        }
        pendingCtrl = -1;
        h.removeCallbacks(unlockSecond);   // 开锁第二下还没发就先落锁, 取消它
        sendFrame(Proto.CMD_CTRL, Proto.K_QUICK_LOCK, null);
        quietUntil = System.currentTimeMillis() + LOCK_QUIET_MS;
        Log.i(TAG, "lock sent, no reconnect for " + LOCK_QUIET_MS + "ms");
        setState(STATE_READY, "已发送落锁 (车机可能随即断电)");
    }

    /** 没鉴权完成时先记下来, 连上后 8 秒内补发。 */
    private void queuePending(int key) {
        Log.w(TAG, "not ready, ctrl " + key + " queued (state=" + state + ")");
        pendingCtrl = key;
        pendingCtrlTs = System.currentTimeMillis();
        // 手动按解锁说明人就在车边, 用直连, 不走 autoConnect 白名单
        if (!connected && Prefs.addr(this).length() > 0) {
            connect(true);
        }
    }

    /**
     * 读一遍车机的系统信息(cmd=2 key=0)。
     *
     * 原包的读数方式与此完全一致: 鉴权通过后调 setSystemInfo(), 车机回一条
     * 自述 TLV, 里面 tag=23 就是电量百分比。所以「同步时间」和「读电量」
     * 是同一帧, 不额外增加协议往返。
     */
    private void refreshInfo() {
        if (!connected) {
            Log.i(TAG, "refresh info skipped, not connected");
            return;
        }
        // 防抖: 界面 onResume 和重连可能在几秒内各触发一次
        long now = System.currentTimeMillis();
        if (now - lastInfoReqTs < 3000) {
            return;
        }
        lastInfoReqTs = now;
        sendSystemInfo();
    }

    /**
     * 写入舒适进入参数(cmd=7 key=0): [开关][落锁 u16LE][开锁 u16LE]。
     *
     * 车盒对这条帧不回执(原包也没等回执), 所以本地先乐观更新 Prefs,
     * 让界面立刻反映用户的改动; 700ms 后再读一次 7/32 用真实值校准 ——
     * 车盒低电量时会自己把舒适进入关掉, 只有重读才能发现。
     */
    private void setComfortEntry(Intent it) {
        if (it == null) {
            return;
        }
        boolean open = it.getBooleanExtra(EXTRA_CE_OPEN, false);
        int lock = it.getIntExtra(EXTRA_CE_LOCK, 0);
        int unlock = it.getIntExtra(EXTRA_CE_UNLOCK, 0);
        if (!connected) {
            Log.w(TAG, "comfort entry write skipped, not connected");
            return;
        }
        if (open && (lock <= 0 || unlock <= 0 || unlock >= lock)) {
            // 开锁阈值必须比落锁更强(数值更小), 否则会出现"走远也不落锁"
            Log.w(TAG, "comfort entry rejected: lock=" + lock + " unlock=" + unlock);
            return;
        }
        sendFrame(Proto.CMD_CFG, Proto.K_COMFORT, Proto.comfortEntry(open, lock, unlock));
        Prefs.put(this, Prefs.K_CE_OPEN, open);
        Prefs.put(this, Prefs.K_CE_LOCK, open ? lock : 0);
        Prefs.put(this, Prefs.K_CE_UNLOCK, open ? unlock : 0);
        Log.i(TAG, "comfort entry written: open=" + open + " lock=-" + lock + "dBm unlock=-" + unlock + "dBm");
        publishState(state, detail);
        h.postDelayed(new Runnable() {
            public void run() {
                sendFrame(Proto.CMD_CFG, Proto.K_COMFORT_RPT, null);
            }
        }, 700);
    }

    private void sendSystemInfo() {
        // TLV: tag1=时间戳(4B) 2=24小时制(1B) 3=时区(4B) 4=语言(1B) 19=手机类型(1B)
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int ts = (int) (System.currentTimeMillis() / 1000);
        int tz = (cal.get(java.util.Calendar.ZONE_OFFSET) + cal.get(java.util.Calendar.DST_OFFSET)) / 60000;
        byte[] p = new byte[6 + 3 + 6 + 3 + 3];
        int i = 0;
        p[i++] = 1;
        p[i++] = 4;
        p[i++] = (byte) ts;
        p[i++] = (byte) (ts >> 8);
        p[i++] = (byte) (ts >> 16);
        p[i++] = (byte) (ts >> 24);
        p[i++] = 2;
        p[i++] = 1;
        p[i++] = 1;
        p[i++] = 3;
        p[i++] = 4;
        p[i++] = (byte) tz;
        p[i++] = (byte) (tz >> 8);
        p[i++] = (byte) (tz >> 16);
        p[i++] = (byte) (tz >> 24);
        p[i++] = 4;
        p[i++] = 1;
        p[i++] = 0;   // 语言: 0 中文
        p[i++] = 19;
        p[i++] = 1;
        p[i] = 1;     // 手机类型 Android
        sendFrame(Proto.CMD_INFO, 0, p);
    }

    private void enqueue(byte[] f) {
        txq.add(f);
        if (!txBusy) {
            txNext();
        }
    }

    private void txNext() {
        if (txq.isEmpty()) {
            txBusy = false;
            return;
        }
        if (gatt == null || chIo == null || !connected) {
            txq.clear();
            txBusy = false;
            wxFails = 0;
            return;
        }
        txBusy = true;
        // 注意: 用 peek 而不是 poll, 写失败时帧要留在队列里重试, 不能丢
        byte[] f = txq.peek();
        int r = doWrite(f);
        if (r == 0) {
            txq.poll();
            wxFails = 0;
            h.postDelayed(txNextTask, 120);
            return;
        }
        wxFails++;
        if (wxFails > 6) {
            Log.w(TAG, "write give up, drop frame r=" + r);
            txq.poll();
            wxFails = 0;
            txBusy = false;
            return;
        }
        int delay = 120 + wxFails * 120;
        Log.w(TAG, "write retry #" + wxFails + " r=" + r + " in " + delay + "ms");
        h.postDelayed(txNextTask, delay);
    }

    private final Runnable txNextTask = new Runnable() {
        public void run() {
            txNext();
        }
    };

    /** 选写类型: 有带响应写就用默认写(原 App 走 Realtek SDK, 用的是带响应), 否则用无响应写。 */
    private int pickWriteType() {
        if (wxType >= 0) {
            return wxType;
        }
        int p = chIo.getProperties();
        boolean noRsp = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        boolean withRsp = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        wxType = (withRsp || !noRsp)
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        return wxType;
    }

    /** 返回 0 表示写请求已被栈接受, 非 0 是错误码。 */
    @SuppressLint("MissingPermission")
    private int doWrite(byte[] f) {
        try {
            int type = pickWriteType();
            int st;
            if (Build.VERSION.SDK_INT >= 33) {
                st = gatt.writeCharacteristic(chIo, f, type);
            } else {
                chIo.setWriteType(type);
                chIo.setValue(f);
                st = gatt.writeCharacteristic(chIo) ? 0 : -1;
            }
            if (st != 0) {
                Log.w(TAG, "write fail code=" + st + " type=" + type);
                // 换另一种写类型, 下次重试时生效
                wxType = (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            }
            return st;
        } catch (Exception e) {
            Log.w(TAG, "write exception", e);
            return -2;
        }
    }

    private void handleFrame(byte[] raw) {
        if (raw == null || raw.length < Proto.HEADER) {
            return;
        }
        Proto.Rsp r = Proto.parse(raw);
        if (r == null) {
            return;
        }
        Log.i(TAG, "rx cmd=" + r.cmd + " key=" + r.key + " len=" + r.payload.length
                + " " + Proto.hex(raw));
        switch (r.cmd) {
            case Proto.CMD_AUTH:
                onAuthResponse(r);
                break;
            case Proto.CMD_INFO:
                onSystemInfo(r);
                break;
            case Proto.CMD_CTRL:
                if (r.key == Proto.K_RSSI && r.payload.length >= 1) {
                    // 车机推的是 RSSI 绝对值: 0x24 = 36 表示 -36dBm
                    int v = r.payload[0];
                    final int rssi = v > 0 ? -v : v;
                    lastCarRssiTs = System.currentTimeMillis();
                    h.post(new Runnable() {
                        public void run() {
                            onRssi(rssi);
                        }
                    });
                }
                break;
            case Proto.CMD_CFG:
                if (r.key == Proto.K_COMFORT_RPT) {
                    onComfortEntry(r);
                }
                break;
            default:
                break;
        }
    }

    /**
     * 车机回报的舒适进入参数(cmd=7 key=32)。
     * payload: [0]=是否开启, [1..2]=落锁距离 u16LE, [3..4]=开锁距离 u16LE, [5..8]=错误码 u32LE
     * 距离是 RSSI 绝对值, 70 表示 -70dBm; 开锁要求信号更强(数值更小)才会触发。
     *
     * 注意: 这里只读不改。自动开锁是车机自己做的判断, 新 App 不参与,
     * 否则会和车机各判一次, 变成开两下。
     */
    private void onComfortEntry(Proto.Rsp r) {
        byte[] p = r.payload;
        if (p == null || p.length < 5) {
            return;
        }
        boolean open = p[0] == 1;
        int lockD = Proto.get16(p, 1);
        int unlockD = Proto.get16(p, 3);
        int err = p.length >= 9 ? Proto.get32(p, 5) : -1;
        Prefs.put(this, Prefs.K_CE_OPEN, open);
        Prefs.put(this, Prefs.K_CE_LOCK, lockD);
        Prefs.put(this, Prefs.K_CE_UNLOCK, unlockD);
        // 第一次读到就把这台车盒的实际标定存成基线, 供"恢复默认"写回。
        // 原包硬编码的 75/65 只是它自己打开总开关时的初值, 每台车盒实测并不一样。
        // 不要求 open: 有些车盒关掉自动开锁后仍会保留阈值, 那组值同样可用。
        if (lockD > 0 && unlockD > 0 && !Prefs.hasCeBase(this)) {
            Prefs.putCeBase(this, true, lockD, unlockD);
            Log.i(TAG, "comfort entry baseline captured: lock=-" + lockD + " unlock=-" + unlockD);
        }
        Log.i(TAG, "comfort entry: open=" + open + " unlock=-" + unlockD
                + "dBm lock=-" + lockD + "dBm err=" + err);
        // 借这条广播让界面把新读到的参数刷出来
        publishState(state, detail);
    }

    /** 给界面用的一句话描述。 */
    public static String ceText(Context c) {
        if (!Prefs.hasCe(c)) {
            return null;
        }
        if (!Prefs.ceOpen(c)) {
            return "车机自动开锁: 已关闭";
        }
        return "车机自动开锁: 开 · 开锁 ≤ -" + Prefs.ceUnlock(c)
                + "dBm · 落锁 ≥ -" + Prefs.ceLock(c) + "dBm";
    }

    /* ------------------------------ 电量 ------------------------------ */

    /**
     * 车机自述信息(cmd=2): payload 是 TLV 列表, tag=23 电量百分比, tag=32 电压 mV,
     * tag=33 充电状态。部分固件会连回两条一模一样的, 后到的覆盖前一条即可。
     */
    private void onSystemInfo(Proto.Rsp r) {
        Proto.Info info = Proto.parseInfo(r.payload);
        if (info == null) {
            return;
        }
        Log.i(TAG, "info " + info);
        if (!info.hasPower) {
            // 只有手机发过去的时间 TLV 被回显, 没有电量: 等下一次刷新
            return;
        }
        Prefs.put(this, Prefs.K_POWER, info.power);
        if (info.hasVoltage) {
            Prefs.put(this, Prefs.K_VOLTAGE, info.voltage);
        }
        if (info.charging >= 0) {
            Prefs.put(this, Prefs.K_CHARGING, info.charging);
        }
        Prefs.put(this, Prefs.K_POWER_TS, System.currentTimeMillis());
        // 刷新通知栏里的电量, 并通知界面
        setState(state, detail);
    }

    /** 电量的一句话描述, 给通知栏和界面共用。没读到过返回 null。 */
    public static String powerText(Context c) {
        int p = Prefs.power(c);
        if (p < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("电量 ").append(p).append('%');
        int mv = Prefs.voltage(c);
        if (mv > 0) {
            sb.append(" · ").append(String.format(java.util.Locale.ENGLISH, "%.2fV", mv / 1000.0));
        }
        return sb.toString();
    }

    /* ------------------------------ RSSI ------------------------------ */

    private final Runnable rssiTask = new Runnable() {
        public void run() {
            // 车机自己在每 2 秒推 cmd=4/key=107, 有推送就不必再问
            boolean carPushing = System.currentTimeMillis() - lastCarRssiTs < 10000;
            if (gatt != null && !carPushing) {
                try {
                    gatt.readRemoteRssi();
                } catch (Exception ignored) {
                }
            }
            h.postDelayed(this, 5000);
        }
    };

    private void onRssi(int rssi) {
        lastRssi = rssi;
        if (!ready) {
            return;
        }
        // 信号抖动不刷界面, 变化超过 5dB 才更新, 避免日志刷屏
        if (shownRssi != Integer.MIN_VALUE && Math.abs(rssi - shownRssi) < 5) {
            return;
        }
        shownRssi = rssi;
        setState(STATE_READY, String.format("已连接 (%d dBm)", Integer.valueOf(rssi)));
    }

    /* ------------------------------ 扫描(配对用) ------------------------------ */

    /** 扫到车机时回调 (addr, serialHex, name, rssi)。 */
    public interface ScanListener {
        void onFound(String addr, String serial, String name, int rssi);
    }

    private static ScanListener scanListener;

    public static void setScanListener(ScanListener l) {
        scanListener = l;
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        ScanFilter.Builder fb = new ScanFilter.Builder();
        fb.setManufacturerData(MANUFACTURER_ID, new byte[0], new byte[0]);
        filters.add(fb.build());
        ScanSettings.Builder sb = new ScanSettings.Builder();
        sb.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        try {
            scanner.startScan(filters, sb.build(), scanCallback);
            scanning = true;
        } catch (Exception e) {
            Log.w(TAG, "scan fail", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            handleScan(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                handleScan(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
        }
    };

    private void handleScan(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        ScanRecord rec = result.getScanRecord();
        byte[] md = rec == null ? null : rec.getManufacturerSpecificData(MANUFACTURER_ID);
        String serial = "";
        if (md != null && md.length >= 6) {
            serial = Proto.hex(md, 0, 6).toUpperCase();
        }
        String name = rec == null ? null : rec.getDeviceName();
        ScanListener l = scanListener;
        if (l != null) {
            l.onFound(result.getDevice().getAddress(), serial, name, result.getRssi());
        }
    }

    /* ------------------------------ 状态/通知 ------------------------------ */

    private void setState(String s, String d) {
        state = s;
        detail = d;
        lastState = s;
        lastDetail = d;
        publishState(s, d);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification());
        }
    }

    private void publishState(String s, String d) {
        Intent i = new Intent(BCAST_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, s);
        i.putExtra(EXTRA_DETAIL, d);
        i.putExtra(EXTRA_POWER, Prefs.power(this));
        sendBroadcast(i);
    }

    public String currentState() {
        return state;
    }

    public String currentDetail() {
        return detail;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "车钥匙连接", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, BleKeyService.class);
        i.setAction(a);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, code, i, flags);
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, flags);
        String text = detail == null || detail.length() == 0 ? "待连接" : detail;
        if (ready) {
            String pw = powerText(this);
            if (pw != null) {
                text = text + " · " + pw;
            }
        }
        b.setContentTitle("车钥匙")
                .setContentText(text)
                // 以前用 android.R.drawable.stat_sys_data_bluetooth, 状态栏里是个系统的
                // 老蓝牙符号, 跟应用图标对不上。换成自绘的单色钥匙剪影。
                .setSmallIcon(R.drawable.ic_stat_blekey)
                .setContentIntent(pi)
                .setOngoing(true);
        // 不要再调 setLargeIcon: MIUI/HyperOS 的通知卡片会把"应用图标"画在左边、
        // largeIcon 画在右边, 两个都设就出现一对重复的图标。只留左边的应用图标。
        if (ready) {
            // 系统 UI 最多显示 3 个按钮。让位不常用(App 里还有入口), 换成后备箱:
            // 双手拿东西时下拉通知栏点一下就能开, 不必进 App 找按钮。
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_lock_idle_lock, "解锁",
                    action(ACTION_UNLOCK, 10)).build());
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_upload, "后备箱",
                    action(ACTION_TRUNK, 13)).build());
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_lock_lock, "落锁",
                    action(ACTION_LOCK, 11)).build());
        }
        return b.build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 30) {
            // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE = 16 (API 30 才有)
            startForeground(NOTIF_ID, n, 16);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        super.onDestroy();
    }

    /**
     * 用户从最近任务里划掉时仍然继续跑 —— 车钥匙必须常驻,
     * 被划掉不能等于断开连接。只续一次闹钟兜底, 由它决定要不要重来。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "task removed, keep alive");
        KeepAlive.scheduleJob(this);
        KeepAlive.scheduleAlarm(this, 3000);
    }
}
