package com.blekey.rdb;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 红树河熊 BLE 车钥匙协议层。
 *
 * 帧格式(16 字节头 + payload, 全部小端):
 *   [0..3]   上行 55 AA 00 00 / 下行 AA 55 ver type|channel
 *   [4..5]   sequenceId
 *   [6..7]   cmd
 *   [8..9]   key
 *   [10..11] payload 长度
 *   [12..13] payload 字节求和(16 位)
 *   [14..15] 头部 [0..13] 字节求和(16 位, 含上一条)
 *
 * 注意: 原始 App 里叫 crc8WithData, 实际是逐字节求和, 不是 CRC。
 */
public final class Proto {

    public static final int HEADER = 16;

    /* cmd */
    public static final int CMD_INFO   = 2;
    public static final int CMD_CTRL   = 4;
    public static final int CMD_FLASH  = 5;
    public static final int CMD_AUTH   = 6;
    public static final int CMD_CFG    = 7;
    public static final int CMD_SYNC   = 8;

    /* auth key */
    public static final int AUTH_QUERY     = 0;   // 请求鉴权
    public static final int AUTH_SET_KEY   = 1;   // 下发设备密钥
    public static final int AUTH_VERIFY    = 2;   // 校验设备密钥
    public static final int AUTH_UNBIND    = 3;   // 解绑
    public static final int AUTH_MANUAL    = 4;   // 请求车机手动确认
    public static final int AUTH_CONFIRM   = 5;   // 确认结果
    public static final int AUTH_MQTT      = 6;

    /* ctrl key */
    public static final int K_QUICK_UNLOCK = 100;
    public static final int K_QUICK_LOCK   = 101;
    public static final int K_TRUNK        = 102;
    public static final int K_FUNCTION     = 103;
    public static final int K_UNLOCK       = 104;
    public static final int K_LOCK         = 105;
    public static final int K_QUICK_START  = 106;
    public static final int K_RSSI         = 107;
    public static final int K_DROP_WINDOW  = 114;
    public static final int K_QUICK_TRUNK  = 115;

    /* cfg key */
    public static final int K_COMFORT      = 0;   // 舒适进入: 写参数(手机 -> 车机)
    public static final int K_COMFORT_RPT  = 32;  // 舒适进入: 参数回报(车机 -> 手机)
    public static final int K_CAR_LOCK     = 1;
    public static final int K_UNLOCK_CFG   = 4;

    /* auth type(车机回报的绑定状态) */
    public static final int BOND_FREE      = 0;   // 车机未绑定任何设备
    public static final int BOND_BOUND     = 1;   // 车机已绑定

    /* cmd=2(系统信息同步)响应里的 TLV tag, 取值与原 App 的 TlvTag 一致 */
    public static final int TAG_POWER       = 23;  // 1B  电量百分比 0~100
    public static final int TAG_VER_MAJOR   = 24;  // 2B
    public static final int TAG_VER_MINOR   = 25;  // 2B
    public static final int TAG_VER_PATCH   = 26;  // 2B
    public static final int TAG_DEV_TYPE    = 27;  // 4B  1 车钥匙 / 2 车盒
    public static final int TAG_PLATFORM    = 28;  // 4B
    public static final int TAG_MATCH_NUM   = 29;  // 4B
    public static final int TAG_BOND_STATE  = 30;  // 1B
    public static final int TAG_CPU_ID      = 31;  // nB
    public static final int TAG_VOLTAGE     = 32;  // 2B  电池电压 mV
    public static final int TAG_CHARGING    = 33;  // 1B  0 未充电 / 1 充电中
    public static final int TAG_POWER_STATE = 34;  // 1B  车盒开关机状态
    public static final int TAG_CAR_TYPE    = 35;  // 4B

    private Proto() {
    }

    /* ---------------- 组帧 ---------------- */

    public static byte[] frame(int seq, int cmd, int key, byte[] payload) {
        int len = (payload == null) ? 0 : payload.length;
        byte[] out = new byte[HEADER + len];
        out[0] = 0x55;
        out[1] = (byte) 0xAA;
        out[2] = 0;
        out[3] = 0;
        put16(out, 4, seq);
        put16(out, 6, cmd);
        put16(out, 8, key);
        put16(out, 10, len);
        put16(out, 12, sum(payload, 0, len));
        put16(out, 14, sum(out, 0, 14));   // [0..13] 共 14 字节
        if (len > 0) {
            System.arraycopy(payload, 0, out, HEADER, len);
        }
        return out;
    }

    public static byte[] frame(int seq, int cmd, int key) {
        return frame(seq, cmd, key, null);
    }

    /* ---------------- 解帧 ---------------- */

    public static final class Rsp {
        public int version;
        public int type;
        public int channel;
        public int seq;
        public int cmd;
        public int key;
        public int dataLen;
        public int dataSum;
        public int headerSum;
        public byte[] payload;

        public boolean valid() {
            return payload != null;
        }
    }

    public static Rsp parse(byte[] b) {
        if (b == null || b.length < HEADER) {
            return null;
        }
        // 上行 55 AA -> 0xAA55; 下行 AA 55 -> 0x55AA。两种都接受。
        int flag = (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
        if (flag != 0xAA55 && flag != 0x55AA) {
            return null;
        }
        Rsp r = new Rsp();
        r.version = b[2] & 0xFF;
        int t = b[3] & 0xFF;
        r.type = t & 0x0F;
        r.channel = (t & 0xF0) >> 4;
        r.seq = get16(b, 4);
        r.cmd = get16(b, 6);
        r.key = get16(b, 8);
        r.dataLen = get16(b, 10);
        r.dataSum = get16(b, 12);
        r.headerSum = get16(b, 14);
        int n = b.length - HEADER;
        r.payload = new byte[n];
        System.arraycopy(b, HEADER, r.payload, 0, n);
        return r;
    }

    /* ---------------- 鉴权响应 ---------------- */

    /** 车机鉴权响应的解析结果。 */
    public static final class AuthRsp {
        public int type = -1;          // 0 未绑定 / 1 已绑定
        public boolean keyConfirm;     // 是否允许直接下发密钥
        public String macId = "";      // 车机 MAC
        public String random = "";     // 32 hex, 校验时作为 AES 明文
        public int errorCode = -1;
        public String iccid = "";
        public boolean hasRandom() {
            return random != null && random.length() == 32;
        }
    }

    /**
     * 解析 cmd=6 的响应。
     * key=0: payload[0]=type, 最高位为 1 表示 TLV 格式
     * key=1/2/4: payload[0..3] = errorCode
     * key=5: errorCode(4) + random(16)
     */
    public static AuthRsp parseAuth(Rsp r) {
        if (r == null || r.payload == null) {
            return null;
        }
        AuthRsp a = new AuthRsp();
        byte[] p = r.payload;
        if (r.key == AUTH_QUERY) {
            if (p.length == 0) {
                return a;
            }
            int t = p[0] & 0xFF;
            a.type = t & 0x7F;
            if ((t & 0x80) != 0) {
                parseTlv(p, 1, a);
            } else if (p.length >= 25) {
                // 旧格式: macId(6) + number(4) + deviceType(4) + platform(4)
                //         + ver 3*2 + random(16) + [45]=keyConfirm
                a.macId = hex(p, 1, 6);
                if (p.length >= 41) {
                    a.random = hex(p, 25, 16);
                }
                if (p.length >= 46) {
                    a.keyConfirm = p[45] == 1;
                }
            }
            return a;
        }
        if (r.key == AUTH_SET_KEY || r.key == AUTH_VERIFY || r.key == AUTH_MANUAL
                || r.key == AUTH_UNBIND) {
            if (p.length >= 4) {
                a.errorCode = get32(p, 0);
            }
            return a;
        }
        if (r.key == AUTH_CONFIRM) {
            if (p.length >= 4) {
                a.errorCode = get32(p, 0);
            }
            if (p.length >= 20) {
                a.random = hex(p, 4, 16);
            }
            return a;
        }
        a.errorCode = p.length >= 4 ? get32(p, 0) : -1;
        return a;
    }

    private static void parseTlv(byte[] p, int offset, AuthRsp a) {
        int i = offset;
        while (i + 1 < p.length) {
            int tag = p[i] & 0xFF;
            int len = p[i + 1] & 0xFF;
            if (i + 2 + len > p.length) {
                return;
            }
            switch (tag) {
                case 1:  // AUTH_TYPE -> keyConfirm
                    a.keyConfirm = p[i + 2] == 1;
                    break;
                case 2:  // AUTH_BOND_STATE -> type
                    a.type = p[i + 2] & 0xFF;
                    break;
                case 5:  // AUTH_MATCH_NUM
                    break;
                case 11: // AUTH_RANDOM
                    a.random = hex(p, i + 2, len);
                    break;
                case 20: // AUTH_MAC_ID
                    a.macId = hex(p, i + 2, len);
                    break;
                case 21: // AUTH_4G_ICCID
                    a.iccid = new String(p, i + 2, len, StandardCharsets.UTF_8);
                    break;
                default:
                    break;
            }
            i += 2 + len;
        }
    }

    /* ---------------- 系统信息(cmd=2) ---------------- */

    /**
     * cmd=2 响应的解析结果。payload 是纯 TLV 列表, 与原 App 的
     * FtkBean.parseInfoSyncResp 一一对应, 只是这里只取我们显示的字段。
     */
    public static final class Info {
        public boolean hasPower;
        public int power;              // 电量百分比 0~100
        public boolean hasVoltage;
        public int voltage;            // 电池电压 mV
        public int charging = -1;      // 0 未充电 / 1 充电中, -1 未知
        public int powerState = -1;    // 车盒开关机状态
        public int bondState = -1;     // 0 未绑定 / 1 已绑定
        public int deviceType = -1;    // 1 车钥匙 / 2 车盒
        public int platform = -1;
        public String version = "";
        public String cpuId = "";

        public String toString() {
            return "Info{power=" + (hasPower ? power + "%" : "?")
                    + ", voltage=" + (hasVoltage ? voltage + "mV" : "?")
                    + ", charging=" + charging
                    + ", powerState=" + powerState
                    + ", bondState=" + bondState
                    + ", deviceType=" + deviceType
                    + ", platform=" + platform
                    + ", version='" + version + "'}";
        }
    }

    /**
     * 解析 cmd=2 响应的 payload。
     *
     * 注意: 车机对「手机发去的系统信息同步」会回一条自述 TLV, 里面就带电量;
     * 有的固件会先回显手机发去的 tag(1/2/3/4/19), 再回自己的 tag(23 等),
     * 所以这里按 tag 取值, 不认识的就跳过。
     */
    public static Info parseInfo(byte[] p) {
        if (p == null || p.length < 2) {
            return null;
        }
        Info info = new Info();
        int i = 0;
        while (i + 1 < p.length) {
            int tag = p[i] & 0xFF;
            int len = p[i + 1] & 0xFF;
            int v = i + 2;
            if (v + len > p.length) {
                // 长度字段坏了就停, 不要越界
                break;
            }
            switch (tag) {
                case TAG_POWER:
                    if (len >= 1) {
                        info.power = p[v] & 0xFF;
                        info.hasPower = true;
                    }
                    break;
                case TAG_VOLTAGE:
                    if (len >= 2) {
                        info.voltage = get16(p, v);
                        info.hasVoltage = true;
                    }
                    break;
                case TAG_CHARGING:
                    if (len >= 1) {
                        info.charging = p[v] & 0xFF;
                    }
                    break;
                case TAG_POWER_STATE:
                    if (len >= 1) {
                        info.powerState = p[v] & 0xFF;
                    }
                    break;
                case TAG_BOND_STATE:
                    if (len >= 1) {
                        info.bondState = p[v] & 0xFF;
                    }
                    break;
                case TAG_DEV_TYPE:
                    if (len >= 4) {
                        info.deviceType = get32(p, v);
                    }
                    break;
                case TAG_PLATFORM:
                    if (len >= 4) {
                        info.platform = get32(p, v);
                    }
                    break;
                case TAG_VER_MAJOR:
                case TAG_VER_MINOR:
                case TAG_VER_PATCH:
                    if (len >= 2) {
                        info.version = info.version.length() == 0
                                ? String.valueOf(get16(p, v))
                                : info.version + "." + get16(p, v);
                    }
                    break;
                case TAG_CPU_ID:
                    info.cpuId = hex(p, v, len);
                    break;
                default:
                    break;
            }
            i = v + len;
        }
        return info;
    }

    /* ---------------- 密钥 ---------------- */

    /** 把字符串按 UTF-8 补齐/截断到固定长度。 */
    public static byte[] pad(String s, int len) {
        byte[] src = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (src.length == len) {
            return src;
        }
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
        return out;
    }

    /** 手机侧生成 6 位数字口令(与原 App 一致)。 */
    public static String newPassword() {
        int v = (int) (Math.random() * 900000.0d) + 100000;
        return String.format(java.util.Locale.ENGLISH, "%06d", Integer.valueOf(v));
    }

    /** deviceKey = AES-ECB(pad(password,16), pad(macHex,16)) */
    public static byte[] buildDeviceKey(String password, String macHex) {
        return aes(pad(password, 16), pad(macHex.toUpperCase(), 16));
    }

    public static byte[] aes(byte[] key16, byte[] data16) {
        try {
            SecretKeySpec ks = new SecretKeySpec(key16, "AES");
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, ks);
            return c.doFinal(data16);
        } catch (Exception e) {
            throw new RuntimeException("aes fail", e);
        }
    }

    /* ---------------- 工具 ---------------- */

    public static int sum(byte[] b, int from, int len) {
        if (b == null) {
            return 0;
        }
        int s = 0;
        int end = Math.min(from + len, b.length);
        for (int i = from; i < end; i++) {
            s += b[i] & 0xFF;
        }
        return s & 0xFFFF;
    }

    public static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    public static int get16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static int get32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * 舒适进入参数写入帧的 payload(cmd=7 key=0):
     * [0] 开关, [1..2] 落锁阈值 u16LE, [3..4] 开锁阈值 u16LE。
     *
     * 阈值是 RSSI 的绝对值, 75 表示 -75dBm。开锁必须比落锁"更强"(数值更小),
     * 否则会出现"走远了也不落锁"。关闭时按原包做法填 0/0。
     */
    public static byte[] comfortEntry(boolean open, int lock, int unlock) {
        byte[] p = new byte[5];
        p[0] = (byte) (open ? 1 : 0);
        put16(p, 1, open ? lock : 0);
        put16(p, 3, open ? unlock : 0);
        return p;
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len && i < b.length; i++) {
            sb.append(String.format("%02x", Integer.valueOf(b[i] & 0xFF)));
        }
        return sb.toString();
    }

    public static String hex(byte[] b) {
        return b == null ? "" : hex(b, 0, b.length);
    }

    public static byte[] unhex(String s) {
        if (s == null) {
            return new byte[0];
        }
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

}
