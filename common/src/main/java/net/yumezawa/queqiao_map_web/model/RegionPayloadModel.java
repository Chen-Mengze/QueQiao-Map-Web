package net.yumezawa.queqiao_map_web.model;

/**
 * wire DTO：一个 512x512 区域的压缩快照（REQ-PROTO-005）。
 * Gson 反射直出，字段名单单词 camelCase：rx/rz/format/xor/sha1/b64。
 * rx/rz 为 MC 区域坐标（覆盖区块 [rx*32,(rx+1)*32) x [rz*32,(rz+1)*32)）。
 * format 复用 "zlib-pal8" token：像素 payload = pal8 512*512 行主序 -> zlib。
 * xor 为 store 侧原始 RGB24 的 u32 校验（§2.5）；sha1 为 b64 压缩字节的 hex 校验（§3.3）。
 */
public final class RegionPayloadModel {

    private final int rx;
    private final int rz;
    private final String format;
    private final int xor;
    private final String sha1;
    private final String b64;

    public RegionPayloadModel(int rx, int rz, String format, int xor, String sha1, String b64) {
        this.rx = rx;
        this.rz = rz;
        this.format = format;
        this.xor = xor;
        this.sha1 = sha1;
        this.b64 = b64;
    }

    public int getRx() {
        return rx;
    }

    public int getRz() {
        return rz;
    }

    public String getFormat() {
        return format;
    }

    public int getXor() {
        return xor;
    }

    public String getSha1() {
        return sha1;
    }

    public String getB64() {
        return b64;
    }
}
